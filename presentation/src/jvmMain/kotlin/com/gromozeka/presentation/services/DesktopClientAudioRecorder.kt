package com.gromozeka.presentation.services

import com.gromozeka.shared.audio.AudioConfig
import com.gromozeka.shared.audio.AudioOutputFormat
import com.gromozeka.shared.audio.AudioRecorder
import com.gromozeka.shared.audio.RecordingSession
import kotlinx.coroutines.CoroutineScope

class DesktopClientAudioRecorder : ClientAudioRecorder {
    private val audioConfig = AudioConfig(
        sampleRate = 16000,
        channels = 1,
        bitDepth = 16,
        outputFormat = AudioOutputFormat.WAV
    )

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
    override suspend fun stop(): ClientRecordedAudio {
        val bytes = session.stop()
        return ClientRecordedAudio(
            data = bytes,
            mediaType = "audio/wav",
            fileExtension = "wav",
            byteSize = bytes.size,
            sampleRate = audioConfig.sampleRate,
            channels = audioConfig.channels,
            bitDepth = audioConfig.bitDepth
        )
    }

    override fun cancel() {
        session.cancel()
    }
}
