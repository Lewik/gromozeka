package com.gromozeka.presentation.services

import com.gromozeka.domain.model.UserProfile
import com.gromozeka.remote.protocol.RemoteLiveAudioChunk
import klog.KLoggers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collect

private typealias LocalWhisperLiveStreaming = UserProfile.SpeechSettings.SpeechToText.LocalWhisper.LiveStreaming

interface ClientLiveAudioStreamer {
    suspend fun start(
        scope: CoroutineScope,
        onChunk: suspend (RemoteLiveAudioChunk) -> Unit,
    ): ClientLiveAudioStreamingSession
}

interface ClientLiveAudioStreamingSession {
    suspend fun stop()
}

class RollingClientLiveAudioStreamer(
    private val recorder: ClientAudioRecorder,
    private val liveStreamingSettings: () -> LocalWhisperLiveStreaming = { LocalWhisperLiveStreaming() },
) : ClientLiveAudioStreamer {
    private val log = KLoggers.logger(this)
    private val sampleRate = 16_000
    private val channels = 1
    private val bitDepth = 16
    private val frameSizeBytes = channels * (bitDepth / 8)

    override suspend fun start(
        scope: CoroutineScope,
        onChunk: suspend (RemoteLiveAudioChunk) -> Unit,
    ): ClientLiveAudioStreamingSession {
        val settings = liveStreamingSettings()
        val windowMillis = settings.windowMillis
        val stepMillis = settings.stepMillis
        val minWindowMillis = settings.minWindowMillis
        val maxAdaptiveWindowMillis = settings.maximumAdaptiveWindowMillis

        require(windowMillis > 0) { "Live audio window duration must be positive" }
        require(stepMillis > 0) { "Live audio step duration must be positive" }
        require(minWindowMillis > 0) { "Live audio minimum window duration must be positive" }
        require(maxAdaptiveWindowMillis >= windowMillis) {
            "Live audio maximum adaptive window must be greater than or equal to window duration"
        }
        return if (recorder.supportsStreamingAudioChunks) {
            startOverlappingStream(scope, onChunk, settings)
        } else {
            startSegmentedStream(scope, onChunk, settings)
        }
    }

    private suspend fun startOverlappingStream(
        scope: CoroutineScope,
        onChunk: suspend (RemoteLiveAudioChunk) -> Unit,
        settings: LocalWhisperLiveStreaming,
    ): ClientLiveAudioStreamingSession {
        val stopSignal = CompletableDeferred<Unit>()
        val recordingSession = recorder.start(scope)
        val bufferMutex = Mutex()
        var pendingRawBuffer = ByteArray(0)
        var overlapBuffer = ByteArray(0)
        val windowBytes = bytesForMillis(settings.windowMillis)
        val stepMillis = settings.stepMillis
        val minWindowBytes = bytesForMillis(settings.minWindowMillis)
        val overlapBytes = bytesForMillis(settings.overlapMillis)
        val maxWindowBytes = bytesForMillis(settings.maximumAdaptiveWindowMillis)

        val collectorJob = scope.launch {
            recordingSession.audioChunks.collect { chunk ->
                bufferMutex.withLock {
                    pendingRawBuffer += chunk
                }
            }
        }

        val senderJob = scope.launch {
            var sequenceNumber = 0
            while (isActive) {
                if (!stopSignal.isCompleted) {
                    withTimeoutOrNull(stepMillis) {
                        stopSignal.await()
                    }
                }
                val window = bufferMutex.withLock {
                    val pendingRawBytes = pendingRawBuffer.size.toLong()
                    if (pendingRawBuffer.isEmpty()) {
                        null
                    } else if (pendingRawBuffer.size < minWindowBytes && !stopSignal.isCompleted) {
                        null
                    } else {
                        val audioWindow = overlapBuffer + pendingRawBuffer
                        val windowSizeBytes = windowBytes(
                            pendingRawBytes = pendingRawBytes,
                            defaultWindowBytes = windowBytes,
                            overlapBytes = overlapBytes,
                        )
                        if (windowSizeBytes > windowBytes) {
                            log.info {
                                "Live audio adaptive window expanded: pendingMillis=${bytesToMillis(pendingRawBytes)} " +
                                    "windowMillis=${bytesToMillis(windowSizeBytes.toLong())}"
                            }
                        }
                        if (audioWindow.size > maxWindowBytes) {
                            log.warn {
                                "Live audio capture exceeded max adaptive window; preserving full backlog: " +
                                    "windowMillis=${bytesToMillis(audioWindow.size.toLong())} " +
                                    "configuredMaxWindowMillis=${settings.maximumAdaptiveWindowMillis}"
                            }
                        }
                        pendingRawBuffer = ByteArray(0)
                        overlapBuffer = audioWindow.takeLastBytes(overlapBytes)
                        audioWindow
                    }
                }
                if (window != null && window.isNotEmpty()) {
                    onChunk(window.toRemoteLiveWavChunk(sequenceNumber++))
                }
                if (stopSignal.isCompleted) {
                    val hasPendingAudio = bufferMutex.withLock { pendingRawBuffer.isNotEmpty() }
                    if (!hasPendingAudio) {
                        break
                    }
                }
            }
        }
        log.info {
            "Live audio overlapping streamer started: profile=${settings.profile} " +
                "windowMillis=${settings.windowMillis} stepMillis=${settings.stepMillis} " +
                "maxAdaptiveWindowMillis=${settings.maximumAdaptiveWindowMillis}"
        }
        return object : ClientLiveAudioStreamingSession {
            override suspend fun stop() {
                recordingSession.cancel()
                collectorJob.join()
                stopSignal.complete(Unit)
                senderJob.join()
                collectorJob.cancelAndJoin()
                log.info { "Live audio overlapping streamer stopped" }
            }
        }
    }

    private fun startSegmentedStream(
        scope: CoroutineScope,
        onChunk: suspend (RemoteLiveAudioChunk) -> Unit,
        settings: LocalWhisperLiveStreaming,
    ): ClientLiveAudioStreamingSession {
        val stopSignal = CompletableDeferred<Unit>()
        val senderJob = scope.launch {
            var sequenceNumber = 0
            while (isActive && !stopSignal.isCompleted) {
                val recordingSession = recorder.start(scope)
                try {
                    withTimeoutOrNull(settings.windowMillis) {
                        stopSignal.await()
                    }
                    val recorded = recordingSession.stop()
                    if (recorded.byteSize > 0) {
                        onChunk(recorded.toRemoteLiveAudioChunk(sequenceNumber++))
                    }
                } catch (error: Throwable) {
                    recordingSession.cancel()
                    throw error
                }
            }
        }
        log.info { "Live audio segmented streamer started: segmentMillis=${settings.windowMillis}" }
        return object : ClientLiveAudioStreamingSession {
            override suspend fun stop() {
                stopSignal.complete(Unit)
                senderJob.join()
                log.info { "Live audio segmented streamer stopped" }
            }
        }
    }

    private fun bytesForMillis(millis: Long): Int =
        (sampleRate * frameSizeBytes * millis / 1000L).toInt()

    private fun bytesToMillis(bytes: Long): Long =
        bytes * 1000L / (sampleRate * frameSizeBytes)

    private fun windowBytes(
        pendingRawBytes: Long,
        defaultWindowBytes: Int,
        overlapBytes: Int,
    ): Int =
        LiveAudioWindowSizing.backlogPreservingWindowBytes(
            pendingRawBytes = pendingRawBytes,
            defaultWindowBytes = defaultWindowBytes,
            overlapBytes = overlapBytes,
        )

    private fun ByteArray.toRemoteLiveWavChunk(sequenceNumber: Int): RemoteLiveAudioChunk =
        RemoteLiveAudioChunk(
            sequenceNumber = sequenceNumber,
            data = toWav16BitMonoBigEndianPcm(),
            mediaType = "audio/wav",
            fileExtension = "wav",
            sampleRate = sampleRate,
            channels = channels,
            bitDepth = bitDepth,
        )

    private fun ByteArray.toWav16BitMonoBigEndianPcm(): ByteArray {
        val littleEndianPcm = ByteArray(size)
        var index = 0
        while (index + 1 < size) {
            littleEndianPcm[index] = this[index + 1]
            littleEndianPcm[index + 1] = this[index]
            index += 2
        }
        if (index < size) {
            littleEndianPcm[index] = this[index]
        }
        return wav16BitMono(sampleRate, littleEndianPcm)
    }
}

