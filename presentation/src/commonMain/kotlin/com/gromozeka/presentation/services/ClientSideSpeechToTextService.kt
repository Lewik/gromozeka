package com.gromozeka.presentation.services

import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedText

import com.gromozeka.remote.protocol.RemoteAudioRecording

interface ClientSideSpeechToTextService {
    fun isEnabled(): Boolean

    fun isAvailable(): Boolean = isEnabled()

    suspend fun transcribe(recording: RemoteAudioRecording): String

}

object NoOpClientSideSpeechToTextService : ClientSideSpeechToTextService {
    override fun isEnabled(): Boolean = false

    override suspend fun transcribe(recording: RemoteAudioRecording): String =
        throw LocalizedTextException(localizedText("voice.clientSttUnavailable"))

}
