package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiEmbeddingProvider
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiRequestResponseExecutionClient
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import com.gromozeka.domain.service.DirectAiEmbeddingProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

@Service
@Primary
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class TargetedAiEmbeddingProvider(
    private val directProvider: DirectAiEmbeddingProvider,
    private val configurationProvider: AiConfigurationProvider,
    private val workerTargetResolver: ConversationRuntimeWorkerTargetResolver,
    private val remoteClients: List<AiRequestResponseExecutionClient>,
) : AiEmbeddingProvider {
    override suspend fun embed(request: AiEmbeddingRequest): AiEmbeddingResponse {
        val runtime = configurationProvider.resolveAiRuntime(request.selection)
        val modelSpec = configurationProvider.catalog.modelSpecFor(runtime.modelConfiguration)
            ?: error("AI embedding model spec not found: ${runtime.modelConfiguration.providerModelId}")
        return when (val target = runtime.connection.executionTarget) {
            AiExecutionTarget.Server -> directProvider.embed(runtime, modelSpec, request)
            is AiExecutionTarget.Worker -> remoteClient().embed(
                target = workerTargetResolver.requireOnline(
                    ConversationRuntimeWorkerId(target.workerId),
                    ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                ),
                runtime = runtime,
                modelSpec = modelSpec,
                request = request,
            )
        }
    }

    private fun remoteClient(): AiRequestResponseExecutionClient =
        remoteClients.singleOrNull()
            ?: error(
                if (remoteClients.isEmpty()) {
                    "Worker-targeted embeddings require Worker Gateway transport"
                } else {
                    "Multiple AI request-response transports are configured"
                }
            )
}
