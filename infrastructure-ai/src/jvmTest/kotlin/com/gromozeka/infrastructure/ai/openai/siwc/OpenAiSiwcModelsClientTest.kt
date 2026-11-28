package com.gromozeka.infrastructure.ai.openai.siwc

import com.gromozeka.domain.model.ai.AiCallFailure
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class OpenAiSiwcModelsClientTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/models", ::models)
        createContext("/v1/unauthorized") { exchange ->
            exchange.json(401, "not-json")
        }
        start()
    }

    @AfterTest
    fun close() = server.stop(0)

    @Test
    fun `lists only visible ChatGPT plan models`() = runBlocking {
        val client = OpenAiSiwcModelsClient(
            accessTokens = OpenAiSiwcAccessTokenProvider { "oauth-access" },
            modelsUrl = "http://127.0.0.1:${server.address.port}/v1/models",
            httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
        )

        val result = client.list(Clock.System.now() + 5.seconds)

        assertEquals(listOf("gpt-visible", "gpt-second"), result.models.map { it.id })
        assertEquals(listOf("Visible", "Second"), result.models.map { it.displayName })
    }

    @Test
    fun `maps model endpoint status without hiding authentication and quota failures`() {
        assertEquals(
            AiCallFailure.Kind.DEADLINE_EXCEEDED,
            OpenAiSiwcException("deadline_exceeded", "elapsed").toAiCallException().failure.kind,
        )
        assertEquals(
            AiCallFailure.Kind.AUTHENTICATION,
            OpenAiSiwcException("model_catalog_failed", "failed", status = 401).toAiCallException().failure.kind,
        )
        assertEquals(
            AiCallFailure.Kind.ACCESS_DENIED,
            OpenAiSiwcException("model_catalog_failed", "failed", status = 403).toAiCallException().failure.kind,
        )
        assertEquals(
            AiCallFailure.Kind.RATE_LIMITED,
            OpenAiSiwcException("model_catalog_failed", "failed", status = 429).toAiCallException().failure.kind,
        )
        assertEquals(
            AiCallFailure.Kind.TRANSPORT,
            OpenAiSiwcException("refresh_not_ready", "wait", retryable = true).toAiCallException().failure.kind,
        )
    }

    @Test
    fun `preserves an HTTP authentication status even when the error body is not JSON`() = runBlocking {
        val client = OpenAiSiwcModelsClient(
            accessTokens = OpenAiSiwcAccessTokenProvider { "oauth-access" },
            modelsUrl = "http://127.0.0.1:${server.address.port}/v1/unauthorized",
            httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
        )

        val error = assertFailsWith<OpenAiSiwcException> {
            client.list(Clock.System.now() + 5.seconds)
        }

        assertEquals(401, error.status)
        assertEquals(AiCallFailure.Kind.AUTHENTICATION, error.toAiCallException().failure.kind)
    }

    private fun models(exchange: HttpExchange) {
        assertEquals("GET", exchange.requestMethod)
        assertEquals("Bearer oauth-access", exchange.requestHeaders.getFirst("Authorization"))
        exchange.json(200, """{"models":[
            {"slug":"gpt-visible","display_name":"Visible","visibility":"list"},
            {"slug":"gpt-hidden","display_name":"Hidden","visibility":"hidden"},
            {"slug":"gpt-second","display_name":"Second","visibility":"list"}
        ]}""")
    }

    private fun HttpExchange.json(status: Int, value: String) {
        val body = value.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
        close()
    }
}
