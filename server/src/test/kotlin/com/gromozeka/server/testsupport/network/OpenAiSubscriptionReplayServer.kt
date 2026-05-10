package com.gromozeka.server.testsupport.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Captured request sent to the local replay server.
 */
data class RecordedHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>>,
    val body: String,
)

/**
 * Scripted HTTP response returned by the local replay server.
 */
data class StubHttpResponse(
    val statusCode: Int = 200,
    val body: String,
    val contentType: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun sse(vararg events: SseEvent): StubHttpResponse {
            val body = buildString {
                events.forEach { event ->
                    event.name?.let { append("event: ").append(it).append('\n') }
                    event.data.lineSequence().forEach { line ->
                        append("data: ").append(line).append('\n')
                    }
                    append('\n')
                }
                append("data: [DONE]\n\n")
            }

            return StubHttpResponse(
                body = body,
                contentType = "text/event-stream",
                headers = mapOf("Cache-Control" to "no-cache"),
            )
        }
    }
}

data class SseEvent(
    val name: String? = null,
    val data: String,
)

/**
 * Minimal local server for replaying OpenAI subscription responses in tests.
 */
class OpenAiSubscriptionReplayServer private constructor(
    private val server: HttpServer,
) : Closeable {

    private val scriptedResponses = ConcurrentLinkedQueue<StubHttpResponse>()
    private val recordedRequests = CopyOnWriteArrayList<RecordedHttpRequest>()

    val baseUrl: String = "http://127.0.0.1:${server.address.port}"
    val responsesUrl: String = "$baseUrl/backend-api/codex/responses"

    fun enqueue(response: StubHttpResponse) {
        scriptedResponses += response
    }

    fun requests(): List<RecordedHttpRequest> = recordedRequests.toList()

    fun reset() {
        scriptedResponses.clear()
        recordedRequests.clear()
    }

    override fun close() {
        server.stop(0)
    }

    private fun handleResponses(exchange: HttpExchange) {
        val requestBody = exchange.requestBody.use { input ->
            input.readAllBytes().toString(StandardCharsets.UTF_8)
        }

        recordedRequests += RecordedHttpRequest(
            method = exchange.requestMethod,
            path = exchange.requestURI.path,
            headers = exchange.requestHeaders.mapValues { (_, values) -> values.toList() },
            body = requestBody,
        )

        if (exchange.requestMethod.equals("GET", ignoreCase = true)) {
            val responseBytes = """{"error":{"message":"WebSocket upgrade is not supported by replay server"}}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(426, responseBytes.size.toLong())
            exchange.responseBody.use { output ->
                output.write(responseBytes)
            }
            return
        }

        val response = scriptedResponses.poll() ?: StubHttpResponse(
            statusCode = 500,
            body = """{"error":{"message":"No scripted response available for ${exchange.requestURI.path}"}}""",
        )

        val responseBytes = response.body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", response.contentType)
        response.headers.forEach { (key, value) ->
            exchange.responseHeaders.add(key, value)
        }
        exchange.sendResponseHeaders(response.statusCode, responseBytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(responseBytes)
        }
    }

    companion object {
        fun start(): OpenAiSubscriptionReplayServer {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val replayServer = OpenAiSubscriptionReplayServer(server)
            server.createContext("/backend-api/codex/responses", replayServer::handleResponses)
            server.executor = null
            server.start()
            return replayServer
        }
    }
}
