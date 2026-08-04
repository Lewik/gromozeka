package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpServerTransport
import com.gromozeka.domain.model.mcp.McpTransportValueRemovals
import com.gromozeka.domain.repository.McpServerRepository
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.McpServerMutationKind
import com.gromozeka.domain.service.WorkerControlClient
import com.gromozeka.domain.service.WorkerControlRequest
import com.gromozeka.domain.service.WorkerControlResult
import com.gromozeka.domain.service.WorkerToolExecutionClient
import com.gromozeka.domain.tool.TOOL_CONTEXT_TOOL_NAME
import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.springframework.stereotype.Service
import java.util.Base64

@Service
class McpServerManagementService(
    private val repository: McpServerRepository,
    private val workerRegistry: ConversationRuntimeWorkerRegistry,
    private val workerControlClient: WorkerControlClient,
    private val workerToolExecutionClient: WorkerToolExecutionClient,
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
        transportValueRemovals: McpTransportValueRemovals = McpTransportValueRemovals(),
    ): McpServer {
        val current = repository.find(config.id)
            ?: error("MCP server not found: ${config.id.value}")
        require(current.revision == expectedRevision) {
            "MCP server revision conflict: expected $expectedRevision, actual ${current.revision}"
        }
        require(current.config.workerId == config.workerId) {
            "Moving an MCP server between Workers requires explicit delete and create operations"
        }
        return apply(
            kind = McpServerMutationKind.UPDATE,
            config = config.mergeTransportValuesFrom(
                existing = current.config,
                removals = transportValueRemovals,
            ),
            expectedRevision = expectedRevision,
        )
    }

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
        try {
            check(repository.delete(serverId, expectedRevision)) {
                "MCP server $serverId changed concurrently; read it again before retrying"
            }
        } catch (error: Throwable) {
            runCatching { synchronizeWorker(current.config.workerId) }
                .onFailure(error::addSuppressed)
            throw error
        }
    }

    suspend fun testBrowserUse(serverId: McpServerId): BrowserUseProbeResult {
        val server = repository.find(serverId)
            ?: error("MCP server not found: ${serverId.value}")
        val screenshotTool = server.snapshot.tools.singleOrNull {
            it.remoteName == BROWSER_SCREENSHOT_TOOL_NAME
        } ?: error("MCP server does not provide $BROWSER_SCREENSHOT_TOOL_NAME: ${serverId.value}")
        val workerIdentity = onlineWorker(server.config.workerId)
        val executionTarget = ConversationRuntimeTaskTarget.Worker(server.config.workerId)
        val toolName = screenshotTool.toAiToolDefinition(serverId).name
        val toolCallId = Conversation.Message.ContentItem.ToolCall.Id(uuid7())
        val toolCall = Conversation.Message.ContentItem.ToolCall(
            id = toolCallId,
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = toolName,
                input = buildJsonObject {
                    put("type", "png")
                    putJsonObject(AI_TOOL_EXECUTION_TARGET_FIELD) {
                        put(AI_TOOL_EXECUTION_WORKER_ID_FIELD, server.config.workerId.value)
                    }
                },
            ),
        )
        val execution = workerToolExecutionClient.execute(
            target = workerIdentity,
            executionTarget = executionTarget,
            toolCalls = listOf(toolCall),
            toolContext = ToolExecutionContext(
                mapOf(
                    TOOL_CONTEXT_WORKER_ID to server.config.workerId.value,
                    TOOL_CONTEXT_TOOL_NAME to toolName,
                )
            ),
        )
        val result = execution.results.singleOrNull()
            ?: error("Browser Use probe returned ${execution.results.size} tool results")
        check(result.toolUseId == toolCallId && result.toolName == toolName) {
            "Browser Use probe response correlation mismatch"
        }
        check(!result.isError) {
            result.result.filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.Text>()
                .joinToString("\n") { it.content }
                .ifBlank { "Browser Use probe failed" }
        }
        val screenshot = result.result
            .filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.Base64Data>()
            .singleOrNull { it.mediaType.type == "image" }
            ?: error("Browser Use probe did not return exactly one image")
        return BrowserUseProbeResult(
            screenshot = Base64.getDecoder().decode(screenshot.data),
            mediaType = screenshot.mediaType.value,
            fileName = screenshot.fileName,
        )
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
        val server = try {
            val observed = checkNotNull(result.mcpServer)
            val current = repository.find(config.id)
            when (kind) {
                McpServerMutationKind.CREATE -> require(current == null) {
                    "MCP server already exists: ${config.id.value}"
                }
                McpServerMutationKind.UPDATE,
                McpServerMutationKind.REFRESH -> requireNotNull(current) {
                    "MCP server not found: ${config.id.value}"
                }
            }
            val now = Clock.System.now()
            val candidate = observed.copy(
                revision = (current?.revision ?: 0) + 1,
                refreshAvailable = false,
                createdAt = current?.createdAt ?: now,
                updatedAt = now,
            )
            val persisted = when (kind) {
                McpServerMutationKind.CREATE -> repository.create(candidate)
                McpServerMutationKind.UPDATE,
                McpServerMutationKind.REFRESH ->
                    repository.replace(candidate, checkNotNull(expectedRevision))
            }
            check(persisted) {
                "MCP server ${config.id.value} changed concurrently; read it again before retrying"
            }
            candidate
        } catch (error: Throwable) {
            runCatching { synchronizeWorker(config.workerId) }
                .onFailure(error::addSuppressed)
            throw error
        }
        capabilityCatalogService.scheduleSource(
            AiToolCapabilitySource(
                id = server.config.id.sourceId,
                definitions = server.snapshot.tools.map { it.toAiToolDefinition(server.config.id) },
                instructions = server.snapshot.instructions,
            )
        )
        return server
    }

    private suspend fun synchronizeWorker(workerId: ConversationRuntimeWorkerId) {
        val result = execute(
            workerId = workerId,
            command = WorkerControlRequest.Command.SynchronizeMcpServers(
                repository.listByWorker(workerId)
            ),
        )
        check(result.status == WorkerControlResult.Status.SYNCHRONIZED) {
            result.failureMessage()
        }
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

data class BrowserUseProbeResult(
    val screenshot: ByteArray,
    val mediaType: String,
    val fileName: String?,
)

private fun McpServerConfig.mergeTransportValuesFrom(
    existing: McpServerConfig,
    removals: McpTransportValueRemovals,
): McpServerConfig {
    val requestedTransport = transport
    val existingTransport = existing.transport
    return copy(
        transport = when {
            requestedTransport is McpServerTransport.Stdio &&
                existingTransport is McpServerTransport.Stdio -> {
                require(removals.httpHeaders.isEmpty()) {
                    "HTTP header removals require a Streamable HTTP MCP transport"
                }
                require(
                    requestedTransport.environment.keys.intersect(
                        removals.environmentVariables
                    ).isEmpty()
                ) {
                    "MCP environment variables cannot be replaced and removed in the same update"
                }
                requestedTransport.copy(
                    environment = (existingTransport.environment + requestedTransport.environment) -
                        removals.environmentVariables
                )
            }
            requestedTransport is McpServerTransport.StreamableHttp &&
                existingTransport is McpServerTransport.StreamableHttp -> {
                require(removals.environmentVariables.isEmpty()) {
                    "Environment variable removals require a stdio MCP transport"
                }
                require(
                    requestedTransport.headers.keys.none { replacement ->
                        removals.httpHeaders.any { removal ->
                            replacement.equals(removal, ignoreCase = true)
                        }
                    }
                ) {
                    "MCP HTTP headers cannot be replaced and removed in the same update"
                }
                requestedTransport.copy(
                    headers = existingTransport.headers.mergeHttpHeaders(
                        replacements = requestedTransport.headers,
                        removals = removals.httpHeaders,
                    )
                )
            }
            else -> {
                require(
                    removals.environmentVariables.isEmpty() &&
                        removals.httpHeaders.isEmpty()
                ) {
                    "Transport value removals cannot be applied while changing MCP transport type"
                }
                requestedTransport
            }
        }
    )
}

private const val BROWSER_SCREENSHOT_TOOL_NAME = "browser_take_screenshot"

private fun Map<String, String>.mergeHttpHeaders(
    replacements: Map<String, String>,
    removals: Set<String>,
): Map<String, String> =
    toMutableMap().apply {
        replacements.forEach { (name, value) ->
            keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(::remove)
            put(name, value)
        }
        removals.forEach { name ->
            keys.filter { it.equals(name, ignoreCase = true) }.forEach(::remove)
        }
    }
