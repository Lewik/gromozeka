package com.gromozeka.shared.audio

import kotlinx.coroutines.CoroutineScope

expect class AudioRecorder() {
    fun launchRecording(scope: CoroutineScope, config: AudioConfig = AudioConfig()): RecordingSession
}