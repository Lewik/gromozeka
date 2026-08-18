package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.BundledMcpRuntime
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpServerSnapshot
import com.gromozeka.domain.model.mcp.McpServerTransport
import com.gromozeka.domain.model.mcp.McpTransportValueRemovals
import com.gromozeka.domain.model.mcp.McpToolSnapshot
import com.gromozeka.domain.model.mcp.McpToolNamespace
import com.gromozeka.domain.repository.AiToolCapabilityCatalogRepository
import com.gromozeka.domain.repository.McpServerRepository
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerControlClient
import com.gromozeka.domain.service.WorkerControlRequest
import com.gromozeka.domain.service.WorkerControlResult
import com.gromozeka.domain.service.WorkerToolExecutionClient
import com.gromozeka.domain.service.WorkerToolExecutionResult
import com.gromozeka.domain.tool.AiToolCapabilityCatalog
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpServerManagementServiceTest {
    @Test
    fun `browser probe executes screenshot on the assigned worker and preserves binary`() = runBlocking {
        val fixture = Fixture(online = true)
        try {
            val created = fixture.service.create(fixture.server.config)
            val result = fixture.service.testBrowserUse(created.config.id)

            assertContentEquals(fixture.screenshot, result.screenshot)
            assertEquals("image/png", result.mediaType)
            assertEquals("page.png", result.fileName)
            assertEquals(fixture.identity, fixture.toolExecutionIdentity)
            assertEquals(
                fixture.identity.workerId,
                fixture.toolExecutionTarget?.workerId,
            )
            val toolCall = fixture.toolExecutionCalls.single()
            assertEquals("mcp__test__browser_take_screenshot", toolCall.call.name)
            assertEquals(
                fixture.identity.workerId.value,
                toolCall.call.input.jsonObject
                    .getValue(AI_TOOL_EXECUTION_TARGET_FIELD)
                    .jsonObject
                    .getValue(AI_TOOL_EXECUTION_WORKER_ID_FIELD)
                    .jsonPrimitive
                    .content,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `create targets the current exact worker session and schedules its source catalog`() = runBlocking {
        val fixture = Fixture(online = true)
        try {
            val created = fixture.service.create(fixture.server.config)

            assertEquals(fixture.server.config, created.config)
            assertEquals(fixture.server.snapshot, created.snapshot)
            assertEquals(1, created.revision)
            assertEquals(created, fixture.repository.find(created.config.id))
            assertEquals(fixture.identity, fixture.request?.target)
            val command = fixture.request?.command as WorkerControlRequest.Command.ApplyMcpServer
            assertEquals(fixture.server.config, command.config)
            assertEquals(fixture.server.config.namespace.sourceId, fixture.generatedSource?.id)
            assertEquals(fixture.server.snapshot.instructions, fixture.generatedSource?.instructions)
            assertEquals(
                fixture.server.snapshot.tools.map { it.remoteName }.sorted(),
                fixture.generatedSource?.definitions
                    ?.map { it.name.substringAfterLast("__") }
                    ?.sorted(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `matching namespace contracts are shared across workers`() = runBlocking {
        val fixture = Fixture(online = true)
        try {
            fixture.repository.create(
                fixture.server.copy(
                    config = fixture.server.config.copy(
                        id = McpServerId("other_installation"),
                        workerId = ConversationRuntimeWorkerId("other-worker"),
                    )
                )
            )

            val created = fixture.service.create(fixture.server.config)

            assertEquals(fixture.server.config.namespace, created.config.namespace)
            assertEquals(2, fixture.repository.list().size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `different namespace contracts coexist across workers`() = runBlocking {
        val fixture = Fixture(online = true)
        try {
            val conflictingTools = fixture.server.snapshot.tools.map { tool ->
                if (tool.remoteName == "search") tool.copy(description = "Conflicting search contract.") else tool
            }
            val conflictingSnapshot = fixture.server.snapshot.copy(
                tools = conflictingTools,
                fingerprint = McpServerSnapshot.calculateFingerprint(
                    serverName = fixture.server.snapshot.serverName,
                    serverVersion = fixture.server.snapshot.serverVersion,
                    instructions = fixture.server.snapshot.instructions,
                    supportsToolsListChanged = fixture.server.snapshot.supportsToolsListChanged,
                    tools = conflictingTools,
                ),
            )
            fixture.repository.create(
                fixture.server.copy(
                    config = fixture.server.config.copy(
                        id = McpServerId("other_installation"),
                        workerId = ConversationRuntimeWorkerId("other-worker"),
                    ),
                    snapshot = conflictingSnapshot,
                )
            )

            val created = fixture.service.create(fixture.server.config)

            assertEquals(fixture.server.config.namespace, created.config.namespace)
            assertEquals(2, fixture.repository.list().size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `duplicate namespace installation on one worker is rejected before execution`() = runBlocking {
        val fixture = Fixture(online = true)
        try {
            fixture.repository.create(
                fixture.server.copy(
                    config = fixture.server.config.copy(id = McpServerId("other_installation"))
                )
            )

            val error = assertFailsWith<IllegalArgumentException> {
                fixture.service.create(fixture.server.config)
            }

            assertTrue(error.message.orEmpty().contains("already has MCP server other_installation"))
            assertEquals(null, fixture.request)
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
    fun `bundled runtime update preserves its internal configuration and token`() = runBlocking {
        val fixture = Fixture(online = true)
        try {
            val currentConfig = fixture.server.config.copy(
                transport = McpServerTransport.BundledStdio(
                    runtime = BundledMcpRuntime.BROWSER_USE,
                    arguments = listOf("--extension"),
                    environment = mapOf(
                        "PLAYWRIGHT_MCP_EXTENSION_ID" to "bridge-id",
                        "PLAYWRIGHT_MCP_EXTENSION_TOKEN" to "secret-token",
                    ),
                    ephemeralWorkingDirectory = true,
                )
            )
            fixture.repository.create(fixture.server.copy(config = currentConfig))

            fixture.service.update(
                config = currentConfig.copy(
                    displayName = "Updated Browser",
                    transport = McpServerTransport.BundledStdio(
                        runtime = BundledMcpRuntime.BROWSER_USE,
                        arguments = listOf("--extension", "--output-max-size=52428800"),
                        environment = mapOf("PLAYWRIGHT_MCP_EXTENSION_ID" to "bridge-id"),
                        ephemeralWorkingDirectory = true,
                    ),
                ),
                expectedRevision = 1,
            )

            val command = fixture.request?.command as WorkerControlRequest.Command.ApplyMcpServer
            val transport = command.config.transport as McpServerTransport.BundledStdio
            assertEquals(
                mapOf(
                    "PLAYWRIGHT_MCP_EXTENSION_ID" to "bridge-id",
                    "PLAYWRIGHT_MCP_EXTENSION_TOKEN" to "secret-token",
                ),
                transport.environment,
            )
            assertEquals(listOf("--extension", "--output-max-size=52428800"), transport.arguments)
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
    fun `update rejects moving a server to another worker`() = runBlocking {
        val fixture = Fixture(online = true)
        try {
            fixture.repository.create(fixture.server)

            val error = assertFailsWith<IllegalArgumentException> {
                fixture.service.update(
                    config = fixture.server.config.copy(
                        workerId = ConversationRuntimeWorkerId("other-worker")
                    ),
                    expectedRevision = 1,
                )
            }

            assertEquals(
                "Moving an MCP server between Workers requires explicit delete and create operations",
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
        val screenshot = byteArrayOf(1, 3, 3, 7)
        var toolExecutionIdentity: ConversationRuntimeWorkerIdentity? = null
        var toolExecutionTarget: ConversationRuntimeTaskTarget.Worker? = null
        var toolExecutionCalls = emptyList<Conversation.Message.ContentItem.ToolCall>()

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
                return when (val command = request.command) {
                    is WorkerControlRequest.Command.ApplyMcpServer -> {
                        val current = repository.find(command.config.id)
                        WorkerControlResult(
                            requestId = request.id,
                            status = WorkerControlResult.Status.SUCCEEDED,
                            mcpServer = server.copy(
                                config = command.config,
                                revision = (current?.revision ?: 0) + 1,
                            ),
                        )
                    }
                    is WorkerControlRequest.Command.DeleteMcpServer ->
                        WorkerControlResult(
                            requestId = request.id,
                            status = WorkerControlResult.Status.DELETED,
                        )
                    is WorkerControlRequest.Command.SynchronizeMcpServers ->
                        WorkerControlResult(
                            requestId = request.id,
                            status = WorkerControlResult.Status.SYNCHRONIZED,
                        )
                }
            }
        }
        private val toolExecutionClient = object : WorkerToolExecutionClient {
            override suspend fun execute(
                target: ConversationRuntimeWorkerIdentity,
                executionTarget: ConversationRuntimeTaskTarget.Worker,
                toolCalls: List<Conversation.Message.ContentItem.ToolCall>,
                toolContext: ToolExecutionContext,
                resolvedSecretsByToolCallId: Map<String, Map<String, String>>,
            ): WorkerToolExecutionResult {
                toolExecutionIdentity = target
                toolExecutionTarget = executionTarget
                toolExecutionCalls = toolCalls
                val call = toolCalls.single()
                return WorkerToolExecutionResult(
                    results = listOf(
                        Conversation.Message.ContentItem.ToolResult(
                            toolUseId = call.id,
                            toolName = call.call.name,
                            result = listOf(
                                Conversation.Message.ContentItem.ToolResult.Data.Base64Data(
                                    data = Base64.getEncoder().encodeToString(screenshot),
                                    mediaType = Conversation.Message.MediaType.IMAGE_PNG,
                                    fileName = "page.png",
                                )
                            ),
                        )
                    ),
                    returnDirect = false,
                )
            }
        }
        val service = McpServerManagementService(
            repository = repository,
            workerRegistry = registry,
            workerControlClient = controlClient,
            workerToolExecutionClient = toolExecutionClient,
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

    override suspend fun replace(server: McpServer, expectedRevision: Long): Boolean {
        val current = servers[server.config.id] ?: return false
        if (current.revision != expectedRevision) return false
        servers[server.config.id] = server
        return true
    }

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
        namespace = McpToolNamespace("test"),
        displayName = "Test MCP",
        workerId = workerId,
        transport = McpServerTransport.Stdio(command = "unused"),
    )
    val tools = listOf(
        McpToolSnapshot(
            remoteName = "search",
            description = "Search test data.",
            inputSchema = "{}",
        ),
        McpToolSnapshot(
            remoteName = "browser_take_screenshot",
            description = "Capture a browser screenshot.",
            inputSchema = "{}",
        ),
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
