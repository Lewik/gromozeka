package com.gromozeka.client

import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.LiveTranscriptionException
import com.gromozeka.remote.protocol.RemotePcmAudioChunk
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.StartLiveInterpreterRequest
import com.gromozeka.remote.protocol.TranslateLiveTextRequest
import com.gromozeka.remote.protocol.LiveTextTranslatedResponse
import kotlinx.coroutines.withTimeout
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow

interface LiveInterpreterClient {
    suspend fun start(languages: List<String>, selection: AiRuntimeSelection, manualTurnControl: Boolean = false): LiveInterpreterRemoteSession

    suspend fun start(
        languages: List<String>,
        selection: AiRuntimeSelection,
        manualTurnControl: Boolean,
        nativeTranslationLanguage: String?,
    ): LiveInterpreterRemoteSession {
        require(nativeTranslationLanguage == null) { "This Live client does not support native translation" }
        return start(languages, selection, manualTurnControl)
    }

    suspend fun translate(request: TranslateLiveTextRequest): LiveTextTranslatedResponse
}

interface LiveInterpreterRemoteSession {
    val sessionId: String
    val events: Flow<ServerPayload>
    suspend fun sendAudioChunk(chunk: RemotePcmAudioChunk)
    suspend fun commit()
    suspend fun stop()
    suspend fun close()
}

class RemoteLiveInterpreterService internal constructor(private val client: GromozekaWsClient) : LiveInterpreterClient {
    override suspend fun translate(request: TranslateLiveTextRequest): LiveTextTranslatedResponse =
        withTimeout(200_000) { client.requestTyped<TranslateLiveTextRequest, LiveTextTranslatedResponse>(request) }

    override suspend fun start(
        languages: List<String>,
        selection: AiRuntimeSelection,
        manualTurnControl: Boolean,
    ): LiveInterpreterRemoteSession = start(languages, selection, manualTurnControl, null)

    override suspend fun start(
        languages: List<String>,
        selection: AiRuntimeSelection,
        manualTurnControl: Boolean,
        nativeTranslationLanguage: String?,
    ): LiveInterpreterRemoteSession {
        val session = try {
            client.startLiveInterpreter(StartLiveInterpreterRequest(
                sessionId = uuid7(),
                languages = languages,
                transcriptionRuntimeSelection = selection,
                manualTurnControl = manualTurnControl,
                nativeTranslationLanguage = nativeTranslationLanguage,
            ))
        } catch (error: RemoteConnectionLostException) {
            throw LiveTranscriptionException("Live connection to Gromozeka was lost", retryable = true, cause = error)
        }
        return object : LiveInterpreterRemoteSession {
            private var stopped = false
            override val sessionId: String = session.sessionId
            override val events: Flow<ServerPayload> = session.channel.receiveAsFlow().catch { error ->
                if (error is RemoteConnectionLostException) {
                    throw LiveTranscriptionException("Live connection to Gromozeka was lost", retryable = true, cause = error)
                }
                throw error
            }

            override suspend fun sendAudioChunk(chunk: RemotePcmAudioChunk) =
                client.sendLiveInterpreterAudioChunk(sessionId, chunk)

            override suspend fun commit() {
                check(!stopped) { "Live remote input is already stopping" }
                client.commitLiveInterpreter(sessionId)
            }

            override suspend fun stop() {
                if (stopped) return
                stopped = true
                client.stopLiveInterpreter(sessionId)
            }

            override suspend fun close() {
                try {
                    stop()
                } finally {
                    client.closeLiveInterpreterSession(sessionId)
                }
            }
        }
    }
}
