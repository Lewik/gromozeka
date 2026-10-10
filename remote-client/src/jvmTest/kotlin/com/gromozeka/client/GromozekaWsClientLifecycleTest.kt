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
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class GromozekaWsClientLifecycleTest {
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
