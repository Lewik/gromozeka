package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.anthropic.AnthropicSdkRuntimeBackend
import com.gromozeka.infrastructure.ai.openai.OpenAiSdkClientFactory
import com.gromozeka.infrastructure.ai.openai.OpenAiSdkRuntimeBackend
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Instant as JavaInstant
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class SdkGatewaySettingsTest {
    @Test
    fun `actual SDK runtimes send exact gateway prefix one Bearer token and configured headers`() = runBlocking {
        for (api in Api.entries) for (suffix in listOf("", "/")) {
            SdkHttpTestServer(api.reply()).use { server ->
                val settings = AiHttpSettings(authentication = AiHttpSettings.Authentication.BEARER, noProxy = listOf("*"),
                    extraHeaders = mapOf("user-agent" to "Gromozeka-Gateway-Fixture", "x-tfy-strict-openai" to "false",
                        "X-TFY-METADATA" to """{"application":"fixture"}"""))
                val connection = api.connection(server.origin + "/api/llm" + suffix, SecretRef.Inline(TOKEN), settings)
                assertEquals(AiStepOutcome.COMPLETE, runtime(connection).call(request()).outcome)
                val sent = server.requests.single()
                assertEquals("POST", sent.method)
                assertEquals("/api/llm${api.path}", sent.target)
                assertEquals(listOf("Bearer $TOKEN"), sent.headers["authorization"])
                assertFalse("x-api-key" in sent.headers)
                assertEquals(listOf("Gromozeka-Gateway-Fixture"), sent.headers["user-agent"])
                assertEquals(listOf("false"), sent.headers["x-tfy-strict-openai"])
                assertEquals(listOf("""{"application":"fixture"}"""), sent.headers["x-tfy-metadata"])
                assertEquals(MODEL, Json.parseToJsonElement(sent.body).jsonObject.getValue("model").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `native Anthropic auth keeps x-api-key without adding ambient Bearer credentials`() = runBlocking {
        SdkHttpTestServer(Api.MESSAGES.reply()).use { server ->
            val connection = Api.MESSAGES.connection(server.origin + "/api/llm", SecretRef.Inline(TOKEN), AiHttpSettings(noProxy = listOf("*")))
            runtime(connection).call(request())
            assertEquals(listOf(TOKEN), server.requests.single().headers["x-api-key"])
            assertFalse("authorization" in server.requests.single().headers)
        }
    }

    @Test
    fun `missing selected credentials never fall back to another token source or make HTTP requests`() = runBlocking {
        for (api in Api.entries) {
            SdkHttpTestServer(api.reply()).use { server ->
                for (ref in listOf(null, SecretRef.EnvironmentVariable("EXPLICIT_MISSING"))) {
                    val sources = Sources()
                    val failure = assertFailsWith<AiCallException> {
                        runtime(api.connection(server.origin + "/api/llm", ref), sources).call(request())
                    }
                    assertEquals(AiCallFailure.Kind.CONFIGURATION, failure.failure.kind)
                    assertEquals(AiCallFailure.Outcome.NOT_STARTED, failure.failure.outcome)
                    assertEquals(0, failure.failure.attempts)
                    assertFalse(failure.toString().contains("ambient-native-secret"))
                    assertEquals<List<SecretRef?>>(if (ref == null) emptyList() else listOf(ref), sources.reads)
                    assertTrue(server.requests.isEmpty())
                }
            }
        }
        val sources = Sources()
        val factory = OpenAiSdkClientFactory(sources)
        val native = AiConnection.OpenAiApi(AiConnection.Id("native"), "Native")
        assertEquals("ambient-native-secret", factory.resolveApiKey(native))
        assertEquals<List<SecretRef?>>(listOf(SecretRef.EnvironmentVariable("OPENAI_API_KEY")), sources.reads)
    }

    @Test
    fun `same runtime rereads file credentials for the next logical call and never reuses a deleted token`() = runBlocking {
        val directory = Files.createTempDirectory("grz-sdk-token-")
        try {
            for (api in Api.entries) {
                val file = directory.resolve("token")
                Files.writeString(file, "$TOKEN\n")
                SdkHttpTestServer(api.reply(), api.reply()).use { server ->
                    val sources = Sources()
                    val connection = api.connection(server.origin + "/api/llm", SecretRef.File(file.toString()))
                    val runtime = runtime(connection, sources)
                    runtime.call(request())
                    Files.writeString(file, "synthetic-rotated-token\n")
                    runtime.call(request())
                    Files.delete(file)
                    assertFails { runtime.call(request()) }
                    assertEquals(listOf("Bearer $TOKEN", "Bearer synthetic-rotated-token"), server.requests.map { it.headers.getValue("authorization").single() })
                    assertEquals<List<SecretRef?>>(List(3) { SecretRef.File(file.toString()) }, sources.reads)
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `401 and 403 remain distinct and withhold token response body and private diagnostic headers`() = runBlocking {
        for (api in Api.entries) for (status in listOf(401, 403)) {
            val reply = SdkHttpTestServer.Reply("""{"error":{"type":"auth_error","message":"$TOKEN private-route-marker","code":"bad_token"}}""", status,
                mapOf("x-tfy-resolved-model" to "private-route-marker", "x-tfy-applied-configurations" to "private-config-marker"))
            SdkHttpTestServer(listOf(reply)).use { server ->
                val error = assertFailsWith<AiCallException> {
                    runtime(api.connection(server.origin + "/api/llm", SecretRef.Inline(TOKEN))).call(request())
                }
                assertEquals(status, error.failure.httpStatus)
                assertContains(error.message.orEmpty(), if (status == 401) "authentication" else "denied access")
                assertNull(error.cause)
                assertTrue(error.suppressed.isEmpty())
                val rendered = error.stackTraceToString()
                for (privateValue in listOf(TOKEN, "private-route-marker", "private-config-marker")) assertFalse(rendered.contains(privateValue))
                assertEquals(1, server.requests.size, "Authentication failures must not refresh or retry")
            }
        }
    }

    @Test
    fun `configured HTTP proxy receives actual SDK requests without resolving the gateway locally`() = runBlocking {
        for (api in Api.entries) {
            SdkHttpTestServer(api.reply()).use { proxy ->
                val settings = AiHttpSettings(authentication = AiHttpSettings.Authentication.BEARER, proxyUrl = proxy.origin)
                runtime(api.connection("http://gateway.invalid/api/llm", SecretRef.Inline(TOKEN), settings)).call(request())
                val sent = proxy.requests.single()
                assertEquals("http://gateway.invalid/api/llm${api.path}", sent.target)
                assertEquals(listOf("Bearer $TOKEN"), sent.headers["authorization"])
            }
        }
    }

    @Test
    fun `noProxy actually bypasses a configured proxy for the inference endpoint`() = runBlocking {
        for (api in Api.entries) {
            SdkHttpTestServer(api.reply()).use { proxy ->
                SdkHttpTestServer(api.reply()).use { endpoint ->
                    val settings = AiHttpSettings(authentication = AiHttpSettings.Authentication.BEARER, proxyUrl = proxy.origin, noProxy = listOf("127.0.0.1"))
                    runtime(api.connection(endpoint.origin + "/api/llm", SecretRef.Inline(TOKEN), settings)).call(request())
                    assertEquals(1, endpoint.requests.size)
                    assertTrue(proxy.requests.isEmpty())
                }
            }
        }
    }

    @Test
    fun `additional CA works with real HTTPS while default trust and hostname verification remain enabled`() = runBlocking {
        SdkTlsTestIdentity().use { identity ->
            val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().single()
            val tlsSettings = AiHttpSettings(authentication = AiHttpSettings.Authentication.BEARER, caBundlePath = identity.caBundle.toString(), noProxy = listOf("*"))
            val combined = SdkHttpSupport.tls(tlsSettings)!!.trustManager.acceptedIssuers.toSet()
            assertTrue(combined.containsAll(system.acceptedIssuers.toSet()))
            assertTrue(combined.size > system.acceptedIssuers.toSet().size)
            for (api in Api.entries) {
                SdkHttpTestServer(listOf(SdkHttpTestServer.Reply(api.reply())), identity.context).use { endpoint ->
                    val url = endpoint.origin + "/api/llm"
                    assertEquals(AiStepOutcome.COMPLETE, runtime(api.connection(url, SecretRef.Inline(TOKEN), tlsSettings)).call(request()).outcome)
                    val untrusted = assertFailsWith<AiCallException> {
                        runtime(api.connection(url, SecretRef.Inline(TOKEN), tlsSettings.copy(caBundlePath = null))).call(request())
                    }
                    assertContains(untrusted.message.orEmpty(), "TLS")
                    val wrongHost = assertFailsWith<AiCallException> {
                        runtime(api.connection(url.replace("127.0.0.1", "localhost"), SecretRef.Inline(TOKEN), tlsSettings)).call(request())
                    }
                    assertContains(wrongHost.message.orEmpty(), "TLS")
                    assertEquals(1, endpoint.requests.size, "TLS failures must happen before credentials or payload are sent")
                }
            }
        }
    }

    @Test
    fun `missing malformed or empty CA bundle fails instead of disabling TLS or falling back`() {
        val directory = Files.createTempDirectory("grz-sdk-ca-")
        try {
            val missing = directory.resolve("missing.pem")
            assertFailsWith<IllegalStateException> { SdkHttpSupport.tls(AiHttpSettings(caBundlePath = missing.toString())) }
            val file = directory.resolve("ca.pem")
            for (body in listOf("", "private-invalid-ca-marker")) {
                Files.writeString(file, body)
                val error = assertFailsWith<IllegalArgumentException> { SdkHttpSupport.tls(AiHttpSettings(caBundlePath = file.toString())) }
                assertFalse(error.toString().contains("private-invalid-ca-marker"))
            }
            assertFailsWith<IllegalArgumentException> { SdkHttpSupport.tls(AiHttpSettings(caBundlePath = "relative.pem")) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `JWT expiry is only an unverified hint and opaque tokens remain untouched`() = runBlocking {
        fun part(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val jwt = part("""{"alg":"HS256","kid":"private-kid"}""") + "." + part("""{"exp":1,"sub":"private-subject"}""") + ".not-a-valid-signature"
        assertEquals(JavaInstant.ofEpochSecond(1), SdkHttpSupport.unverifiedJwtExpiry(jwt))
        for (opaque in listOf("TFY_PAT_opaque", "opaque.with.dots", "a.b.c", "bad", part("{}") + "." + part("{\"exp\":1}") + ".sig")) {
            assertNull(SdkHttpSupport.unverifiedJwtExpiry(opaque))
        }
        SdkHttpTestServer(Api.CHAT.reply()).use { endpoint ->
            assertEquals(AiStepOutcome.COMPLETE, runtime(Api.CHAT.connection(endpoint.origin + "/api/llm", SecretRef.Inline(jwt))).call(request()).outcome)
            assertEquals(listOf("Bearer $jwt"), endpoint.requests.single().headers["authorization"])
        }
    }

    private fun runtime(connection: AiConnection, source: Sources = Sources()): AiRuntime {
        val backend = if (connection.kind == AiConnection.Kind.ANTHROPIC_API) AnthropicSdkRuntimeBackend(source, admission = TestAiAdmission())
            else OpenAiSdkRuntimeBackend(OpenAiSdkClientFactory(source), admission = TestAiAdmission())
        val model = AiModelConfiguration(AiModelConfiguration.Id("synthetic-model"), connection.id, MODEL, "Synthetic")
        val spec = AiModelSpec(MODEL, connection.kind.provider, setOf(AiModelCapability.TEXT_GENERATION),
            AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(32_000, 8192)))
        return RoutingAiRuntimeProvider(listOf(backend)).getRuntime(ResolvedAiRuntime(connection, model, spec), null)
    }

    private fun request() = AiRuntimeRequest(listOf("Synthetic system instruction"), listOf(
        Conversation.Message(Conversation.Message.Id("one"), Conversation.Id("synthetic"), role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Hello")), createdAt = Instant.fromEpochMilliseconds(0))),
        options = AiRuntimeOptions(maxOutputTokens = 128))

    private class Sources : SettingsProvider {
        val reads = mutableListOf<SecretRef?>()
        override val userProfile: UserProfile get() = error("Not used")
        override val userDeviceSettings: UserDeviceSettings get() = error("Not used")
        override val mode: AppMode get() = error("Not used")
        override val homeDirectory: String get() = error("Not used")
        override fun resolveSecret(secretRef: SecretRef?): String? {
            reads += secretRef
            return when (secretRef) {
                is SecretRef.Inline -> secretRef.value
                is SecretRef.EnvironmentVariable -> if (secretRef.name in setOf("OPENAI_API_KEY", "ANTHROPIC_API_KEY")) "ambient-native-secret" else null
                is SecretRef.File -> Files.readString(Path.of(secretRef.path)).trim()
                null -> null
            }
        }
    }

    private enum class Api(val path: String) {
        CHAT("/chat/completions"), RESPONSES("/responses"), MESSAGES("/v1/messages");
        fun connection(url: String, source: SecretRef?, settings: AiHttpSettings = AiHttpSettings(authentication = AiHttpSettings.Authentication.BEARER, noProxy = listOf("*"))): AiConnection = when (this) {
            CHAT -> AiConnection.OpenAiCompatible(AiConnection.Id("chat"), "Synthetic", baseUrl = url, apiKey = source, httpSettings = settings)
            RESPONSES -> AiConnection.OpenAiApi(AiConnection.Id("responses"), "Synthetic", baseUrl = url, apiKey = source, httpSettings = settings)
            MESSAGES -> AiConnection.AnthropicApi(AiConnection.Id("messages"), "Synthetic", baseUrl = url, apiKey = source, httpSettings = settings)
        }
        fun reply(): String = when (this) {
            CHAT -> """{"id":"fixture","object":"chat.completion","created":1,"model":"$MODEL","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}]}"""
            RESPONSES -> """{"id":"fixture","object":"response","created_at":1,"status":"completed","model":"$MODEL","output":[{"type":"message","id":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"OK","annotations":[]}]}]}"""
            MESSAGES -> """{"id":"fixture","type":"message","role":"assistant","model":"$MODEL","content":[{"type":"text","text":"OK"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""
        }
    }
    private companion object { const val TOKEN = "synthetic-gateway-secret"; const val MODEL = "gateway-account/exact-model-id" }
}
