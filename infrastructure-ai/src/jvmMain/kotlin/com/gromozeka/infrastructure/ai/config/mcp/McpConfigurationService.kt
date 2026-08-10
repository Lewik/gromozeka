package com.gromozeka.infrastructure.ai.config.mcp

import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpServerSnapshot
import com.gromozeka.domain.model.mcp.McpToolSnapshot
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.McpServerRefreshPublisher
import com.gromozeka.domain.service.McpServerRevision
import com.gromozeka.domain.service.McpServerMutationKind
import com.gromozeka.domain.tool.AiToolCallback
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import jakarta.annotation.PreDestroy
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class McpConfigurationService(
    @Value("\${gromozeka.runtime.worker.id}") workerId: String,
    private val clientFactory: McpClientFactory,
    private val refreshPublisher: ObjectProvider<McpServerRefreshPublisher>,
    @Qualifier("mcpCoroutineScope") private val coroutineScope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val workerId = ConversationRuntimeWorkerId(workerId)
    private val mutationMutex = Mutex()

    @Volatile
    private var activeServers: Map<McpServerId, ActiveMcpServer> = emptyMap()

    fun getTools(): List<AiToolCallback> =
        activeServers.values
            .sortedBy { it.server.config.id.value }
            .flatMap(ActiveMcpServer::tools)

    suspend fun synchronize(servers: List<McpServer>): List<McpServerRevision> =
        mutationMutex.withLock {
            require(servers.map { it.config.id }.distinct().size == servers.size) {
                "Worker MCP synchronization contains duplicate server ids"
            }
            require(servers.all { it.config.workerId == workerId }) {
                "Worker MCP synchronization contains a server assigned to another Worker"
            }
            require(servers.map { it.config.namespace }.distinct().size == servers.size) {
                "Worker MCP synchronization contains duplicate tool namespaces"
            }

            val next = linkedMapOf<McpServerId, ActiveMcpServer>()
            val refreshAvailable = mutableListOf<McpServerRevision>()
            servers.sortedBy { it.config.id.value }.forEach { server ->
                val current = activeServers[server.config.id]
                if (current?.matches(server) == true) {
                    next[server.config.id] = current
                    return@forEach
                }
                runCatching { preparePersisted(server) }
                    .onSuccess { prepared ->
                        next[server.config.id] = prepared.active
                        if (prepared.refreshAvailable) {
                            refreshAvailable += McpServerRevision(
                                serverId = server.config.id,
                                revision = server.revision,
                            )
                        }
                    }
                    .onFailure { error ->
                        log.error(error) {
                            "Failed to activate persisted MCP server ${server.config.id.value}: ${error.message}"
                        }
                    }
            }

            activeServers.values
                .filterNot { active -> next[active.server.config.id] === active }
                .forEach(::close)
            activeServers = next
            refreshAvailable
        }

    suspend fun apply(
        kind: McpServerMutationKind,
        config: McpServerConfig,
        expectedRevision: Long?,
    ): McpServer = mutationMutex.withLock {
        require(config.workerId == workerId) {
            "MCP server ${config.id.value} targets worker ${config.workerId.value}, not ${workerId.value}"
        }
        require(
            activeServers.values.none {
                it.server.config.id != config.id && it.server.config.namespace == config.namespace
            }
        ) {
            "Worker ${workerId.value} already has an MCP server in namespace ${config.namespace.value}"
        }
        val current = activeServers[config.id]?.server
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
        val current = activeServers[serverId]?.server
            ?: error("MCP server not found: ${serverId.value}")
        require(current.config.workerId == workerId) {
            "MCP server ${serverId.value} belongs to worker ${current.config.workerId.value}"
        }
        require(current.revision == expectedRevision) {
            "MCP server revision conflict: expected=$expectedRevision actual=${current.revision}"
        }
        val removed = activeServers[serverId]
        activeServers = activeServers - serverId
        removed?.let(::close)
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

    private suspend fun preparePersisted(server: McpServer): PreparedMcpServer {
        val client = connect(server.config)
        try {
            val observedTools = client.listAllTools()
            val observedSnapshot = runCatching {
                snapshot(server.config, client, observedTools)
            }.getOrElse { error ->
                log.warn(error) {
                    "MCP server ${server.config.id.value} no longer matches its accepted snapshot; " +
                        "explicit refresh is required"
                }
                return PreparedMcpServer(
                    active = prepareActive(server, client),
                    refreshAvailable = true,
                )
            }
            val refreshAvailable = observedSnapshot.fingerprint != server.snapshot.fingerprint
            if (refreshAvailable) {
                log.warn {
                    "MCP server ${server.config.id.value} tools changed; explicit refresh is required"
                }
            }
            return PreparedMcpServer(
                active = prepareActive(server, client),
                refreshAvailable = refreshAvailable,
            )
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
                    namespace = server.config.namespace,
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
                refreshPublisher.getIfAvailable()?.publishRefreshAvailable(
                    serverId = server.config.id,
                    expectedRevision = server.revision,
                )
            }
        }
        return active
    }

    private fun activate(active: ActiveMcpServer) {
        val server = active.server
        val previous = activeServers[server.config.id]
        activeServers = activeServers + (server.config.id to active)
        previous?.let {
            close(it)
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
    ) {
        fun matches(other: McpServer): Boolean =
            server.config == other.config &&
                server.snapshot == other.snapshot &&
                server.revision == other.revision
    }

    private data class PreparedMcpServer(
        val active: ActiveMcpServer,
        val refreshAvailable: Boolean,
    )

    private fun close(server: ActiveMcpServer) {
        runCatching(server.client::close)
            .onFailure { error ->
                log.warn(error) {
                    "Failed to close MCP server client ${server.server.config.id.value}"
                }
            }
    }
}