object NoOpClientLiveAudioStreamer : ClientLiveAudioStreamer {
    override suspend fun start(
        scope: CoroutineScope,
        onChunk: suspend (RemoteLiveAudioChunk) -> Unit,
    ): ClientLiveAudioStreamingSession =
        error("Client live audio streaming is not available on this platform")
}

fun ClientRecordedAudio.toRemoteLiveAudioChunk(sequenceNumber: Int): RemoteLiveAudioChunk =
    RemoteLiveAudioChunk(
        sequenceNumber = sequenceNumber,
        data = data,
        mediaType = mediaType,
        fileExtension = fileExtension,
        sampleRate = sampleRate,
        channels = channels,
        bitDepth = bitDepth,
    )

private fun ByteArray.takeLastBytes(maxSize: Int): ByteArray =
    if (size <= maxSize) this else copyOfRange(size - maxSize, size)

private fun wav16BitMono(sampleRate: Int, pcmLittleEndian: ByteArray): ByteArray {
    val output = ByteArray(44 + pcmLittleEndian.size)
    output.writeAscii(0, "RIFF")
    output.writeLittleEndianInt(4, 36 + pcmLittleEndian.size)
    output.writeAscii(8, "WAVE")
    output.writeAscii(12, "fmt ")
    output.writeLittleEndianInt(16, 16)
    output.writeLittleEndianShort(20, 1)
    output.writeLittleEndianShort(22, 1)
    output.writeLittleEndianInt(24, sampleRate)
    output.writeLittleEndianInt(28, sampleRate * 2)
    output.writeLittleEndianShort(32, 2)
    output.writeLittleEndianShort(34, 16)
    output.writeAscii(36, "data")
    output.writeLittleEndianInt(40, pcmLittleEndian.size)
    pcmLittleEndian.copyInto(output, destinationOffset = 44)
    return output
}

private fun ByteArray.writeAscii(offset: Int, value: String) {
    value.encodeToByteArray().copyInto(this, destinationOffset = offset)
}

private fun ByteArray.writeLittleEndianInt(offset: Int, value: Int) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
    this[offset + 2] = ((value ushr 16) and 0xff).toByte()
    this[offset + 3] = ((value ushr 24) and 0xff).toByte()
}

internal object LiveAudioWindowSizing {
    fun backlogPreservingWindowBytes(
        pendingRawBytes: Long,
        defaultWindowBytes: Int,
        overlapBytes: Int,
    ): Int {
        val windowBytes = maxOf(defaultWindowBytes.toLong(), pendingRawBytes + overlapBytes)
        require(windowBytes <= Int.MAX_VALUE) {
            "Live audio backlog is too large to fit into one in-memory window: $windowBytes bytes"
        }
        return windowBytes.toInt()
    }
}

private fun ByteArray.writeLittleEndianShort(offset: Int, value: Int) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
}
