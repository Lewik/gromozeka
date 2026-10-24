package com.gromozeka.e2e

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HistoryNetworkProbeTest {
    private val payload = "{\"text\":\"שלום 😀 multiline\\ncontent\"}"

    @Test
    fun `probe measures chunked and fixed payload bytes without consuming the client response`() = runBlocking<Unit> {
        withServer { url ->
            HttpClient().use { client ->
                val probe = HistoryNetworkProbe(client)
                val chunked = client.get("$url/chunked/history")
                assertNull(chunked.headers[HttpHeaders.ContentLength])
                assertEquals(payload, chunked.bodyAsText())
                assertEquals(payload, client.get("$url/fixed/history").bodyAsText())
                assertEquals(listOf(payload.encodeToByteArray().size.toLong(), payload.encodeToByteArray().size.toLong()), probe.responseSizes)
                assertEquals(2, probe.requests.get())
                assertEquals(listOf("$url/chunked/history", "$url/fixed/history"), probe.urls)
            }
        }
    }

    @Test
    fun `probe still gates delivery after measuring a chunked body`() = runBlocking<Unit> {
        withServer { url ->
            HttpClient().use { client ->
                val probe = HistoryNetworkProbe(client)
                val gate = CompletableDeferred<Unit>()
                probe.gate = gate
                val response = async { client.get("$url/chunked/history").bodyAsText() }
                try {
                    withTimeout(5_000) { while (probe.responseSizes.isEmpty()) delay(10) }
                    assertFalse(response.isCompleted)
                    gate.complete(Unit)
                    assertEquals(payload, withTimeout(5_000) { response.await() })
                } finally {
                    gate.complete(Unit)
                }
            }
        }
    }

    private suspend fun withServer(block: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = payload.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, if (exchange.requestURI.path.startsWith("/fixed/")) bytes.size.toLong() else 0)
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}") } finally { server.stop(0) }
    }
}
