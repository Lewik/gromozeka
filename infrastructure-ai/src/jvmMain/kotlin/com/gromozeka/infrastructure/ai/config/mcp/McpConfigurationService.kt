package com.gromozeka.infrastructure.ai.config.mcp

import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpServerSnapshot
import com.gromozeka.domain.model.mcp.McpToolSnapshot
import com.gromozeka.domain.repository.McpServerRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.McpServerMutationKind
import com.gromozeka.domain.tool.AiToolCallback
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service

@Service
@DependsOn("database")
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class McpConfigurationService(
    @Value("\${gromozeka.runtime.worker.id}") workerId: String,
    private val repository: McpServerRepository,
    private val clientFactory: McpClientFactory,
    @Qualifier("mcpCoroutineScope") private val coroutineScope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val workerId = ConversationRuntimeWorkerId(workerId)
    private val mutationMutex = Mutex()

    @Volatile
    private var activeServers: Map<McpServerId, ActiveMcpServer> = emptyMap()

    @PostConstruct
    fun initialize() {
        runBlocking {
            repository.listByWorker(workerId).forEach { server ->
                runCatching { activatePersisted(server) }
                    .onFailure { error ->
                        log.error(error) {
                            "Failed to activate persisted MCP server ${server.config.id.value}: ${error.message}"
                        }
                    }
            }
        }
    }

    fun getTools(): List<AiToolCallback> =
        activeServers.values
            .sortedBy { it.server.config.id.value }
            .flatMap(ActiveMcpServer::tools)

    suspend fun apply(
        kind: McpServerMutationKind,
        config: McpServerConfig,
        expectedRevision: Long?,
    ): McpServer = mutationMutex.withLock {
        require(config.workerId == workerId) {
            "MCP server ${config.id.value} targets worker ${config.workerId.value}, not ${workerId.value}"
        }
        val current = repository.find(config.id)
        validateMutation(kind, config, expectedRevision, current)

        val candidate = connect(config)
        try {
            val snapshot = snapshot(config, candidate, candidate.listAllTools())
            val now = Clock.System.now()
            val replacement = McpServer(
                config = config,
                snapshot = snapshot,
                revision = (current?.revision ?: 0) + 1,
                refreshAvailable = false,
                createdAt = current?.createdAt ?: now,
                updatedAt = now,
            )
            val activeCandidate = prepareActive(replacement, candidate)
            val persisted = if (current == null) {
                repository.create(replacement)
            } else {
                repository.replace(replacement, checkNotNull(expectedRevision))
            }
            check(persisted) {
                "MCP server ${config.id.value} changed concurrently; read it again before retrying"
            }
            activate(activeCandidate)
            replacement
        } catch (error: Throwable) {
            candidate.forceClose()
            throw error
        }
    }

    suspend fun delete(
        serverId: McpServerId,
        expectedRevision: Long,
    ) = mutationMutex.withLock {
        val current = repository.find(serverId)
            ?: error("MCP server not found: ${serverId.value}")
        require(current.config.workerId == workerId) {
            "MCP server ${serverId.value} belongs to worker ${current.config.workerId.value}"
        }
        require(repository.delete(serverId, expectedRevision)) {
            "MCP server ${serverId.value} changed concurrently; read it again before retrying"
        }
        val removed = activeServers[serverId]
        activeServers = activeServers - serverId
        removed?.let {
            runCatching(it.client::close)
                .onFailure { error ->
                    log.warn(error) { "Failed to close deleted MCP server client ${serverId.value}" }
                }
        }
    }

    @PreDestroy
    fun shutdown() {
        activeServers.values.forEach { server ->
            runCatching(server.client::forceClose)
                .onFailure { error ->
                    log.warn(error) { "Failed to close MCP server ${server.server.config.id.value}" }
                }
        }
        activeServers = emptyMap()
    }

    private suspend fun activatePersisted(server: McpServer) {
        val client = connect(server.config)
        try {
            val observedTools = client.listAllTools()
            val observedSnapshot = runCatching {
                snapshot(server.config, client, observedTools)
            }.getOrElse { error ->
                repository.markRefreshAvailable(server.config.id, server.revision)
                log.warn(error) {
                    "MCP server ${server.config.id.value} no longer matches its accepted snapshot; " +
                        "explicit refresh is required"
                }
                activate(prepareActive(server, client))
                return
            }
            if (observedSnapshot.fingerprint != server.snapshot.fingerprint) {
                repository.markRefreshAvailable(server.config.id, server.revision)
                log.warn {
                    "MCP server ${server.config.id.value} tools changed; explicit refresh is required"
                }
            }
            activate(prepareActive(server, client))
        } catch (error: Throwable) {
            client.forceClose()
            throw error
        }
    }

    private suspend fun connect(config: McpServerConfig): McpConnectedClient =
        clientFactory.connect(config).also { client ->
            check(client.serverInfo.name.isNotBlank()) {
                "MCP server ${config.id.value} returned a blank implementation name"
            }
        }

    private suspend fun snapshot(
        config: McpServerConfig,
        client: McpConnectedClient,
        availableTools: List<Tool>,
    ): McpServerSnapshot {
        val selectedTools = selectTools(config, availableTools)
        val tools = selectedTools.map { tool ->
            McpToolSnapshot(
                remoteName = tool.name,
                description = tool.description.orEmpty(),
                inputSchema = McpJson.encodeToString(ToolSchema.serializer(), tool.inputSchema),
            )
        }.sortedBy(McpToolSnapshot::remoteName)
        val fingerprint = McpServerSnapshot.calculateFingerprint(
            serverName = client.serverInfo.name,
            serverVersion = client.serverInfo.version,
            instructions = client.serverInstructions,
            supportsToolsListChanged = client.supportsToolsListChanged,
            tools = tools,
        )
        return McpServerSnapshot(
            serverName = client.serverInfo.name,
            serverVersion = client.serverInfo.version,
            instructions = client.serverInstructions,
            supportsToolsListChanged = client.supportsToolsListChanged,
            tools = tools,
            fingerprint = fingerprint,
            capturedAt = Clock.System.now(),
        )
    }

    private fun selectTools(
        config: McpServerConfig,
        tools: List<Tool>,
    ): List<Tool> {
        require(tools.map(Tool::name).distinct().size == tools.size) {
            "MCP server ${config.id.value} returned duplicate tool names"
        }
        val available = tools.map(Tool::name).toSet()
        val allowedTools = config.allowedTools
        val configuredNames = allowedTools.orEmpty() + config.excludedTools
        val missing = configuredNames - available
        require(missing.isEmpty()) {
            "MCP server ${config.id.value} filters reference missing tools: ${missing.sorted().joinToString()}"
        }
        val selected = tools
            .filter { allowedTools == null || it.name in allowedTools }
            .filterNot { it.name in config.excludedTools }
        require(selected.isNotEmpty()) {
            "MCP server ${config.id.value} exposes no tools after filtering"
        }
        return selected
    }

    private fun prepareActive(
        server: McpServer,
        client: McpConnectedClient,
    ): ActiveMcpServer {
        val active = ActiveMcpServer(
            server = server,
            client = client,
            tools = server.snapshot.tools.map { tool ->
                McpToolCallbackAdapter(
                    serverId = server.config.id,
                    client = client,
                    tool = tool,
                    forwardGrzConversationContext = server.config.forwardGrzConversationContext,
                )
            },
        )
        client.setToolsListChangedHandler {
            coroutineScope.launch {
                val active = activeServers[server.config.id]
                if (active?.client !== client || active.server.revision != server.revision) {
                    return@launch
                }
                val current = repository.find(server.config.id)
                if (
                    current != null &&
                    current.config.workerId == workerId &&
                    current.revision == server.revision
                ) {
                    repository.markRefreshAvailable(current.config.id, current.revision)
                }
            }
        }
        return active
    }

    private fun activate(active: ActiveMcpServer) {
        val server = active.server
        val previous = activeServers[server.config.id]
        activeServers = activeServers + (server.config.id to active)
        previous?.let {
            runCatching(it.client::close)
                .onFailure { error ->
                    log.warn(error) {
                        "Failed to close replaced MCP server client ${server.config.id.value}"
                    }
                }
        }
        log.info {
            "Activated MCP server ${server.config.id.value} revision=${server.revision} " +
                "tools=${server.snapshot.tools.size}"
        }
    }

    private fun validateMutation(
        kind: McpServerMutationKind,
        config: McpServerConfig,
        expectedRevision: Long?,
        current: McpServer?,
    ) {
        when (kind) {
            McpServerMutationKind.CREATE -> require(current == null) {
                "MCP server already exists: ${config.id.value}"
            }
            McpServerMutationKind.UPDATE -> {
                requireNotNull(current) { "MCP server not found: ${config.id.value}" }
                require(current.config.workerId == config.workerId) {
                    "MCP server worker assignment is immutable; delete and recreate it to move workers"
                }
                require(current.revision == expectedRevision) {
                    "MCP server revision conflict: expected=$expectedRevision actual=${current.revision}"
                }
            }
            McpServerMutationKind.REFRESH -> {
                requireNotNull(current) { "MCP server not found: ${config.id.value}" }
                require(current.revision == expectedRevision) {
                    "MCP server revision conflict: expected=$expectedRevision actual=${current.revision}"
                }
                require(current.config == config) {
                    "MCP refresh cannot change configuration"
                }
            }
        }
    }

    private data class ActiveMcpServer(
        val server: McpServer,
        val client: McpConnectedClient,
        val tools: List<AiToolCallback>,
    )
}
