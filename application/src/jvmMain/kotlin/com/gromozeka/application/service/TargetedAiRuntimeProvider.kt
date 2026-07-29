package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
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
        directProvider.capabilities(selection)

    override fun getRuntime(
        selection: AiRuntimeSelection,
        workspaceRootPath: String?,
    ): AiRuntime {
        val connection = configurationProvider.resolveAiRuntime(selection).connection
        return when (val target = connection.executionTarget) {
            AiExecutionTarget.Server -> directProvider.getRuntime(selection, workspaceRootPath)
            is AiExecutionTarget.Worker -> {
                require(workspaceRootPath == null) {
                    "Worker-targeted AI runtime cannot receive a Server-local workspace path"
                }
                WorkerAiRuntime(
                    selection = selection,
                    workerId = ConversationRuntimeWorkerId(target.workerId),
                    capabilities = directProvider.capabilities(selection),
                )
            }
        }
    }

    private inner class WorkerAiRuntime(
        private val selection: AiRuntimeSelection,
        private val workerId: ConversationRuntimeWorkerId,
        override val capabilities: AiRuntimeCapabilities,
    ) : AiRuntime {
        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse =
            remoteClient().call(
                target = workerTargetResolver.requireOnline(
                    workerId,
                    ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                ),
                selection = selection,
                workspaceRootPath = null,
                request = request,
            )

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
                    "Worker-targeted AI execution requires Rabbit runtime transport"
                } else {
                    "Multiple AI request-response transports are configured"
                }
            )
}
