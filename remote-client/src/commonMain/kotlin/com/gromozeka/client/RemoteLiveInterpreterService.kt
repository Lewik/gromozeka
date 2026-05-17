package com.gromozeka.client

import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.remote.protocol.RemoteLiveAudioChunk
import com.gromozeka.remote.protocol.RemoteLiveTranscriptChunk
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.StartLiveInterpreterRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class RemoteLiveInterpreterService internal constructor(
    private val client: GromozekaWsClient,
) {
    suspend fun start(
        targetLanguage: String = "ru",
        sourceLanguageCode: String = "auto",
        sourceLanguageHint: String = "Hebrew, Russian, and English workplace conversation",
        translationRuntimeSelection: AiRuntimeSelection? = null,
    ): RemoteLiveInterpreterSession {
        val session = client.startLiveInterpreter(
            StartLiveInterpreterRequest(
                targetLanguage = targetLanguage,
                sourceLanguageCode = sourceLanguageCode,
                sourceLanguageHint = sourceLanguageHint,
                translationRuntimeSelection = translationRuntimeSelection,
            )
        )
        return RemoteLiveInterpreterSession(client, session.sessionId, session.channel)
    }
}

class RemoteLiveInterpreterSession internal constructor(
    private val client: GromozekaWsClient,
    val sessionId: String,
    private val channel: Channel<ServerPayload>,
) {
    val events: Flow<ServerPayload> = channel.receiveAsFlow()

    suspend fun sendAudioChunk(chunk: RemoteLiveAudioChunk) {
        client.sendLiveInterpreterAudioChunk(sessionId, chunk)
    }

    suspend fun sendTranscriptChunk(chunk: RemoteLiveTranscriptChunk) {
        client.sendLiveInterpreterTranscriptChunk(sessionId, chunk)
    }

    suspend fun stop() {
        runCatching { client.stopLiveInterpreter(sessionId) }
    }

    fun closeLocally() {
        client.closeLiveInterpreterSession(sessionId)
    }
}
