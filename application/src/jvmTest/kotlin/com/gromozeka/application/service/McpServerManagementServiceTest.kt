package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpServerSnapshot
import com.gromozeka.domain.model.mcp.McpServerTransport
import com.gromozeka.domain.model.mcp.McpTransportValueRemovals
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

    @Test
    fun `update preserves omitted environment values and applies replacements and removals`() = runBlocking {
        val fixture = Fixture(online = true)
        try {
            val currentConfig = fixture.server.config.copy(
                transport = McpServerTransport.Stdio(
                    command = "mcp-server",
                    environment = mapOf(
                        "KEEP" to "kept-secret",
                        "REPLACE" to "old-secret",
                        "REMOVE" to "removed-secret",
                    ),
                )
            )
            fixture.repository.create(fixture.server.copy(config = currentConfig))

            fixture.service.update(
                config = currentConfig.copy(
                    displayName = "Updated MCP",
                    transport = McpServerTransport.Stdio(
                        command = "mcp-server",
                        environment = mapOf(
                            "REPLACE" to "new-secret",
                            "ADD" to "added-secret",
                        ),
                    ),
                ),
                expectedRevision = 1,
                transportValueRemovals = McpTransportValueRemovals(
                    environmentVariables = setOf("REMOVE")
                ),
            )

            val command = fixture.request?.command as WorkerControlRequest.Command.ApplyMcpServer
            val transport = command.config.transport as McpServerTransport.Stdio
            assertEquals(
                mapOf(
                    "KEEP" to "kept-secret",
                    "REPLACE" to "new-secret",
                    "ADD" to "added-secret",
                ),
                transport.environment,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `HTTP header updates are merged and removed case insensitively`() = runBlocking {
        val fixture = Fixture(online = true)
        try {
            val currentConfig = fixture.server.config.copy(
                transport = McpServerTransport.StreamableHttp(
                    url = "https://mcp.example.test",
                    headers = mapOf(
                        "Authorization" to "old-token",
                        "X-Keep" to "kept-value",
                        "X-Remove" to "removed-value",
                    ),
                )
            )
            fixture.repository.create(fixture.server.copy(config = currentConfig))

            fixture.service.update(
                config = currentConfig.copy(
                    transport = McpServerTransport.StreamableHttp(
                        url = "https://mcp.example.test/v2",
                        headers = mapOf(
                            "authorization" to "new-token",
                            "X-Add" to "added-value",
                        ),
                    ),
                ),
                expectedRevision = 1,
                transportValueRemovals = McpTransportValueRemovals(
                    httpHeaders = setOf("x-remove")
                ),
            )

            val command = fixture.request?.command as WorkerControlRequest.Command.ApplyMcpServer
            val transport = command.config.transport as McpServerTransport.StreamableHttp
            assertEquals(
                mapOf(
                    "X-Keep" to "kept-value",
                    "authorization" to "new-token",
                    "X-Add" to "added-value",
                ),
                transport.headers,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `update rejects stale revisions before contacting the worker`() = runBlocking {
        val fixture = Fixture(online = true)
        try {
            fixture.repository.create(fixture.server)

            val error = assertFailsWith<IllegalArgumentException> {
                fixture.service.update(
                    config = fixture.server.config,
                    expectedRevision = 2,
                )
            }

            assertEquals(
                "MCP server revision conflict: expected 2, actual 1",
                error.message,
            )
            assertEquals(null, fixture.request)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `update rejects replacing and removing the same HTTP header`() = runBlocking {
        val fixture = Fixture(online = true)
        try {
            val currentConfig = fixture.server.config.copy(
                transport = McpServerTransport.StreamableHttp(
                    url = "https://mcp.example.test",
                    headers = mapOf("Authorization" to "old-token"),
                )
            )
            fixture.repository.create(fixture.server.copy(config = currentConfig))

            val error = assertFailsWith<IllegalArgumentException> {
                fixture.service.update(
                    config = currentConfig.copy(
                        transport = McpServerTransport.StreamableHttp(
                            url = "https://mcp.example.test",
                            headers = mapOf("authorization" to "new-token"),
                        )
                    ),
                    expectedRevision = 1,
                    transportValueRemovals = McpTransportValueRemovals(
                        httpHeaders = setOf("AUTHORIZATION")
                    ),
                )
            }

            assertEquals(
                "MCP HTTP headers cannot be replaced and removed in the same update",
                error.message,
            )
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
