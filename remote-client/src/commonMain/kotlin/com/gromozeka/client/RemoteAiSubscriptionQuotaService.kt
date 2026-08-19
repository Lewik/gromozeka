package com.gromozeka.client

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaObservation
import com.gromozeka.domain.service.AiSubscriptionQuotaService
import com.gromozeka.remote.protocol.AiSubscriptionQuotaResponse
import com.gromozeka.remote.protocol.GetAiSubscriptionQuotaRequest

internal class RemoteAiSubscriptionQuotaService(
    private val client: GromozekaWsClient,
) : AiSubscriptionQuotaService {
    override suspend fun read(
        modelConfigurationId: AiModelConfiguration.Id,
        forceRefresh: Boolean,
    ): AiSubscriptionQuotaObservation =
        client.requestTyped<GetAiSubscriptionQuotaRequest, AiSubscriptionQuotaResponse>(
            GetAiSubscriptionQuotaRequest(modelConfigurationId, forceRefresh)
        ).observation
}
