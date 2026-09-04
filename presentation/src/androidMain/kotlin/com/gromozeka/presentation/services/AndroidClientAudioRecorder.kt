package com.gromozeka.presentation.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.shared.audio.SpeechPcmWav
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

interface AndroidMicrophonePermissionRequester {
    suspend fun requestMicrophonePermission(): Boolean
}

class AndroidClientAudioRecorder(
    context: Context,
    private val permissionRequester: AndroidMicrophonePermissionRequester,
) : ClientAudioRecorder {
    private val applicationContext = context.applicationContext
    private val startMutex = Mutex()
    private val activeSessionLock = Any()
    private val closed = AtomicBoolean(false)
    private var activeSession: AndroidClientAudioRecordingSession? = null

    override val supportsStreamingAudioChunks: Boolean = true

    override suspend fun start(scope: CoroutineScope): ClientAudioRecordingSession = startMutex.withLock {
        check(!closed.get()) { "Android audio recorder is closed" }
        check(scope.isActive) { "Android audio recording scope is not active" }
        check(synchronized(activeSessionLock) { activeSession == null }) {
            "Android audio recording is already active"
        }
        ensureMicrophonePermission()
        check(!closed.get()) { "Android audio recorder is closed" }
        check(scope.isActive) { "Android audio recording scope is not active" }

        val recorder = createAudioRecord()
        lateinit var session: AndroidClientAudioRecordingSession
        session = AndroidClientAudioRecordingSession(recorder, scope) {
            synchronized(activeSessionLock) {
                if (activeSession === session) activeSession = null
            }
        }
        synchronized(activeSessionLock) {
            check(activeSession == null) { "Android audio recording is already active" }
            activeSession = session
        }
        try {
            session.start()
            session
        } catch (error: Throwable) {
            session.cancel()
            synchronized(activeSessionLock) {
                if (activeSession === session) activeSession = null
            }
            throw error
        }
    }

    fun shutdown() {
        closed.set(true)
        synchronized(activeSessionLock) { activeSession }?.cancel()
    }

    private suspend fun ensureMicrophonePermission() {
        if (hasMicrophonePermission()) return
        check(permissionRequester.requestMicrophonePermission() && hasMicrophonePermission()) {
            "Microphone permission denied"
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SpeechPcmWav.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferSize > 0) { "Android microphone does not support 16 kHz mono PCM16 recording" }
        val bufferSize = max(minBufferSize, AUDIO_BUFFER_SIZE_BYTES).alignToPcm16Frame()
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SpeechPcmWav.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("Android microphone could not be initialized")
        }
        return recorder
    }

    private companion object {
        const val AUDIO_BUFFER_DURATION_MILLIS = 100
        const val PCM16_BYTES_PER_SAMPLE = 2
        const val AUDIO_BUFFER_SIZE_BYTES =
            SpeechPcmWav.SAMPLE_RATE * PCM16_BYTES_PER_SAMPLE * AUDIO_BUFFER_DURATION_MILLIS / 1_000
    }
}

private class AndroidClientAudioRecordingSession(
    private val recorder: AudioRecord,
    private val scope: CoroutineScope,
    private val onFinished: () -> Unit,
) : ClientAudioRecordingSession {
    private val terminationRequested = AtomicBoolean(false)
    private val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val rawPcm = ByteArrayOutputStream()
    private var captureFailure: Throwable? = null
    private lateinit var captureJob: Job

    override val audioChunks: Flow<ByteArray> = audioChannel.consumeAsFlow()

    fun start() {
        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Android microphone did not enter the recording state"
            }
            captureJob = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                captureAudio()
            }
        } catch (error: Throwable) {
            terminationRequested.set(true)
            runCatching { recorder.release() }
            audioChannel.close(error)
            onFinished()
            throw error
        }
    }

    override suspend fun stop(): ClientRecordedAudio {
        if (terminationRequested.compareAndSet(false, true)) {
            stopRecorder()
            captureJob.cancel()
        }
        captureJob.join()
        captureFailure?.let { throw it }
        return ClientRecordedAudio(
            data = SpeechPcmWav.encode(rawPcm.toByteArray()),
            format = SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ,
        )
    }

    override fun cancel() {
        if (!terminationRequested.compareAndSet(false, true)) return
        stopRecorder()
        if (::captureJob.isInitialized) captureJob.cancel()
    }

    private suspend fun captureAudio() {
        val frameBuffer = PcmFrameBuffer(frameSizeBytes = PCM16_FRAME_SIZE_BYTES, startPrebufferBytes = 0)
        val readBuffer = ByteArray(
            max(recorder.bufferSizeInFrames * PCM16_FRAME_SIZE_BYTES, MIN_READ_BUFFER_SIZE_BYTES)
                .alignToPcm16Frame()
        )
        try {
            while (scope.isActive && !terminationRequested.get()) {
                when (val bytesRead = recorder.read(
                    readBuffer,
                    0,
                    readBuffer.size,
                    AudioRecord.READ_NON_BLOCKING,
                )) {
                    0 -> delay(EMPTY_READ_RETRY_MILLIS)
                    in 1..Int.MAX_VALUE -> frameBuffer
                        .push(readBuffer.copyOf(bytesRead))
                        ?.let(::publishPcm)
                    else -> if (!terminationRequested.get()) {
                        error("Android microphone read failed: code=$bytesRead")
                    }
                }
                yield()
            }
        } catch (error: Throwable) {
            if (!terminationRequested.get()) captureFailure = error
        } finally {
            terminationRequested.set(true)
            stopRecorder()
            runCatching { recorder.release() }
            captureFailure?.let(audioChannel::close) ?: audioChannel.close()
            onFinished()
        }
    }

    private fun publishPcm(pcmLittleEndian: ByteArray) {
        rawPcm.write(pcmLittleEndian)
        audioChannel.trySend(pcmLittleEndian.toBigEndianPcm16())
    }

    private fun stopRecorder() {
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { recorder.stop() }
        }
    }

    private companion object {
        const val PCM16_FRAME_SIZE_BYTES = 2
        const val MIN_READ_BUFFER_SIZE_BYTES = 3_200
        const val EMPTY_READ_RETRY_MILLIS = 5L
    }
}

private fun ByteArray.toBigEndianPcm16(): ByteArray {
    check(size % 2 == 0) { "PCM16 byte array size must be even: $size" }
    val result = ByteArray(size)
    var index = 0
    while (index < size) {
        result[index] = this[index + 1]
        result[index + 1] = this[index]
        index += 2
    }
    return result
}

private fun Int.alignToPcm16Frame(): Int = this + (this and 1)
