package com.gromozeka.shared.audio

import kotlinx.coroutines.CoroutineScope

actual class AudioRecorder {
    actual fun launchRecording(scope: CoroutineScope, config: AudioConfig): RecordingSession {
        return DesktopRecordingSession(config, scope).apply {
            start()
        }
    }
}