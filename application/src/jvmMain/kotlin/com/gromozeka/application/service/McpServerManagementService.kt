package com.gromozeka.application.service

import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.repository.McpServerRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.McpServerMutationKind
import com.gromozeka.domain.service.WorkerControlClient
import com.gromozeka.domain.service.WorkerControlRequest
import com.gromozeka.domain.service.WorkerControlResult
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service

@Service
class McpServerManagementService(
    private val repository: McpServerRepository,
    private val workerRegistry: ConversationRuntimeWorkerRegistry,
    private val workerControlClient: WorkerControlClient,
    private val capabilityCatalogService: AiToolCapabilityCatalogService,
) {
    suspend fun list(): List<McpServer> = repository.list()

    suspend fun get(id: McpServerId): McpServer? = repository.find(id)

    suspend fun create(config: McpServerConfig): McpServer =
        apply(
            kind = McpServerMutationKind.CREATE,
            config = config,
            expectedRevision = null,
        )

    suspend fun update(
        config: McpServerConfig,
        expectedRevision: Long,
    ): McpServer =
        apply(
            kind = McpServerMutationKind.UPDATE,
            config = config,
            expectedRevision = expectedRevision,
        )

    suspend fun refresh(
        serverId: McpServerId,
        expectedRevision: Long,
    ): McpServer {
        val current = repository.find(serverId)
            ?: error("MCP server not found: ${serverId.value}")
        return apply(
            kind = McpServerMutationKind.REFRESH,
            config = current.config,
            expectedRevision = expectedRevision,
        )
    }

    suspend fun delete(
        serverId: McpServerId,
        expectedRevision: Long,
    ) {
        val current = repository.find(serverId)
            ?: error("MCP server not found: ${serverId.value}")
        val result = execute(
            workerId = current.config.workerId,
            command = WorkerControlRequest.Command.DeleteMcpServer(
                serverId = serverId,
                expectedRevision = expectedRevision,
            ),
        )
        check(result.status == WorkerControlResult.Status.DELETED) {
            result.failureMessage()
        }
    }

    private suspend fun apply(
        kind: McpServerMutationKind,
        config: McpServerConfig,
        expectedRevision: Long?,
    ): McpServer {
        val result = execute(
            workerId = config.workerId,
            command = WorkerControlRequest.Command.ApplyMcpServer(
                kind = kind,
                config = config,
                expectedRevision = expectedRevision,
            ),
        )
        check(result.status == WorkerControlResult.Status.SUCCEEDED) {
            result.failureMessage()
        }
        val server = checkNotNull(result.mcpServer)
        capabilityCatalogService.scheduleSource(
            AiToolCapabilitySource(
                id = server.config.id.sourceId,
                definitions = server.snapshot.tools.map { it.toAiToolDefinition(server.config.id) },
                instructions = server.snapshot.instructions,
            )
        )
        return server
    }

    private suspend fun execute(
        workerId: ConversationRuntimeWorkerId,
        command: WorkerControlRequest.Command,
    ): WorkerControlResult {
        val identity = onlineWorker(workerId)
        val request = WorkerControlRequest(
            id = WorkerControlRequest.Id(uuid7()),
            target = identity,
            command = command,
        )
        val result = workerControlClient.execute(request)
        check(result.requestId == request.id) {
            "Worker control response correlation mismatch"
        }
        return result
    }

    private suspend fun onlineWorker(workerId: ConversationRuntimeWorkerId): ConversationRuntimeWorkerIdentity {
        val registration = workerRegistry.find(workerId)
            ?: error("Worker not found: ${workerId.value}")
        val staleBefore = Clock.System.now() - ConversationRuntimeTiming.workerRegistrationStaleAfter
        require(registration.isOnline(staleBefore)) {
            "Worker is offline: ${workerId.value}"
        }
        return registration.identity
    }

    private fun WorkerControlResult.failureMessage(): String =
        if (status == WorkerControlResult.Status.FAILED) {
            "Worker control failed [$errorCode]: $errorMessage"
        } else {
            "Unexpected worker control status: $status"
        }
}
