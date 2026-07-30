package com.gromozeka.server

import com.gromozeka.application.service.InMemoryConversationRuntimeWorkerRegistry
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.repository.WorkerEnrollmentRepository
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerEnvironmentProfile
import com.gromozeka.domain.service.WorkerNativeShell
import com.gromozeka.domain.service.WorkerOperatingSystem
import com.gromozeka.remote.protocol.WorkerGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import java.security.MessageDigest
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

        assertEquals(worker, service.authenticate("Bearer $credential"))
        assertNull(service.authenticate("Bearer invalid-credential"))
        assertNull(service.authenticate(null))
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
        val gatewayService = WorkerGatewayService(runtimeRegistry, WorkerGatewaySessionRegistry())
        val authenticationService = WorkerGatewayAuthenticationService(repository)

        application {
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
            send(
                Frame.Binary(
                    true,
                    WorkerGatewayCodec.encode(WorkerGatewayMessage.Heartbeat(startedAt)),
                )
            )
        }
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
            organizationAccess = false,
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

private class GatewayAuthenticationRepository(
    private val credentialHash: String,
    private val worker: WorkerResource,
) : WorkerEnrollmentRepository {
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
