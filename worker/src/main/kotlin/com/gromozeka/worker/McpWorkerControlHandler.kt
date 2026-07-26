package com.gromozeka.worker

import com.gromozeka.application.service.ConversationRuntimeWorker
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.WorkerControlHandler
import com.gromozeka.domain.service.WorkerControlRequest
import com.gromozeka.domain.service.WorkerControlResult
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.supportedBy
import com.gromozeka.infrastructure.ai.config.mcp.McpConfigurationService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service

@Configuration
class WorkerControlConfiguration {
    @Bean
    fun conversationRuntimeWorkerIdentity(
        runtimeWorker: ConversationRuntimeWorker,
    ) = runtimeWorker.identity
}

@Service
class McpWorkerControlHandler(
    private val mcpConfigurationService: McpConfigurationService,
    private val aiToolProvider: AiToolProvider,
    private val runtimeWorker: ConversationRuntimeWorker,
) : WorkerControlHandler {
    override suspend fun handle(request: WorkerControlRequest): WorkerControlResult =
        when (val command = request.command) {
            is WorkerControlRequest.Command.ApplyMcpServer -> {
                val server = mcpConfigurationService.apply(
                    kind = command.kind,
                    config = command.config,
                    expectedRevision = command.expectedRevision,
                )
                updateAdvertisedTools()
                WorkerControlResult(
                    requestId = request.id,
                    status = WorkerControlResult.Status.SUCCEEDED,
                    mcpServer = server,
                )
            }
            is WorkerControlRequest.Command.DeleteMcpServer -> {
                mcpConfigurationService.delete(
                    serverId = command.serverId,
                    expectedRevision = command.expectedRevision,
                )
                updateAdvertisedTools()
                WorkerControlResult(
                    requestId = request.id,
                    status = WorkerControlResult.Status.DELETED,
                )
            }
        }

    private suspend fun updateAdvertisedTools() {
        val descriptors = aiToolProvider.getTools()
            .supportedBy(runtimeWorker.capabilities)
            .map { AiToolDescriptor(it.definition, it.metadata) }
            .sortedBy { it.definition.name }
        runtimeWorker.updateAdvertisedTools(descriptors)
    }
}
