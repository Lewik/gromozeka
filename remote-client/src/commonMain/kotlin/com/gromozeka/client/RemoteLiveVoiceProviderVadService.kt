package com.gromozeka.client

import com.gromozeka.domain.model.SpeechAvailabilityFailure
import com.gromozeka.domain.model.SpeechAvailabilityException

import com.gromozeka.remote.protocol.RemotePcmAudioChunk
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.GetLiveVoiceProviderVadAvailabilityRequest
import com.gromozeka.remote.protocol.LiveVoiceProviderVadAvailabilityResponse
import com.gromozeka.remote.protocol.StartLiveVoiceProviderVadRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

interface LiveVoiceProviderVadService {
    suspend fun unavailableReason(): SpeechAvailabilityFailure?

    suspend fun start(
        languageCode: String?,
        prompt: String?,
    ): LiveVoiceProviderVadSession
}

object NoOpLiveVoiceProviderVadService : LiveVoiceProviderVadService {
    private val unavailableReason = SpeechAvailabilityFailure(SpeechAvailabilityFailure.Code.PROVIDER_VAD_UNAVAILABLE)

    override suspend fun unavailableReason(): SpeechAvailabilityFailure = unavailableReason

    override suspend fun start(
        languageCode: String?,
        prompt: String?,
    ): LiveVoiceProviderVadSession =
        throw SpeechAvailabilityException(unavailableReason)
}

class RemoteLiveVoiceProviderVadService internal constructor(
    private val client: GromozekaWsClient,
) : LiveVoiceProviderVadService {
    override suspend fun unavailableReason(): SpeechAvailabilityFailure? =
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
        return RemoteLiveVoiceProviderVadSession(client, session.sessionId, session.channel)
    }
}

interface LiveVoiceProviderVadSession {
    val sessionId: String
    val events: Flow<ServerPayload>
    suspend fun sendAudioChunk(chunk: RemotePcmAudioChunk)
    suspend fun stop()
    fun closeLocally()
}

private class RemoteLiveVoiceProviderVadSession(
    private val client: GromozekaWsClient,
    override val sessionId: String,
    private val channel: Channel<ServerPayload>,
) : LiveVoiceProviderVadSession {
    override val events: Flow<ServerPayload> = channel.receiveAsFlow()

    override suspend fun sendAudioChunk(chunk: RemotePcmAudioChunk) {
        client.sendLiveVoiceProviderVadAudioChunk(sessionId, chunk)
    }

    override suspend fun stop() {
        runCatching { client.stopLiveVoiceProviderVad(sessionId) }
    }

    override fun closeLocally() {
        client.closeLiveVoiceProviderVadSession(sessionId)
    }
}
