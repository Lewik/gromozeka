package com.gromozeka.infrastructure.ai.config.mcp

import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpServerSnapshot
import com.gromozeka.domain.model.mcp.McpServerTransport
import com.gromozeka.domain.model.mcp.McpToolSnapshot
import com.gromozeka.domain.model.mcp.McpToolNamespace
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.McpServerRefreshPublisher
import com.gromozeka.domain.service.McpServerRevision
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
import org.springframework.beans.factory.support.StaticListableBeanFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpConfigurationServiceTest {
    @Test
    fun `create validates live tools then activates accepted snapshot`() = runBlocking {
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
                listOf("mcp__test__Read", "mcp__test__Search"),
                fixture.service.getTools().map { it.definition.name },
            )
            assertFalse(fixture.client.closed)
            assertFalse(fixture.client.forceClosed)
        }
    }

    @Test
    fun `failed update leaves active revision untouched`() = runBlocking {
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

            assertEquals(listOf("mcp__test__Search"), fixture.service.getTools().map { it.definition.name })
            assertFalse(firstClient.closed)
            assertTrue(failedCandidate.forceClosed)
        }
    }

    @Test
    fun `tools list changed publishes accepted revision for explicit refresh`() = runBlocking {
        fixture(tools = tools("Search")).use { fixture ->
            val config = fixture.config()
            fixture.service.apply(McpServerMutationKind.CREATE, config, null)

            fixture.client.notifyToolsListChanged()

            assertEquals(
                listOf(McpServerRevision(config.id, 1)),
                fixture.refreshPublisher.references,
            )
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
            fixture.refreshPublisher.references.clear()

            original.notifyToolsListChanged()

            assertTrue(fixture.refreshPublisher.references.isEmpty())
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
                listOf("mcp__test__Fetch", "mcp__test__Search"),
                fixture.service.getTools().map { it.definition.name },
            )
            assertTrue(original.closed)
            assertFalse(refreshed.closed)
        }
    }

    @Test
    fun `startup keeps accepted snapshot active when remote tool shape drifted`() = runBlocking {
        val config = testConfig(allowedTools = setOf("old_tool"))
        val persisted = server(config, listOf("old_tool"))
        val observed = FakeMcpConnectedClient(tools("new_tool"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val refreshPublisher = RecordingRefreshPublisher()
        val service = McpConfigurationService(
            workerId = WORKER_ID.value,
            clientFactory = QueueMcpClientFactory(listOf(observed)),
            refreshPublisher = refreshPublisher.provider(),
            coroutineScope = scope,
        )
        try {
            val refreshAvailable = service.synchronize(listOf(persisted))

            assertEquals(listOf("mcp__test__old_tool"), service.getTools().map { it.definition.name })
            assertEquals(listOf(McpServerRevision(config.id, 1)), refreshAvailable)
            assertFalse(observed.closed)
        } finally {
            service.shutdown()
            scope.cancel()
        }
    }

    @Test
    fun `worker rejects a second installation in the same tool namespace`() = runBlocking {
        val firstClient = FakeMcpConnectedClient(tools("Search"))
        val unusedSecondClient = FakeMcpConnectedClient(tools("Search"))
        fixture(firstClient, unusedSecondClient).use { fixture ->
            val first = fixture.config()
            fixture.service.apply(McpServerMutationKind.CREATE, first, null)

            val error = assertFailsWith<IllegalArgumentException> {
                fixture.service.apply(
                    McpServerMutationKind.CREATE,
                    first.copy(id = McpServerId("test_server_copy")),
                    null,
                )
            }

            assertTrue(error.message.orEmpty().contains("already has an MCP server in namespace test"))
            assertFalse(unusedSecondClient.closed)
            assertFalse(unusedSecondClient.forceClosed)
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val refreshPublisher = RecordingRefreshPublisher()
        val client = clients.first()
        val service = McpConfigurationService(
            workerId = WORKER_ID.value,
            clientFactory = QueueMcpClientFactory(clients),
            refreshPublisher = refreshPublisher.provider(),
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
                namespace = McpToolNamespace("test"),
                displayName = "Test MCP",
                workerId = WORKER_ID,
                transport = McpServerTransport.Stdio(command = "unused"),
                allowedTools = allowedTools,
            )
    }
}

private class RecordingRefreshPublisher : McpServerRefreshPublisher {
    val references = mutableListOf<McpServerRevision>()

    override suspend fun publishRefreshAvailable(
        serverId: McpServerId,
        expectedRevision: Long,
    ) {
        references += McpServerRevision(serverId, expectedRevision)
    }

    fun provider() =
        StaticListableBeanFactory(mapOf("refreshPublisher" to this))
            .getBeanProvider(McpServerRefreshPublisher::class.java)
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
