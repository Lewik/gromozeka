package com.gromozeka.shared.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

actual class AudioRecorder {
    actual fun launchRecording(scope: CoroutineScope, config: AudioConfig): RecordingSession =
        UnsupportedRecordingSession
}

private object UnsupportedRecordingSession : RecordingSession {
    override val audioChunks: Flow<ByteArray> = emptyFlow()

    override suspend fun stop(): ByteArray =
        throw UnsupportedOperationException("Audio recording is not available on wasmJs")

    override fun cancel() = Unit
}
