package com.gromozeka.client

import com.gromozeka.remote.protocol.ClientInstanceId
import com.gromozeka.remote.protocol.GetSettingsRequest
import com.gromozeka.remote.protocol.RemoteClientPlatform
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertContentEquals
import com.gromozeka.domain.model.*
import com.gromozeka.remote.protocol.ApplicationUpdatePoll
import com.gromozeka.remote.protocol.ApplicationUpdatePollResponse
import com.gromozeka.updates.*
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class GromozekaWsClientLifecycleTest {
    @Test
    fun `independent updater verifies bytes refuses redirects and cancellation and installs only once`() {
        for (scenario in listOf("valid", "checksum", "redirect", "cancelled", "changed-after-download")) testApplication {
            val directory = Files.createTempDirectory("update-http-").toFile()
            val bytes = byteArrayOf(0, 1, 2, -1)
            val artifact = ApplicationUpdateArtifact(UpdateComponent.CLIENT, UpdatePlatform.WINDOWS_X64, "2.0.0", "client.zip",
                bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, UpdatePackageFormat.WINDOWS_ZIP)
            var installation = ApplicationInstallation("client-test", User.Id("test-user"), component = UpdateComponent.CLIENT,
                platform = UpdatePlatform.WINDOWS_X64, label = "Fixture", version = "1.0.0", lastSeenAt = "now",
                request = ApplicationUpdateRequest("update-test", artifact, "now"))
            var foreignRequests = 0
            externalServices {
                hosts("https://foreign.invalid") { routing { get("/package") { foreignRequests++; call.respondBytes(bytes) } } }
            }
            application {
                routing {
                    post("/api/updates/v1/client/poll") {
                        val poll = Json.decodeFromString<ApplicationUpdatePoll>(call.receiveText())
                        poll.report?.let { report ->
                            installation = installation.copy(request = requireNotNull(installation.request).copy(
                                phase = if (scenario == "cancelled" && report.phase == ApplicationUpdatePhase.READY) ApplicationUpdatePhase.CANCELLED else report.phase))
                        }
                        call.respondText(Json.encodeToString(ApplicationUpdatePollResponse(installation, artifact)), io.ktor.http.ContentType.Application.Json)
                    }
                    get("/api/updates/v1/client/artifacts/client-test/update-test") {
                        if (scenario == "redirect") call.respondRedirect("https://foreign.invalid/package")
                        else call.respondBytes(if (scenario == "checksum") byteArrayOf(9, 9, 9, 9) else bytes)
                    }
                }
            }
            val closedUiClient = createClient {}
            val updateHttp = createClient {}
            closedUiClient.close()
            var busy = true
            var installs = 0
            val installer = object : ApplicationUpdateInstaller {
                override suspend fun prepare() = !busy
                override suspend fun install(file: File, update: ApplicationUpdateRequest, store: UpdateLocalStore) {
                    assertContentEquals(bytes, file.readBytes())
                    assertEquals(ApplicationUpdatePhase.INSTALLING, store.read().report?.phase)
                    val duplicate = ApplicationUpdateAgent("http://localhost", createClient {}, UpdateComponent.CLIENT,
                        UpdatePlatform.WINDOWS_X64, "Duplicate", "1.0.0", UpdateLocalStore(directory), this,
                        closeHttpClient = true)
                    try {
                        assertFailsWith<IllegalStateException> { duplicate.checkNow() }
                    } finally { duplicate.close() }
                    installs++
                }
            }
            val agent = ApplicationUpdateAgent("http://localhost", updateHttp, UpdateComponent.CLIENT, UpdatePlatform.WINDOWS_X64,
                "Fixture", "1.0.0", UpdateLocalStore(directory), installer, closeHttpClient = true)
            try {
                agent.checkNow()
                if (scenario in setOf("valid", "changed-after-download")) {
                    assertEquals(ApplicationUpdatePhase.READY, agent.view.value.installation?.request?.phase)
                    assertFalse(agent.installReady())
                    busy = false
                    if (scenario == "changed-after-download") File(directory, "client.zip").writeBytes(byteArrayOf(9))
                    assertEquals(scenario == "valid", agent.installReady())
                    agent.checkNow()
                }
                assertEquals(if (scenario == "valid") 1 else 0, installs, scenario)
                assertEquals(0, foreignRequests)
                assertEquals(when (scenario) {
                    "valid" -> ApplicationUpdatePhase.INSTALLING
                    "cancelled" -> ApplicationUpdatePhase.CANCELLED
                    else -> ApplicationUpdatePhase.FAILED
                }, agent.view.value.installation?.request?.phase, scenario)
            } finally { agent.close(); directory.deleteRecursively() }
        }
    }

    @Test
    fun `owner cancellation does not fail pending requests as a network outage`() =
        verifyDisconnect(ownerCancelled = true)

    @Test
    fun `server disconnect still fails pending requests as a network outage`() =
        verifyDisconnect(ownerCancelled = false)

    private fun verifyDisconnect(ownerCancelled: Boolean) = testApplication {
        val requestReceived = CompletableDeferred<Unit>()
        val disconnect = CompletableDeferred<Unit>()
        application {
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    incoming.receive()
                    incoming.receive()
                    requestReceived.complete(Unit)
                    disconnect.await()
                    close()
                }
            }
        }
        val owner = SupervisorJob()
        val client = GromozekaWsClient(
            url = "ws://localhost/ws",
            httpClient = createClient { install(ClientWebSockets) },
            scope = CoroutineScope(owner + Dispatchers.Unconfined),
            clientInstanceId = ClientInstanceId("lifecycle-test"),
            clientPlatform = RemoteClientPlatform.DESKTOP,
        )
        try {
            withTimeout(10_000) {
                supervisorScope {
                    val request = async { client.request(GetSettingsRequest) }
                    try {
                        requestReceived.await()
                        assertEquals(RemoteConnectionState.Status.CONNECTED, client.connectionState.value.status)
                        if (ownerCancelled) {
                            owner.cancelAndJoin()
                            assertEquals(RemoteConnectionState.Status.CONNECTED, client.connectionState.value.status)
                            assertTrue(request.isActive)
                        } else {
                            disconnect.complete(Unit)
                            val failure = assertFailsWith<Exception> { request.await() }
                            assertEquals("RemoteConnectionLostException", failure::class.simpleName)
                            assertEquals(RemoteConnectionState.Status.OFFLINE, client.connectionState.value.status)
                        }
                    } finally {
                        request.cancelAndJoin()
                    }
                }
            }
        } finally {
            client.close()
            owner.cancelAndJoin()
            disconnect.complete(Unit)
        }
    }
}
