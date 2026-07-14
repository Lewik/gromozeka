package com.gromozeka.presentation.services

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.shared.audio.AudioConfig
import com.gromozeka.shared.audio.AudioOutputFormat
import com.gromozeka.shared.audio.AudioRecorder
import com.gromozeka.shared.audio.RecordingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

class DesktopClientAudioRecorder : ClientAudioRecorder {
    private val audioConfig = AudioConfig(
        sampleRate = 16000,
        channels = 1,
        bitDepth = 16,
        outputFormat = AudioOutputFormat.WAV
    )

    override val supportsStreamingAudioChunks: Boolean = true

    override suspend fun start(scope: CoroutineScope): ClientAudioRecordingSession =
        DesktopClientAudioRecordingSession(
            session = AudioRecorder().launchRecording(scope, audioConfig),
            audioConfig = audioConfig
        )
}

private class DesktopClientAudioRecordingSession(
    private val session: RecordingSession,
    private val audioConfig: AudioConfig,
) : ClientAudioRecordingSession {
    override val audioChunks: Flow<ByteArray> = session.audioChunks

    override suspend fun stop(): ClientRecordedAudio {
        val bytes = session.stop()
        return ClientRecordedAudio(
            data = bytes,
            format = SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ,
        )
    }

    override fun cancel() {
        session.cancel()
    }
}
