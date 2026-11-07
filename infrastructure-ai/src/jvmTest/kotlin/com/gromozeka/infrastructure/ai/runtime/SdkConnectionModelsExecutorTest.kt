package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.openai.OpenAiSdkClientFactory
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SdkConnectionModelsExecutorTest {
    @Test
    fun `three gateway connection kinds use exact models prefix and preserve metadata without any model spec`() = runBlocking {
        for (kind in KINDS) for (suffix in listOf("", "/")) {
            SdkHttpTestServer(MODELS).use { server ->
                val result = SdkConnectionModelsExecutor(Sources()).listModels(connection(kind, server.origin + "/api/llm" + suffix), deadline())
                assertEquals(listOf(AiModelCandidate("account/exact-opaque-id", "model", 123, "account")), result.models)
                val sent = server.requests.single()
                assertEquals("GET", sent.method); assertEquals("/api/llm/models", sent.target); assertEquals("", sent.body)
                assertEquals(listOf("Bearer $TOKEN"), sent.headers["authorization"]); assertFalse("x-api-key" in sent.headers)
                assertEquals(listOf("Model-list-fixture"), sent.headers["user-agent"])
                assertEquals(listOf("false"), sent.headers["x-tfy-strict-openai"])
                val encoded = Json.encodeToString(result)
                assertFalse(encoded.contains("capabilities")); assertFalse(encoded.contains("limits")); assertFalse(encoded.contains("private-extra"))
            }
        }
    }

    @Test
    fun `disabled unsupported and implicit endpoints make no HTTP and do not read a secret`() = runBlocking {
        val source = Sources()
        val executor = SdkConnectionModelsExecutor(source)
        SdkHttpTestServer(MODELS).use { server ->
            val configured = connection(AiConnection.Kind.OPENAI_API, server.origin) as AiConnection.OpenAiApi
            for (invalid in listOf(configured.copy(enabled = false), configured.copy(baseUrl = null),
                AiConnection.OpenAiSubscription(AiConnection.Id("subscription"), "Subscription", enabled = true))) {
                assertFailsWith<IllegalArgumentException> { executor.listModels(invalid, deadline()) }
            }
            assertEquals(0, source.reads.get()); assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `401 403 429 and 503 Retry-After zero are single GET failures without redirect or opaque error leakage`() = runBlocking {
        for (status in listOf(401, 403, 429, 503, 307)) {
            val reply = SdkHttpTestServer.Reply("""{"error":"private-discovery-error-$TOKEN"}""", status,
                mapOf("Retry-After" to "0", "Location" to "/other-models", "x-tfy-resolved-model" to "private-discovery-header"))
            SdkHttpTestServer(listOf(reply)).use { server ->
                val error = assertFailsWith<AiCallException> {
                    SdkConnectionModelsExecutor(Sources()).listModels(connection(AiConnection.Kind.OPENAI_API, server.origin), deadline())
                }
                assertEquals(status, error.failure.httpStatus); assertEquals(1, error.failure.attempts)
                assertEquals(when (status) { 401 -> AiCallFailure.Kind.AUTHENTICATION; 403 -> AiCallFailure.Kind.ACCESS_DENIED;
                    429 -> AiCallFailure.Kind.RATE_LIMITED; else -> AiCallFailure.Kind.HTTP_ERROR }, error.failure.kind)
                assertEquals(1, server.requests.size)
                assertFalse(error.stackTraceToString().contains("private-discovery")); assertFalse(error.stackTraceToString().contains(TOKEN))
                assertNull(error.cause); assertTrue(error.suppressed.isEmpty())
            }
        }
    }

    @Test
    fun `malformed duplicated paginated or oversized lists fail as a whole without guessed defaults`() = runBlocking {
        val bodies = listOf("private-discovery-invalid", "{}", "[]", """{"object":"list","data":[{}]}""",
            """{"object":"list","data":[{"id":"same"},{"id":"same"}]}""",
            """{"object":"list","data":[{"id":"route","created":"123"}]}""",
            """{"object":"list","data":[{"id":"route","created":-1}]}""",
            """{"object":"list","data":[{"id":"route","owned_by":42}]}""",
            """{"object":"list","data":[],"has_more":true}""", "x".repeat(4 * 1024 * 1024 + 1))
        for (body in bodies) SdkHttpTestServer(body).use { server ->
            val error = assertFailsWith<AiCallException> {
                SdkConnectionModelsExecutor(Sources()).listModels(connection(AiConnection.Kind.OPENAI_API, server.origin), deadline())
            }
            assertEquals(AiCallFailure.Kind.INVALID_RESPONSE, error.failure.kind)
            assertEquals(1, error.failure.attempts); assertEquals(1, server.requests.size)
            assertFalse(error.stackTraceToString().contains("private-discovery-invalid"))
        }
        SdkHttpTestServer("""{"object":"list","data":[{"id":" provider/custom-id "}],"has_more":false}""").use { server ->
            val result = SdkConnectionModelsExecutor(Sources()).listModels(connection(AiConnection.Kind.OPENAI_API, server.origin), deadline())
            assertEquals(AiModelCandidate(" provider/custom-id "), result.models.single())
        }
        SdkHttpTestServer("""{"object":"list","data":[]}""").use { server ->
            assertTrue(SdkConnectionModelsExecutor(Sources()).listModels(connection(AiConnection.Kind.OPENAI_API, server.origin), deadline()).models.isEmpty())
        }
    }

    @Test
    fun `credential source rotates between inspections and native Anthropic auth is not silently changed`() = runBlocking {
        val file = Files.createTempFile("grz-models-token-", ".txt")
        try {
            Files.writeString(file, TOKEN)
            SdkHttpTestServer(MODELS, MODELS).use { server ->
                val source = Sources { Files.readString(file) }
                val executor = SdkConnectionModelsExecutor(source)
                val connection = (connection(AiConnection.Kind.ANTHROPIC_API, server.origin) as AiConnection.AnthropicApi)
                    .copy(apiKey = SecretRef.File(file.toString()), httpSettings = settings().copy(authentication = AiHttpSettings.Authentication.PROVIDER_DEFAULT))
                executor.listModels(connection, deadline())
                Files.writeString(file, "synthetic-models-rotated")
                executor.listModels(connection, deadline())
                Files.delete(file)
                val error = assertFailsWith<AiCallException> { executor.listModels(connection, deadline()) }
                assertEquals(AiCallFailure.Kind.CONFIGURATION, error.failure.kind)
                assertEquals(0, error.failure.attempts)
                assertEquals(listOf(TOKEN, "synthetic-models-rotated"), server.requests.map { it.headers.getValue("x-api-key").single() })
                assertTrue(server.requests.all { "authorization" !in it.headers })
                assertEquals(3, source.reads.get())
            }
        } finally { Files.deleteIfExists(file) }
    }

    @Test
    fun `discovery uses additional CA and exact proxy policy without TLS bypass`() = runBlocking {
        SdkTlsTestIdentity().use { identity ->
            SdkHttpTestServer(listOf(SdkHttpTestServer.Reply(MODELS)), identity.context).use { server ->
                val connection = (connection(AiConnection.Kind.OPENAI_API, server.origin) as AiConnection.OpenAiApi)
                    .copy(httpSettings = settings().copy(caBundlePath = identity.caBundle.toString()))
                assertEquals(1, SdkConnectionModelsExecutor(Sources()).listModels(connection, deadline()).models.size)
                assertEquals(AiCallFailure.Kind.TLS, assertFailsWith<AiCallException> {
                    SdkConnectionModelsExecutor(Sources()).listModels(connection.copy(httpSettings = settings()), deadline())
                }.failure.kind)
                assertEquals(1, server.requests.size)
            }
        }
        SdkHttpTestServer(MODELS).use { proxy ->
            val connection = (connection(AiConnection.Kind.OPENAI_API, "http://models.invalid/api/llm") as AiConnection.OpenAiApi)
                .copy(httpSettings = settings().copy(proxyUrl = proxy.origin, noProxy = emptyList()))
            SdkConnectionModelsExecutor(Sources()).listModels(connection, deadline())
            assertEquals("http://models.invalid/api/llm/models", proxy.requests.single().target)
        }
    }

    @Test
    fun `cancellation and deadline close a real GET socket while reading its body`() = runBlocking {
        for (expire in listOf(false, true)) StalledServer(true).use { server ->
            val result = async(Dispatchers.Default) {
                try { SdkConnectionModelsExecutor(Sources()).listModels(connection(AiConnection.Kind.OPENAI_API, server.origin),
                    if (expire) Clock.System.now() + 500.milliseconds else deadline()); null }
                catch (error: AiCallException) { error.failure }
            }
            withContext(Dispatchers.IO) { assertTrue(server.started.await(3, TimeUnit.SECONDS)) }
            if (expire) assertEquals(AiCallFailure.Kind.DEADLINE_EXCEEDED, withTimeout(2000) { result.await() }?.kind)
            else withTimeout(2000) { result.cancelAndJoin() }
            assertEquals(-1, withContext(Dispatchers.IO) { server.peerClosed.get(2, TimeUnit.SECONDS) })
            assertEquals(1, server.requests.get())
        }
    }

    @Test
    fun `models inspection shares the inference gate and expired queueing starts no GET`() = runBlocking {
        SdkHttpTestServer(MODELS).use { server ->
            val connection = (connection(AiConnection.Kind.OPENAI_API, server.origin) as AiConnection.OpenAiApi)
                .copy(httpSettings = settings().copy(maxConcurrentCalls = 1))
            val source = Sources()
            val entered = CompletableDeferred<Unit>()
            val inference = SdkHttpCallExecutor(connection, 5) { OpenAiSdkClientFactory(source).createInferenceClient(connection, it) }
            val held = launch(Dispatchers.Default) { inference.call<Unit>(AiRuntimeOptions()) { _, _ -> entered.complete(Unit); CompletableFuture<Unit>() } }
            try {
                entered.await()
                val error = assertFailsWith<AiCallException> {
                    SdkConnectionModelsExecutor(source).listModels(connection, Clock.System.now() + 100.milliseconds)
                }
                assertEquals(AiCallFailure.Outcome.NOT_STARTED, error.failure.outcome); assertEquals(0, error.failure.attempts)
                assertTrue(server.requests.isEmpty()); assertEquals(1, source.reads.get())
            } finally { held.cancelAndJoin() }
            SdkConnectionModelsExecutor(source).listModels(connection, deadline())
            assertEquals(1, server.requests.size)
        }
    }

    private fun connection(kind: AiConnection.Kind, url: String): AiConnection {
        val id = AiConnection.Id(UUID.randomUUID().toString())
        return when (kind) {
            AiConnection.Kind.OPENAI_API -> AiConnection.OpenAiApi(id, "Models", enabled = true, baseUrl = url, apiKey = SecretRef.Inline(TOKEN), httpSettings = settings())
            AiConnection.Kind.OPENAI_COMPATIBLE -> AiConnection.OpenAiCompatible(id, "Models", enabled = true, baseUrl = url, apiKey = SecretRef.Inline(TOKEN), httpSettings = settings())
            AiConnection.Kind.ANTHROPIC_API -> AiConnection.AnthropicApi(id, "Models", enabled = true, baseUrl = url, apiKey = SecretRef.Inline(TOKEN), httpSettings = settings())
            else -> error("Unsupported fixture")
        }
    }
    private fun deadline() = Clock.System.now() + 5.seconds
    private fun settings() = AiHttpSettings(authentication = AiHttpSettings.Authentication.BEARER, noProxy = listOf("*"),
        extraHeaders = mapOf("User-Agent" to "Model-list-fixture", "x-tfy-strict-openai" to "false"))
    private class Sources(private val resolve: () -> String = { TOKEN }) : SettingsProvider {
        val reads = AtomicInteger()
        override val userProfile: UserProfile get() = error("Unused")
        override val userDeviceSettings: UserDeviceSettings get() = error("Unused")
        override val mode: AppMode get() = error("Unused")
        override val homeDirectory: String get() = error("Unused")
        override fun resolveSecret(secretRef: SecretRef?): String { reads.incrementAndGet(); return resolve() }
    }
    private companion object {
        const val TOKEN = "synthetic-models-token"
        const val MODELS = """{"object":"list","data":[{"id":"account/exact-opaque-id","object":"model","created":123,"owned_by":"account","capabilities":["private-extra"],"limits":{"context":999}}]}"""
        val KINDS = listOf(AiConnection.Kind.OPENAI_API, AiConnection.Kind.OPENAI_COMPATIBLE, AiConnection.Kind.ANTHROPIC_API)
    }
}
