package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.requireSupportsInputs
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiRequestResponseExecutionClient
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import com.gromozeka.domain.service.DirectAiRuntimeProvider
import kotlinx.coroutines.flow.Flow
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
class TargetedAiRuntimeProvider(
    private val directProvider: DirectAiRuntimeProvider,
    private val configurationProvider: AiConfigurationProvider,
    private val workerTargetResolver: ConversationRuntimeWorkerTargetResolver,
    private val remoteClients: List<AiRequestResponseExecutionClient>,
) : AiRuntimeProvider {
    override fun capabilities(selection: AiRuntimeSelection): AiRuntimeCapabilities =
        directProvider.capabilities(configurationProvider.resolveAiRuntime(selection))

    override fun getRuntime(
        selection: AiRuntimeSelection,
        workspaceRootPath: String?,
    ): AiRuntime {
        val runtime = configurationProvider.resolveAiRuntime(selection)
        return when (val target = runtime.connection.executionTarget) {
            AiExecutionTarget.Server -> directProvider.getRuntime(runtime, workspaceRootPath)
            is AiExecutionTarget.Worker -> {
                require(workspaceRootPath == null) {
                    "Worker-targeted AI runtime cannot receive a Server-local workspace path"
                }
                WorkerAiRuntime(
                    runtime = runtime,
                    workerId = ConversationRuntimeWorkerId(target.workerId),
                    capabilities = directProvider.capabilities(runtime),
                )
            }
        }
    }

    private inner class WorkerAiRuntime(
        private val runtime: com.gromozeka.domain.service.ResolvedAiRuntime,
        private val workerId: ConversationRuntimeWorkerId,
        override val capabilities: AiRuntimeCapabilities,
    ) : AiRuntime {
        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            runtime.modelSpec.requireSupportsInputs(request.messages)
            return remoteClient().call(
                target = workerTargetResolver.requireOnline(
                    workerId,
                    ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                ),
                runtime = runtime,
                workspaceRootPath = null,
                request = request,
            )
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> =
            throw UnsupportedOperationException(
                "Streaming AI runtime requires a Server-targeted connection; " +
                    "connection targets Worker ${workerId.value}"
            )
    }

    private fun remoteClient(): AiRequestResponseExecutionClient =
        remoteClients.singleOrNull()
            ?: error(
                if (remoteClients.isEmpty()) {
                    "Worker-targeted AI execution requires Worker Gateway transport"
                } else {
                    "Multiple AI request-response transports are configured"
                }
            )
}
