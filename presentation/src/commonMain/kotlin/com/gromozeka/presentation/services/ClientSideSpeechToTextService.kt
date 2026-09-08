package com.gromozeka.presentation.services

import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedText

import com.gromozeka.remote.protocol.RemoteAudioRecording
import com.gromozeka.remote.protocol.RemoteLiveAudioChunk

interface ClientSideSpeechToTextService {
    fun isEnabled(): Boolean

    fun isAvailable(): Boolean = isEnabled()

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
        throw LocalizedTextException(localizedText("voice.clientSttUnavailable"))

    override suspend fun transcribe(
        chunk: RemoteLiveAudioChunk,
        language: String,
        prompt: String?,
    ): String =
        throw LocalizedTextException(localizedText("voice.clientSttUnavailable"))
}
