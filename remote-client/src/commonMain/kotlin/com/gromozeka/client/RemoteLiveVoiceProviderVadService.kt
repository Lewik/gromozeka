package com.gromozeka.client

import com.gromozeka.remote.protocol.RemotePcmAudioChunk
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.GetLiveVoiceProviderVadAvailabilityRequest
import com.gromozeka.remote.protocol.LiveVoiceProviderVadAvailabilityResponse
import com.gromozeka.remote.protocol.StartLiveVoiceProviderVadRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

interface LiveVoiceProviderVadService {
    suspend fun unavailableReason(): String?

    suspend fun start(
        languageCode: String?,
        prompt: String?,
    ): LiveVoiceProviderVadSession
}

object NoOpLiveVoiceProviderVadService : LiveVoiceProviderVadService {
    private const val unavailableReason = "Provider VAD недоступен на этом клиенте"

    override suspend fun unavailableReason(): String = unavailableReason

    override suspend fun start(
        languageCode: String?,
        prompt: String?,
    ): LiveVoiceProviderVadSession =
        error(unavailableReason)
}

class RemoteLiveVoiceProviderVadService internal constructor(
    private val client: GromozekaWsClient,
) : LiveVoiceProviderVadService {
    override suspend fun unavailableReason(): String? =
        client.requestTyped<GetLiveVoiceProviderVadAvailabilityRequest, LiveVoiceProviderVadAvailabilityResponse>(
            GetLiveVoiceProviderVadAvailabilityRequest
        ).unavailableReason

    override suspend fun start(
        languageCode: String?,
        prompt: String?,
    ): LiveVoiceProviderVadSession {
        val session = client.startLiveVoiceProviderVad(
            StartLiveVoiceProviderVadRequest(
                languageCode = languageCode,
                prompt = prompt,
            )
        )
        return LiveVoiceProviderVadSession(client, session.sessionId, session.channel)
    }
}

class LiveVoiceProviderVadSession internal constructor(
    private val client: GromozekaWsClient,
    val sessionId: String,
    private val channel: Channel<ServerPayload>,
) {
    val events: Flow<ServerPayload> = channel.receiveAsFlow()

    suspend fun sendAudioChunk(chunk: RemotePcmAudioChunk) {
        client.sendLiveVoiceProviderVadAudioChunk(sessionId, chunk)
    }

    suspend fun stop() {
        runCatching { client.stopLiveVoiceProviderVad(sessionId) }
    }

    fun closeLocally() {
        client.closeLiveVoiceProviderVadSession(sessionId)
    }
}
