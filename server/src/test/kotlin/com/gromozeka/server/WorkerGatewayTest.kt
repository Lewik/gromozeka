package com.gromozeka.server

import com.gromozeka.application.service.InMemoryConversationRuntimeWorkerRegistry
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.mcp.McpServer
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.repository.McpServerRepository
import com.gromozeka.domain.repository.WorkerEnrollmentRepository
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerEnvironmentProfile
import com.gromozeka.domain.service.WorkerNativeShell
import com.gromozeka.domain.service.WorkerOperatingSystem
import com.gromozeka.remote.protocol.WorkerGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.request.get
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import java.security.MessageDigest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkerGatewayTest {
    @Test
    fun `gateway authenticates an opaque bearer credential`() = runBlocking {
        val worker = worker("worker-1")
        val credential = "gateway-credential-that-is-long-enough-for-validation"
        val repository = GatewayAuthenticationRepository(
            credentialHash = sha256(credential),
            worker = worker,
        )
        val service = WorkerGatewayAuthenticationService(repository)

        assertEquals(worker, service.authenticate("Bearer $credential")?.worker)
        assertNull(service.authenticate("Bearer invalid-credential"))
        assertNull(service.authenticate(null))
    }

    @Test
    fun `rotated gateway credential invalidates its authenticated principal`() = runBlocking {
        val worker = worker("worker-1")
        val credential = "gateway-credential-that-is-long-enough-for-validation"
        val repository = GatewayAuthenticationRepository(
            credentialHash = sha256(credential),
            worker = worker,
        )
        val service = WorkerGatewayAuthenticationService(repository)
        val principal = requireNotNull(service.authenticate("Bearer $credential"))

        assertTrue(service.isActive(principal))

        repository.rotateTo(sha256("replacement-gateway-credential-that-is-long-enough"))

        assertFalse(service.isActive(principal))
    }

    @Test
    fun `only one live gateway session can own a worker id`() {
        val registry = WorkerGatewaySessionRegistry()
        val first = session("worker-1", "session-1")
        val second = session("worker-1", "session-2")

        assertTrue(registry.attach(first))
        assertFalse(registry.attach(second))
        assertEquals(first, registry.find(ConversationRuntimeWorkerId("worker-1")))
        assertFalse(registry.detach(second))
        assertTrue(registry.detach(first))
        assertNull(registry.find(ConversationRuntimeWorkerId("worker-1")))
    }

    @Test
    fun `authenticated worker completes gateway handshake`() = testApplication {
        val worker = worker("worker-1")
        val credential = "gateway-credential-that-is-long-enough-for-validation"
        val repository = GatewayAuthenticationRepository(
            credentialHash = sha256(credential),
            worker = worker,
        )
        val runtimeRegistry = InMemoryConversationRuntimeWorkerRegistry()
        val sessionRegistry = WorkerGatewaySessionRegistry()
        val authenticationService = WorkerGatewayAuthenticationService(repository)
        val gatewayService = WorkerGatewayService(
            runtimeRegistry,
            sessionRegistry,
            EmptyMcpServerRepository,
            emptyList(),
            TestAiConfigurationProvider,
            authenticationService,
        )

        application {
            installHttpAuthenticationErrors()
            install(ServerWebSockets)
            routing {
                route("/worker/ws") {
                    install(workerGatewayAuthentication(authenticationService))
                    webSocket {
                        gatewayService.handle(
                            socket = this,
                            authenticatedWorker = call.attributes[authenticatedWorkerGatewayKey],
                        )
                    }
                }
            }
        }

        val client = createClient {
            install(ClientWebSockets)
        }
        val identity = ConversationRuntimeWorkerIdentity(
            workerId = worker.id,
            sessionId = ConversationRuntimeWorkerSessionId("session-1"),
        )
        val startedAt = Instant.parse("2026-07-30T00:00:00Z")
        val registration = ConversationRuntimeWorkerRegistration(
            identity = identity,
            capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
            tools = emptyList(),
            environmentProfile = workerEnvironment(startedAt),
            version = "test",
            startedAt = startedAt,
            lastHeartbeatAt = startedAt,
        )

        client.webSocket("/worker/ws", request = {
            header(HttpHeaders.Authorization, "Bearer $credential")
        }) {
            send(Frame.Binary(true, WorkerGatewayCodec.encode(WorkerGatewayMessage.Hello(registration))))
            val welcome = WorkerGatewayCodec.decode((incoming.receive() as Frame.Binary).readBytes())
            assertTrue(welcome is WorkerGatewayMessage.Welcome)
            assertTrue(welcome.mcpServers.isEmpty())
            assertEquals(TestAiConfigurationProvider.snapshot, welcome.aiCatalogSnapshot)
            send(
                Frame.Binary(
                    true,
                    WorkerGatewayCodec.encode(WorkerGatewayMessage.Ready(tools = emptyList())),
                )
            )
            withTimeout(5_000) {
                while (sessionRegistry.find(identity.workerId) == null) {
                    delay(10)
                }
            }
            val response = async {
                sessionRegistry.execute(
                    target = identity,
                    operation = WorkerGatewayOperation.WORKER_CONTROL,
                    payload = "request".encodeToByteArray(),
                    timeout = Duration.ofSeconds(5),
                )
            }
            val request = WorkerGatewayCodec.decode((incoming.receive() as Frame.Binary).readBytes())
                as WorkerGatewayMessage.Request
            assertEquals(WorkerGatewayOperation.WORKER_CONTROL, request.operation)
            assertEquals("request", request.payload.decodeToString())
            send(
                Frame.Binary(
                    true,
                    WorkerGatewayCodec.encode(
                        WorkerGatewayMessage.Response(
                            requestId = request.id,
                            status = WorkerGatewayMessage.Response.Status.SUCCEEDED,
                            payload = "response".encodeToByteArray(),
                        )
                    ),
                )
            )
            assertEquals("response", response.await().decodeToString())
            send(
                Frame.Binary(
                    true,
                    WorkerGatewayCodec.encode(WorkerGatewayMessage.Heartbeat(startedAt)),
                )
            )
        }
    }

    @Test
    fun `worker gateway authentication fails closed`() = testApplication {
        val authenticationService = WorkerGatewayAuthenticationService(
            GatewayAuthenticationRepository(
                credentialHash = sha256("gateway-credential-that-is-long-enough-for-validation"),
                worker = worker("worker-1"),
            )
        )
        application {
            installHttpAuthenticationErrors()
            routing {
                route("/worker") {
                    install(workerGatewayAuthentication(authenticationService))
                    get {
                        call.attributes[authenticatedWorkerGatewayKey]
                        call.respondText("authenticated")
                    }
                }
            }
        }

        val response = client.get("/worker")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("""Bearer realm="gromozeka-worker"""", response.headers[HttpHeaders.WWWAuthenticate])
    }

    @Test
    fun `revoked worker session receives a disconnect request`() = runBlocking {
        val registry = WorkerGatewaySessionRegistry()
        val session = session("worker-1", "session-1")
        registry.attach(session)

        registry.disconnectRevokedWorker(session.identity.workerId)

        assertEquals(
            "Worker access was revoked",
            withTimeout(1_000) { session.awaitRequestedDisconnect() },
        )
    }

    private fun session(workerId: String, sessionId: String): WorkerGatewaySession =
        WorkerGatewaySession(
            ConversationRuntimeWorkerIdentity(
                workerId = ConversationRuntimeWorkerId(workerId),
                sessionId = ConversationRuntimeWorkerSessionId(sessionId),
            )
        )

    private fun worker(workerId: String): WorkerResource =
        WorkerResource(
            id = ConversationRuntimeWorkerId(workerId),
            displayName = workerId,
            ownerUserId = User.Id("owner"),
            runtimeWideAccess = false,
            status = WorkerResource.Status.ACTIVE,
            createdAt = Instant.parse("2026-07-30T00:00:00Z"),
            updatedAt = Instant.parse("2026-07-30T00:00:00Z"),
        )

    private fun workerEnvironment(observedAt: Instant): WorkerEnvironmentProfile =
        WorkerEnvironmentProfile(
            observedAt = observedAt,
            operatingSystem = WorkerOperatingSystem(
                family = WorkerOperatingSystem.Family.LINUX,
                name = "Linux",
                version = "test",
            ),
            architecture = "x86_64",
            nativeShell = WorkerNativeShell(
                kind = WorkerNativeShell.Kind.POSIX_SH,
                executable = "/bin/sh",
            ),
            timezoneId = "UTC",
            localeTag = "en-US",
            logicalProcessorCount = 1,
            totalMemoryBytes = null,
            availableExecutables = emptyList(),
        )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
}

private object TestAiConfigurationProvider : AiConfigurationProvider {
    private val connection = AiConnection.OpenAiApi(
        id = AiConnection.Id("test-connection"),
        displayName = "Test connection",
        enabled = true,
    )
    private val configuration = AiModelConfiguration(
        id = AiModelConfiguration.Id("test-model"),
        connectionId = connection.id,
        providerModelId = "test-model",
        displayName = "Test model",
    )
    override val snapshot = AiCatalogSnapshot(
        catalog = AiCatalog(
            connections = listOf(connection),
            modelSpecs = listOf(
                AiModelSpec(
                    id = configuration.providerModelId,
                    provider = AiProvider.OPENAI,
                    capabilities = AiModelCapability.entries.toSet(),
                    limits = AiModelSpec.Limits(
                        textGeneration = AiModelSpec.Limits.TextGeneration(
                            contextWindowTokens = 1_024,
                        ),
                        embeddings = AiModelSpec.Limits.Embeddings(dimensions = 8),
                    ),
                )
            ),
            modelConfigurations = listOf(configuration),
            runtimeAssignments = AiRuntimeAssignment.Purpose.entries
                .filter(AiRuntimeAssignment.Purpose::requiresExplicitAssignment)
                .map {
                    AiRuntimeAssignment(
                        purpose = it,
                        selection = AiRuntimeSelection(configuration.id),
                    )
                },
            defaultAgentId = AgentDefinition.Id("test-agent"),
        ),
        revision = 1,
    )
    override val snapshotFlow: StateFlow<AiCatalogSnapshot?> =
        MutableStateFlow<AiCatalogSnapshot?>(snapshot)

    override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime =
        ResolvedAiRuntime(connection, configuration)
}

private data object EmptyMcpServerRepository : McpServerRepository {
    override suspend fun find(id: McpServerId): McpServer? = null

    override suspend fun list(): List<McpServer> = emptyList()

    override suspend fun listByWorker(workerId: ConversationRuntimeWorkerId): List<McpServer> = emptyList()

    override suspend fun create(server: McpServer): Boolean = error("Not used")

    override suspend fun replace(server: McpServer, expectedRevision: Long): Boolean = error("Not used")

    override suspend fun markRefreshAvailable(id: McpServerId, expectedRevision: Long): Boolean =
        error("Not used")

    override suspend fun delete(id: McpServerId, expectedRevision: Long): Boolean = error("Not used")
}

private class GatewayAuthenticationRepository(
    credentialHash: String,
    private val worker: WorkerResource,
) : WorkerEnrollmentRepository {
    private var credentialHash = credentialHash

    fun rotateTo(credentialHash: String) {
        this.credentialHash = credentialHash
    }

    override suspend fun issue(
        tokenHash: String,
        ownerUserId: User.Id,
        createdAt: Instant,
        expiresAt: Instant,
    ) = error("Not used")

    override suspend fun consume(
        tokenHash: String,
        gatewayCredentialHash: String,
        workerId: ConversationRuntimeWorkerId,
        displayName: String,
        consumedAt: Instant,
    ): WorkerResource? = error("Not used")

    override suspend fun authenticateGatewayCredential(
        gatewayCredentialHash: String,
    ): WorkerResource? =
        worker.takeIf { gatewayCredentialHash == credentialHash }
}
