package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaRequest
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaSnapshot
import com.gromozeka.domain.service.AiRequestResponseExecutionClient
import com.gromozeka.domain.service.AiSubscriptionQuotaProvider
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import com.gromozeka.domain.service.DirectAiSubscriptionQuotaProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class TargetedAiSubscriptionQuotaProvider(
    private val directProviders: List<DirectAiSubscriptionQuotaProvider>,
    private val workerTargetResolver: ConversationRuntimeWorkerTargetResolver,
    private val remoteClients: List<AiRequestResponseExecutionClient>,
) : AiSubscriptionQuotaProvider {
    override suspend fun read(request: AiSubscriptionQuotaRequest): AiSubscriptionQuotaSnapshot =
        when (val target = request.connection.executionTarget) {
            AiExecutionTarget.Server -> directProvider(request).read(request)
            is AiExecutionTarget.Worker -> remoteClient().readSubscriptionQuota(
                target = workerTargetResolver.requireRegistered(
                    ConversationRuntimeWorkerId(target.workerId),
                    ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                ),
                request = request,
            )
        }

    private fun directProvider(request: AiSubscriptionQuotaRequest): DirectAiSubscriptionQuotaProvider {
        val matches = directProviders.filter { it.supports(request) }
        return matches.singleOrNull()
            ?: error(
                if (matches.isEmpty()) {
                    "No subscription quota provider registered for ${request.connection.kind}"
                } else {
                    "Multiple subscription quota providers registered for ${request.connection.kind}"
                }
            )
    }

    private fun remoteClient(): AiRequestResponseExecutionClient =
        remoteClients.singleOrNull()
            ?: error(
                if (remoteClients.isEmpty()) {
                    "Worker-targeted subscription quota requires Worker Gateway transport"
                } else {
                    "Multiple AI request-response transports are configured"
                }
            )
}
