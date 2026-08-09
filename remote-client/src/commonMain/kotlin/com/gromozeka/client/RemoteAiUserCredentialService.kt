package com.gromozeka.client

import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiUserCredentialStatus
import com.gromozeka.domain.service.CurrentUserAiCredentialService
import com.gromozeka.remote.protocol.AiUserCredentialStatusResponse
import com.gromozeka.remote.protocol.ConfigureAiUserCredentialRequest
import com.gromozeka.remote.protocol.GetAiUserCredentialStatusRequest
import com.gromozeka.remote.protocol.RemoveAiUserCredentialRequest

internal class RemoteAiUserCredentialService(
    private val client: GromozekaWsClient,
) : CurrentUserAiCredentialService {
    override suspend fun status(connectionId: AiConnection.Id): AiUserCredentialStatus =
        client.requestTyped<GetAiUserCredentialStatusRequest, AiUserCredentialStatusResponse>(
            GetAiUserCredentialStatusRequest(connectionId)
        ).status

    override suspend fun configure(
        connectionId: AiConnection.Id,
        secret: String,
    ): AiUserCredentialStatus =
        client.requestTyped<ConfigureAiUserCredentialRequest, AiUserCredentialStatusResponse>(
            ConfigureAiUserCredentialRequest(connectionId, secret)
        ).status

    override suspend fun remove(connectionId: AiConnection.Id): AiUserCredentialStatus =
        client.requestTyped<RemoveAiUserCredentialRequest, AiUserCredentialStatusResponse>(
            RemoveAiUserCredentialRequest(connectionId)
        ).status
}
