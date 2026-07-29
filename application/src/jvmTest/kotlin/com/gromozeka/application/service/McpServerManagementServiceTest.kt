package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpServerSnapshot
import com.gromozeka.domain.model.mcp.McpServerTransport
import com.gromozeka.domain.model.mcp.McpToolSnapshot
import com.gromozeka.domain.repository.AiToolCapabilityCatalogRepository
import com.gromozeka.domain.repository.McpServerRepository
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerControlClient
import com.gromozeka.domain.service.WorkerControlRequest
import com.gromozeka.domain.service.WorkerControlResult
import com.gromozeka.domain.tool.AiToolCapabilityCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McpServerManagementServiceTest {
    @Test
    fun `create targets the current exact worker session and schedules its source catalog`() = runBlocking {
        val fixture = Fixture(online = true)
        try {
            val created = fixture.service.create(fixture.server.config)

            assertEquals(fixture.server, created)
            assertEquals(fixture.identity, fixture.request?.target)
            val command = fixture.request?.command as WorkerControlRequest.Command.ApplyMcpServer
            assertEquals(fixture.server.config, command.config)
            assertEquals(fixture.server.config.id.sourceId, fixture.generatedSource?.id)
            assertEquals(fixture.server.snapshot.instructions, fixture.generatedSource?.instructions)
            assertEquals(
                fixture.server.snapshot.tools.map { it.remoteName },
                fixture.generatedSource?.definitions?.map { it.name.substringAfterLast("__") },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `create does not send a command when the assigned worker is offline`() = runBlocking {
        val fixture = Fixture(online = false)
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                fixture.service.create(fixture.server.config)
            }

            assertEquals("Worker is offline: test-worker", error.message)
            assertEquals(null, fixture.request)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        online: Boolean,
    ) {
        val identity = ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId("test-worker"),
            sessionId = ConversationRuntimeWorkerSessionId("session-1"),
        )
        val server = testServer(identity.workerId)
        val repository = TestMcpServerRepository()
        val registry = InMemoryConversationRuntimeWorkerRegistry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var request: WorkerControlRequest? = null
        var generatedSource: AiToolCapabilitySource? = null

        private val catalogService = AiToolCapabilityCatalogService(
            repository = TestCapabilityCatalogRepository(),
            mcpServerRepository = repository,
            generator = AiToolCapabilityCatalogGenerator { source, fingerprint ->
                generatedSource = source
                AiToolCapabilityCatalog(
                    sourceId = source.id,
                    fingerprint = fingerprint,
                    overview = "Test MCP tools.",
                    categories = listOf(
                        AiToolCapabilityCatalog.Category(
                            id = "test_tools",
                            label = "Test Tools",
                            summary = "Tools used by the test MCP source.",
                            toolNames = source.definitions.map { it.name },
                        )
                    ),
                    generatedByModelConfigurationId = AiModelConfiguration.Id("test-model"),
                    generatedAt = Clock.System.now(),
                )
            },
            coroutineScope = scope,
        )
        private val controlClient = object : WorkerControlClient {
            override suspend fun execute(request: WorkerControlRequest): WorkerControlResult {
                this@Fixture.request = request
                repository.create(server)
                return WorkerControlResult(
                    requestId = request.id,
                    status = WorkerControlResult.Status.SUCCEEDED,
                    mcpServer = server,
                )
            }
        }
        val service = McpServerManagementService(
            repository = repository,
            workerRegistry = registry,
            workerControlClient = controlClient,
            capabilityCatalogService = catalogService,
        )

        init {
            val now = Clock.System.now()
            val registrationTime = if (online) now else Instant.fromEpochMilliseconds(0)
            runBlocking {
                registry.register(
                    registration = ConversationRuntimeWorkerRegistration(
                        identity = identity,
                        capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
                        tools = emptyList(),
                        environmentProfile = testWorkerEnvironmentProfile(registrationTime),
                        version = "test",
                        startedAt = registrationTime,
                        lastHeartbeatAt = registrationTime,
                    ),
                    staleBefore = Instant.fromEpochMilliseconds(0),
                )
            }
        }

        fun close() {
            scope.cancel()
        }
    }
}

private class TestMcpServerRepository : McpServerRepository {
    private val servers = mutableMapOf<McpServerId, McpServer>()

    override suspend fun find(id: McpServerId): McpServer? = servers[id]

    override suspend fun list(): List<McpServer> = servers.values.toList()

    override suspend fun listByWorker(workerId: ConversationRuntimeWorkerId): List<McpServer> =
        servers.values.filter { it.config.workerId == workerId }

    override suspend fun create(server: McpServer): Boolean =
        servers.putIfAbsent(server.config.id, server) == null

    override suspend fun replace(server: McpServer, expectedRevision: Long): Boolean = error("Not used")

    override suspend fun markRefreshAvailable(id: McpServerId, expectedRevision: Long): Boolean = error("Not used")

    override suspend fun delete(id: McpServerId, expectedRevision: Long): Boolean = error("Not used")
}

private class TestCapabilityCatalogRepository : AiToolCapabilityCatalogRepository {
    private val catalogs = mutableMapOf<Pair<String, String>, AiToolCapabilityCatalog>()

    override suspend fun find(
        sourceId: String,
        fingerprint: String,
    ): AiToolCapabilityCatalog? = catalogs[sourceId to fingerprint]

    override suspend fun saveIfAbsent(catalog: AiToolCapabilityCatalog): AiToolCapabilityCatalog =
        catalogs.getOrPut(catalog.sourceId to catalog.fingerprint) { catalog }
}

private fun testServer(workerId: ConversationRuntimeWorkerId): McpServer {
    val config = McpServerConfig(
        id = McpServerId("test_server"),
        displayName = "Test MCP",
        workerId = workerId,
        transport = McpServerTransport.Stdio(command = "unused"),
    )
    val tools = listOf(
        McpToolSnapshot(
            remoteName = "search",
            description = "Search test data.",
            inputSchema = "{}",
        )
    )
    val snapshot = McpServerSnapshot(
        serverName = "test-mcp",
        serverVersion = "1.0.0",
        instructions = "Use this MCP for test data.",
        supportsToolsListChanged = true,
        tools = tools,
        fingerprint = McpServerSnapshot.calculateFingerprint(
            serverName = "test-mcp",
            serverVersion = "1.0.0",
            instructions = "Use this MCP for test data.",
            supportsToolsListChanged = true,
            tools = tools,
        ),
        capturedAt = Clock.System.now(),
    )
    val now = Clock.System.now()
    return McpServer(
        config = config,
        snapshot = snapshot,
        revision = 1,
        refreshAvailable = false,
        createdAt = now,
        updatedAt = now,
    )
}
