package com.gromozeka.infrastructure.ai.openai.siwc

import com.gromozeka.domain.model.ai.AiCallFailure
import com.gromozeka.infrastructure.ai.runtime.credentialFingerprint
import com.gromozeka.infrastructure.ai.openai.OpenAiResponsesStreamException
import com.openai.core.http.Headers
import com.openai.errors.UnauthorizedException
import com.openai.models.ErrorObject
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
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class OpenAiSiwcModelsClientTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/models", ::models)
        createContext("/v1/unauthorized") { exchange ->
            exchange.json(401, "not-json")
        }
        createContext("/v1/rejected") { exchange ->
            exchange.responseHeaders.set("x-request-id", "req_models-1")
            exchange.json(401, """{"error":{"code":"subscription_sharing_invalid_user","param":"account"}}""")
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

    @Test
    fun `preserves resource diagnostics and reports the rejected token`() = runBlocking {
        val tokens = RecordingAccessTokens()
        val client = OpenAiSiwcModelsClient(
            accessTokens = tokens,
            modelsUrl = "http://127.0.0.1:${server.address.port}/v1/rejected",
            httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
        )

        val error = assertFailsWith<OpenAiSiwcException> {
            client.list(Clock.System.now() + 5.seconds)
        }

        assertEquals("subscription_sharing_invalid_user", error.code)
        assertEquals("subscription_sharing_invalid_user", error.providerCode)
        assertEquals("req_models-1", error.requestId)
        assertEquals(credentialFingerprint("oauth-access"), tokens.rejectedFingerprint)
        assertEquals("account", tokens.rejection?.parameter)
        val failure = error.toAiCallException().failure
        assertEquals(AiCallFailure.Outcome.REJECTED, failure.outcome)
        assertEquals(1, failure.attempts)
        assertEquals("subscription_sharing_invalid_user", failure.providerErrorCode)
        assertEquals("account", failure.providerErrorParameter)
        assertEquals("req_models-1", failure.providerRequestId)
    }

    @Test
    fun `extracts safe diagnostics from Responses SDK failures`() {
        val failure = UnauthorizedException.builder()
            .headers(Headers.builder().put("x-request-id", "req_responses-1").build())
            .error(ErrorObject.builder()
                .code("subscription_sharing_invalid_user")
                .message("private provider message")
                .param("account")
                .type("authentication_error")
                .build())
            .build()

        val error = assertNotNull(failure.openAiSiwcResourceError(401))

        assertEquals(401, error.status)
        assertEquals("subscription_sharing_invalid_user", error.code)
        assertEquals("account", error.parameter)
        assertEquals("req_responses-1", error.requestId)

        val streamError = assertNotNull(OpenAiResponsesStreamException(
            "subscription_sharing_unsupported_capability",
            "input[0].tools",
        ).openAiSiwcResourceError(200, "req_stream-1"))
        assertEquals("subscription_sharing_unsupported_capability", streamError.code)
        assertEquals("input[0].tools", streamError.parameter)
        assertEquals("req_stream-1", streamError.requestId)
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

    private class RecordingAccessTokens : OpenAiSiwcAccessTokenProvider {
        var rejectedFingerprint: String? = null
        var rejection: OpenAiSiwcResourceError? = null

        override suspend fun validAccessToken(deadlineNanos: Long): String = "oauth-access"

        override fun resourceRejectedAccessToken(
            credentialFingerprint: String,
            error: OpenAiSiwcResourceError,
            deadlineNanos: Long,
        ) {
            rejectedFingerprint = credentialFingerprint
            rejection = error
        }
    }
}
