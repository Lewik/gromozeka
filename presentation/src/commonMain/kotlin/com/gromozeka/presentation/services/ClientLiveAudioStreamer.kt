package com.gromozeka.presentation.services

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
    private val windowMillis: Long = 12_000L,
    private val stepMillis: Long = 6_000L,
    private val minWindowMillis: Long = 4_000L,
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
        require(windowMillis > 0) { "Live audio window duration must be positive" }
        require(stepMillis > 0) { "Live audio step duration must be positive" }
        require(minWindowMillis > 0) { "Live audio minimum window duration must be positive" }
        return if (recorder.supportsStreamingAudioChunks) {
            startOverlappingStream(scope, onChunk)
        } else {
            startSegmentedStream(scope, onChunk)
        }
    }

    private suspend fun startOverlappingStream(
        scope: CoroutineScope,
        onChunk: suspend (RemoteLiveAudioChunk) -> Unit,
    ): ClientLiveAudioStreamingSession {
        val stopSignal = CompletableDeferred<Unit>()
        val recordingSession = recorder.start(scope)
        val bufferMutex = Mutex()
        var rawBuffer = ByteArray(0)
        var totalRawBytes = 0
        var lastSentTotalRawBytes = 0
        val windowBytes = bytesForMillis(windowMillis)
        val minWindowBytes = bytesForMillis(minWindowMillis)
        val maxBufferBytes = windowBytes * 2

        val collectorJob = scope.launch {
            recordingSession.audioChunks.collect { chunk ->
                bufferMutex.withLock {
                    totalRawBytes += chunk.size
                    rawBuffer = (rawBuffer + chunk).takeLastBytes(maxBufferBytes)
                }
            }
        }

        val senderJob = scope.launch {
            var sequenceNumber = 0
            while (isActive && !stopSignal.isCompleted) {
                withTimeoutOrNull(stepMillis) {
                    stopSignal.await()
                }
                val window = bufferMutex.withLock {
                    if (totalRawBytes == lastSentTotalRawBytes) {
                        null
                    } else if (rawBuffer.size < minWindowBytes && !stopSignal.isCompleted) {
                        null
                    } else {
                        lastSentTotalRawBytes = totalRawBytes
                        rawBuffer.takeLastBytes(windowBytes)
                    }
                }
                if (window != null && window.isNotEmpty()) {
                    onChunk(window.toRemoteLiveWavChunk(sequenceNumber++))
                }
            }
        }
        log.info { "Live audio overlapping streamer started: windowMillis=$windowMillis stepMillis=$stepMillis" }
        return object : ClientLiveAudioStreamingSession {
            override suspend fun stop() {
                stopSignal.complete(Unit)
                senderJob.join()
                collectorJob.cancelAndJoin()
                recordingSession.cancel()
                log.info { "Live audio overlapping streamer stopped" }
            }
        }
    }

    private fun startSegmentedStream(
        scope: CoroutineScope,
        onChunk: suspend (RemoteLiveAudioChunk) -> Unit,
    ): ClientLiveAudioStreamingSession {
        val stopSignal = CompletableDeferred<Unit>()
        val senderJob = scope.launch {
            var sequenceNumber = 0
            while (isActive && !stopSignal.isCompleted) {
                val recordingSession = recorder.start(scope)
                try {
                    withTimeoutOrNull(windowMillis) {
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
        log.info { "Live audio segmented streamer started: segmentMillis=$windowMillis" }
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

private fun ByteArray.writeLittleEndianShort(offset: Int, value: Int) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
}
