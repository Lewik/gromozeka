package com.gromozeka.presentation.services

import com.gromozeka.remote.protocol.RemoteAudioRecording
import com.gromozeka.remote.protocol.RemoteLiveAudioChunk

interface ClientSideSpeechToTextService {
    fun isEnabled(): Boolean

    suspend fun transcribe(recording: RemoteAudioRecording): String

    suspend fun transcribe(
        chunk: RemoteLiveAudioChunk,
        language: String,
        prompt: String?,
    ): String
}

object NoOpClientSideSpeechToTextService : ClientSideSpeechToTextService {
    override fun isEnabled(): Boolean = false

    override suspend fun transcribe(recording: RemoteAudioRecording): String =
        error("Client-side speech-to-text is not available")

    override suspend fun transcribe(
        chunk: RemoteLiveAudioChunk,
        language: String,
        prompt: String?,
    ): String =
        error("Client-side speech-to-text is not available")
}
