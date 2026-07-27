package com.gromozeka.infrastructure.ai.config.mcp

import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpServerSnapshot
import com.gromozeka.domain.model.mcp.McpServerTransport
import com.gromozeka.domain.model.mcp.McpToolSnapshot
import com.gromozeka.domain.repository.McpServerRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.McpServerMutationKind
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpConfigurationServiceTest {
    @Test
    fun `create validates live tools then persists and activates accepted snapshot`() = runBlocking {
        fixture(tools = tools("Search", "Read", "Write")).use { fixture ->
            val config = fixture.config(
                allowedTools = setOf("Search", "Read"),
            )

            val created = fixture.service.apply(
                kind = McpServerMutationKind.CREATE,
                config = config,
                expectedRevision = null,
            )

            assertEquals(1, created.revision)
            assertEquals(listOf("Read", "Search"), created.snapshot.tools.map { it.remoteName })
            assertEquals(
                listOf("mcp__test_server__Read", "mcp__test_server__Search"),
                fixture.service.getTools().map { it.definition.name },
            )
            assertEquals(created, fixture.repository.find(config.id))
            assertFalse(fixture.client.closed)
            assertFalse(fixture.client.forceClosed)
        }
    }

    @Test
    fun `failed update leaves persisted and active revision untouched`() = runBlocking {
        val firstClient = FakeMcpConnectedClient(tools("Search"))
        val failedCandidate = FakeMcpConnectedClient(
            tools = emptyList(),
            listFailure = IllegalStateException("remote tools failed"),
        )
        fixture(firstClient, failedCandidate).use { fixture ->
            val config = fixture.config()
            fixture.service.apply(McpServerMutationKind.CREATE, config, null)

            assertFailsWith<IllegalStateException> {
                fixture.service.apply(
                    kind = McpServerMutationKind.UPDATE,
                    config = config.copy(displayName = "Updated"),
                    expectedRevision = 1,
                )
            }

            assertEquals(1, fixture.repository.find(config.id)?.revision)
            assertEquals("Test MCP", fixture.repository.find(config.id)?.config?.displayName)
            assertEquals(listOf("mcp__test_server__Search"), fixture.service.getTools().map { it.definition.name })
            assertFalse(firstClient.closed)
            assertTrue(failedCandidate.forceClosed)
        }
    }

    @Test
    fun `tools list changed only marks accepted revision for explicit refresh`() = runBlocking {
        fixture(tools = tools("Search")).use { fixture ->
            val config = fixture.config()
            fixture.service.apply(McpServerMutationKind.CREATE, config, null)

            fixture.client.notifyToolsListChanged()

            val persisted = fixture.repository.find(config.id)
            assertEquals(1, persisted?.revision)
            assertTrue(persisted?.refreshAvailable == true)
            assertEquals(listOf("Search"), persisted?.snapshot?.tools?.map { it.remoteName })
        }
    }

    @Test
    fun `tools list changed from replaced client does not mark current revision`() = runBlocking {
        val original = FakeMcpConnectedClient(tools("Search"))
        val replacement = FakeMcpConnectedClient(tools("Search"))
        fixture(original, replacement).use { fixture ->
            val config = fixture.config()
            fixture.service.apply(McpServerMutationKind.CREATE, config, null)
            fixture.service.apply(
                kind = McpServerMutationKind.UPDATE,
                config = config.copy(displayName = "Updated"),
                expectedRevision = 1,
            )

            original.notifyToolsListChanged()

            val persisted = fixture.repository.find(config.id)
            assertEquals(2, persisted?.revision)
            assertFalse(persisted?.refreshAvailable == true)
        }
    }

    @Test
    fun `explicit refresh atomically swaps accepted tool snapshot`() = runBlocking {
        val original = FakeMcpConnectedClient(tools("Search"))
        val refreshed = FakeMcpConnectedClient(tools("Search", "Fetch"))
        fixture(original, refreshed).use { fixture ->
            val config = fixture.config()
            fixture.service.apply(McpServerMutationKind.CREATE, config, null)

            val accepted = fixture.service.apply(
                kind = McpServerMutationKind.REFRESH,
                config = config,
                expectedRevision = 1,
            )

            assertEquals(2, accepted.revision)
            assertFalse(accepted.refreshAvailable)
            assertEquals(listOf("Fetch", "Search"), accepted.snapshot.tools.map { it.remoteName })
            assertEquals(
                listOf("mcp__test_server__Fetch", "mcp__test_server__Search"),
                fixture.service.getTools().map { it.definition.name },
            )
            assertTrue(original.closed)
            assertFalse(refreshed.closed)
        }
    }

    @Test
    fun `startup keeps accepted snapshot active when remote tool shape drifted`() = runBlocking {
        val repository = InMemoryMcpServerRepository()
        val config = testConfig(allowedTools = setOf("old_tool"))
        val persisted = server(config, listOf("old_tool"))
        repository.create(persisted)
        val observed = FakeMcpConnectedClient(tools("new_tool"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = McpConfigurationService(
            workerId = WORKER_ID.value,
            repository = repository,
            clientFactory = QueueMcpClientFactory(listOf(observed)),
            coroutineScope = scope,
        )
        try {
            service.initialize()

            assertEquals(listOf("mcp__test_server__old_tool"), service.getTools().map { it.definition.name })
            assertTrue(repository.find(config.id)?.refreshAvailable == true)
            assertFalse(observed.closed)
        } finally {
            service.shutdown()
            scope.cancel()
        }
    }

    private fun fixture(vararg clients: FakeMcpConnectedClient): Fixture =
        Fixture(clients.toList())

    private fun fixture(tools: List<Tool>): Fixture =
        Fixture(listOf(FakeMcpConnectedClient(tools)))

    private fun Fixture.config(
        allowedTools: Set<String>? = null,
    ): McpServerConfig =
        testConfig(allowedTools)

    private class Fixture(
        clients: List<FakeMcpConnectedClient>,
    ) : AutoCloseable {
        val repository = InMemoryMcpServerRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = clients.first()
        val service = McpConfigurationService(
            workerId = WORKER_ID.value,
            repository = repository,
            clientFactory = QueueMcpClientFactory(clients),
            coroutineScope = scope,
        )

        override fun close() {
            service.shutdown()
            scope.cancel()
        }
    }

    private companion object {
        val WORKER_ID = ConversationRuntimeWorkerId("test-worker")

        fun testConfig(allowedTools: Set<String>? = null): McpServerConfig =
            McpServerConfig(
                id = McpServerId("test_server"),
                displayName = "Test MCP",
                workerId = WORKER_ID,
                transport = McpServerTransport.Stdio(command = "unused"),
                allowedTools = allowedTools,
            )
    }
}

private class QueueMcpClientFactory(
    clients: List<FakeMcpConnectedClient>,
) : McpClientFactory {
    private val clients = ArrayDeque(clients)

    override suspend fun connect(config: McpServerConfig): McpConnectedClient =
        clients.removeFirstOrNull() ?: error("No fake MCP client left for ${config.id.value}")
}

private class FakeMcpConnectedClient(
    private val tools: List<Tool>,
    private val listFailure: Throwable? = null,
) : McpConnectedClient {
    override val serverInfo = Implementation(name = "test-mcp", version = "1.0.0")
    override val serverInstructions = "Use these tools for tests."
    override val supportsToolsListChanged = true

    var closed = false
        private set
    var forceClosed = false
        private set
    private var toolsListChangedHandler: (() -> Unit)? = null

    override suspend fun listAllTools(): List<Tool> {
        listFailure?.let { throw it }
        return tools
    }

    override suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any?>,
    ): String = "called:$toolName"

    override fun setToolsListChangedHandler(handler: () -> Unit) {
        toolsListChangedHandler = handler
    }

    override fun close() {
        closed = true
    }

    override fun forceClose() {
        forceClosed = true
    }

    fun notifyToolsListChanged() {
        checkNotNull(toolsListChangedHandler).invoke()
    }
}

private class InMemoryMcpServerRepository : McpServerRepository {
    private val servers = mutableMapOf<McpServerId, McpServer>()

    override suspend fun find(id: McpServerId): McpServer? = servers[id]

    override suspend fun list(): List<McpServer> = servers.values.sortedBy { it.config.id.value }

    override suspend fun listByWorker(workerId: ConversationRuntimeWorkerId): List<McpServer> =
        list().filter { it.config.workerId == workerId }

    override suspend fun create(server: McpServer): Boolean {
        if (servers.containsKey(server.config.id)) {
            return false
        }
        servers[server.config.id] = server
        return true
    }

    override suspend fun replace(
        server: McpServer,
        expectedRevision: Long,
    ): Boolean {
        val current = servers[server.config.id] ?: return false
        if (current.revision != expectedRevision) {
            return false
        }
        servers[server.config.id] = server
        return true
    }

    override suspend fun markRefreshAvailable(
        id: McpServerId,
        expectedRevision: Long,
    ): Boolean {
        val current = servers[id] ?: return false
        if (current.revision != expectedRevision) {
            return false
        }
        servers[id] = current.copy(refreshAvailable = true)
        return true
    }

    override suspend fun delete(
        id: McpServerId,
        expectedRevision: Long,
    ): Boolean {
        val current = servers[id] ?: return false
        if (current.revision != expectedRevision) {
            return false
        }
        servers.remove(id)
        return true
    }
}

private fun server(
    config: McpServerConfig,
    toolNames: List<String>,
): McpServer {
    val tools = toolNames.map { name ->
        McpToolSnapshot(
            remoteName = name,
            description = "Tool $name",
            inputSchema = "{}",
        )
    }
    val snapshot = McpServerSnapshot(
        serverName = "test-mcp",
        serverVersion = "1.0.0",
        instructions = "Use these tools for tests.",
        supportsToolsListChanged = true,
        tools = tools,
        fingerprint = McpServerSnapshot.calculateFingerprint(
            serverName = "test-mcp",
            serverVersion = "1.0.0",
            instructions = "Use these tools for tests.",
            supportsToolsListChanged = true,
            tools = tools,
        ),
        capturedAt = Instant.fromEpochMilliseconds(0),
    )
    return McpServer(
        config = config,
        snapshot = snapshot,
        revision = 1,
        refreshAvailable = false,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
}

private fun tools(vararg names: String): List<Tool> =
    names.map { name ->
        Tool(
            name = name,
            description = "Tool $name",
            inputSchema = ToolSchema(),
            outputSchema = null,
            annotations = null,
        )
    }
