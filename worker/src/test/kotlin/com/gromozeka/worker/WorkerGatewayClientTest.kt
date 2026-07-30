package com.gromozeka.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerGatewayClientTest {
    @Test
    fun `gateway URL uses outbound secure WebSocket`() {
        assertEquals(
            "wss://gromozeka.example/worker/ws",
            workerGatewayWebSocketUrl("https://gromozeka.example"),
        )
        assertEquals(
            "ws://127.0.0.1:8765/worker/ws",
            workerGatewayWebSocketUrl("http://127.0.0.1:8765"),
        )
    }

    @Test
    fun `gateway URL refuses plaintext remote endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            workerGatewayWebSocketUrl("http://gromozeka.example")
        }
    }
}
