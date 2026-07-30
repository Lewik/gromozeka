package com.gromozeka.worker

import com.gromozeka.domain.service.WorkerToolCatalogPublisher
import com.gromozeka.domain.service.WorkerControlHandler
import com.gromozeka.domain.service.WorkerControlRequest
import com.gromozeka.domain.service.WorkerControlResult
import com.gromozeka.infrastructure.ai.config.mcp.McpConfigurationService
import org.springframework.stereotype.Service

@Service
class McpWorkerControlHandler(
    private val mcpConfigurationService: McpConfigurationService,
    private val workerToolCatalog: WorkerToolCatalog,
    private val toolCatalogPublisher: WorkerToolCatalogPublisher,
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
            is WorkerControlRequest.Command.SynchronizeMcpServers -> {
                mcpConfigurationService.synchronize(command.servers)
                updateAdvertisedTools()
                WorkerControlResult(
                    requestId = request.id,
                    status = WorkerControlResult.Status.SYNCHRONIZED,
                )
            }
        }

    private suspend fun updateAdvertisedTools() {
        toolCatalogPublisher.updateAdvertisedTools(workerToolCatalog.snapshot())
    }
}
