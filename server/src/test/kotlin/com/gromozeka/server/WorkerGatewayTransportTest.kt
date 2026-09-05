package com.gromozeka.server

import com.gromozeka.remote.protocol.WorkerGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.worker.runtime.KtorWorkerGatewayTransport
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class WorkerGatewayTransportTest {
    @Test
    fun `transport accepts bounded binary messages and rejects oversized bytes before decoding`() = testApplication {
        val heartbeat = WorkerGatewayMessage.Heartbeat(Clock.System.now())
        application {
            install(WebSockets)
            routing {
                webSocket("/worker/ws") {
                    assertEquals("Bearer test-credential", call.request.headers["Authorization"])
                    send(Frame.Binary(true, WorkerGatewayCodec.encode(heartbeat)))
                    send(Frame.Binary(true, ByteArray(1025)))
                }
            }
        }
        val client = createClient { install(ClientWebSockets) }
        val connection = KtorWorkerGatewayTransport(client, "ws://localhost/worker/ws", "test-credential", 1024).connect()
        try {
            assertEquals(heartbeat, connection.receive())
            val failure = assertFailsWith<IllegalArgumentException> { connection.receive() }
            assertEquals("Worker Gateway Server message is too large", failure.message)
        } finally { connection.close(); client.close() }
    }
}
