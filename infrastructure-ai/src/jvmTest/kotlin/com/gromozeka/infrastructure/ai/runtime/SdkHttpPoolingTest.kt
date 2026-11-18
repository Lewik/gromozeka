package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.anthropic.AnthropicSdkRuntimeBackend
import com.gromozeka.infrastructure.ai.openai.OpenAiSdkClientFactory
import com.gromozeka.infrastructure.ai.openai.OpenAiSdkRuntimeBackend
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class SdkHttpPoolingTest {
    private val httpClients = SdkHttpClientPool()
    @AfterTest
    fun closeHttpClients() = httpClients.close()

    @Test
    fun `gateway reuses HTTP and HTTPS across runtime instances without mixing conversations`() = runBlocking {
        SdkTlsTestIdentity().use { identity ->
            for (api in Api.entries) for (tls in listOf(false, true)) {
                SdkHttpTestServer(List(3) { SdkHttpTestServer.Reply(api.reply) }, if (tls) identity.context else null).use { server ->
                    val settings = settings().copy(caBundlePath = if (tls) identity.caBundle.toString() else null)
                    val connection = api.connection(server.origin + "/api/llm", settings = settings)
                    repeat(3) { index ->
                        assertEquals(AiStepOutcome.COMPLETE, runtime(connection).call(request("thread-$index")).outcome)
                    }
                    assertEquals(1, server.requests.map { it.remoteAddress }.distinct().size, "$api tls=$tls")
                    server.requests.forEachIndexed { index, sent ->
                        assertEquals("/api/llm${api.path}", sent.target)
                        assertEquals(listOf("Bearer $TOKEN"), sent.headers["authorization"])
                        assertEquals(listOf("false"), sent.headers["x-tfy-strict-openai"])
                        assertEquals(listOf("""{"application":"pool-fixture"}"""), sent.headers["x-tfy-metadata"])
                        assertEquals(MODEL, Json.parseToJsonElement(sent.body).jsonObject.getValue("model").jsonPrimitive.content)
                        assertContains(sent.body, "thread-$index")
                        (0 until index).forEach { previous -> assertFalse(sent.body.contains("thread-$previous")) }
                    }
                }
            }
        }
    }

    @Test
    fun `token and metadata rotate per logical call while the socket remains reusable`() = runBlocking {
        val directory = Files.createTempDirectory("grz-http-pool-token-")
        try {
            for (api in Api.entries) SdkHttpTestServer(api.reply, api.reply).use { server ->
                val token = directory.resolve("token")
                Files.writeString(token, TOKEN)
                val first = api.connection(server.origin + "/api/llm", token = SecretRef.File(token.toString()))
                runtime(first).call(request())
                Files.writeString(token, "synthetic-rotated-token")
                val updated = settings().copy(extraHeaders = mapOf("X-TFY-METADATA" to """{"application":"updated"}"""))
                runtime(api.connection(server.origin + "/api/llm", settings = updated, token = SecretRef.File(token.toString()))).call(request())
                Files.delete(token)
                val error = assertFailsWith<AiCallException> { runtime(first).call(request()) }
                assertEquals(AiCallFailure.Outcome.NOT_STARTED, error.failure.outcome)
                assertEquals(1, server.requests.map { it.remoteAddress }.distinct().size)
                assertEquals(listOf("Bearer $TOKEN", "Bearer synthetic-rotated-token"), server.requests.map { it.headers.getValue("authorization").single() })
                assertEquals(listOf("""{"application":"updated"}"""), server.requests.last().headers["x-tfy-metadata"])
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `concurrent calls on one connection keep their own credentials and metadata`() = runBlocking {
        for (api in Api.entries) {
            val started = CountDownLatch(3)
            val release = CountDownLatch(1)
            SdkHttpTestServer(List(4) { SdkHttpTestServer.Reply(api.reply) }, concurrentRequests = 3,
                beforeReply = { sent -> if (!sent.body.contains("afterwards")) {
                    started.countDown(); check(release.await(10, TimeUnit.SECONDS))
                } }).use { server ->
                val calls = List(3) { index -> async(Dispatchers.Default) {
                    val ownSettings = settings().copy(extraHeaders = mapOf("X-TFY-METADATA" to """{"request":"call-$index"}"""))
                    val connection = api.connection(server.origin, settings = ownSettings, token = SecretRef.Inline("synthetic-auth-$index"))
                    runtime(connection).call(request("call-$index"))
                } }
                try {
                    await(started)
                    release.countDown()
                    calls.awaitAll().forEach { assertEquals(AiStepOutcome.COMPLETE, it.outcome) }
                    val first = server.requests
                    repeat(3) { index ->
                        val sent = first.single { it.body.contains("call-$index") }
                        assertEquals(listOf("Bearer synthetic-auth-$index"), sent.headers["authorization"])
                        assertEquals(listOf("""{"request":"call-$index"}"""), sent.headers["x-tfy-metadata"])
                    }
                    runtime(api.connection(server.origin)).call(request("afterwards"))
                    val last = server.requests.last()
                    assertTrue(last.remoteAddress in first.map { it.remoteAddress })
                    assertEquals(listOf("Bearer $TOKEN"), last.headers["authorization"])
                    assertEquals(listOf("""{"application":"pool-fixture"}"""), last.headers["x-tfy-metadata"])
                } finally { release.countDown(); calls.forEach { it.cancelAndJoin() } }
            }
        }
    }

    @Test
    fun `different configured connections stay isolated and an endpoint change retires the old pool`() = runBlocking {
        for (api in Api.entries) SdkHttpTestServer(List(4) { SdkHttpTestServer.Reply(api.reply) }).use { server ->
            val first = api.connection(server.origin + "/api/llm", id = "first")
            val second = api.connection(server.origin + "/api/llm", id = "second")
            runtime(first).call(request())
            runtime(second).call(request())
            runtime(first).call(request())
            runtime(api.connection(server.origin + "/new-prefix", id = "first")).call(request())
            val addresses = server.requests.map { it.remoteAddress }
            assertEquals(addresses[0], addresses[2])
            assertEquals(3, addresses.distinct().size)
            assertEquals("/new-prefix${api.path}", server.requests.last().target)
        }
    }

    @Test
    fun `updated proxy and noProxy settings change the real route instead of reusing the old client`() = runBlocking {
        for (api in Api.entries) {
            SdkHttpTestServer(api.reply, api.reply).use { direct ->
                SdkHttpTestServer(api.reply).use { proxy ->
                    val proxied = settings().copy(proxyUrl = proxy.origin, noProxy = emptyList())
                    runtime(api.connection(direct.origin + "/api/llm")).call(request())
                    runtime(api.connection(direct.origin + "/api/llm", settings = proxied)).call(request())
                    runtime(api.connection(direct.origin + "/api/llm", settings = proxied.copy(noProxy = listOf("127.0.0.1")))).call(request())
                    assertEquals(2, direct.requests.size)
                    assertEquals(1, proxy.requests.size)
                    assertEquals(direct.origin + "/api/llm${api.path}", proxy.requests.single().target)
                    assertNotEquals(direct.requests[0].remoteAddress, direct.requests[1].remoteAddress)
                }
            }
        }
    }

    @Test
    fun `CA content rotation at the same path cannot reuse a previously trusted TLS socket`() = runBlocking {
        SdkTlsTestIdentity().use { trusted -> SdkTlsTestIdentity().use { other ->
            val file = Files.createTempFile("grz-pool-ca-", ".pem")
            try {
                val original = Files.readAllBytes(trusted.caBundle)
                for (api in Api.entries) SdkHttpTestServer(List(2) { SdkHttpTestServer.Reply(api.reply) }, trusted.context).use { server ->
                    Files.write(file, original)
                    val connection = api.connection(server.origin + "/api/llm", settings = settings().copy(caBundlePath = file.toString()))
                    runtime(connection).call(request())
                    Files.write(file, Files.readAllBytes(other.caBundle))
                    assertEquals(AiCallFailure.Kind.TLS, assertFailsWith<AiCallException> { runtime(connection).call(request()) }.failure.kind)
                    Files.writeString(file, "invalid-synthetic-ca")
                    assertEquals(AiCallFailure.Outcome.NOT_STARTED, assertFailsWith<AiCallException> { runtime(connection).call(request()) }.failure.outcome)
                    Files.delete(file)
                    assertEquals(AiCallFailure.Outcome.NOT_STARTED, assertFailsWith<AiCallException> { runtime(connection).call(request()) }.failure.outcome)
                    Files.write(file, original)
                    runtime(connection).call(request())
                    assertEquals(2, server.requests.size)
                    assertEquals(2, server.requests.map { it.remoteAddress }.distinct().size)
                }
            } finally { Files.deleteIfExists(file) }
        } }
    }

    @Test
    fun `cancelled and expired calls do not close other sessions or poison subsequent requests`() = runBlocking {
        for (api in Api.entries) for (expire in listOf(false, true)) {
            val started = CountDownLatch(2)
            val release = CountDownLatch(1)
            SdkHttpTestServer(List(3) { SdkHttpTestServer.Reply(api.reply) }, concurrentRequests = 2,
                beforeReply = { sent -> if (!sent.body.contains("afterwards")) {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                } }).use { server ->
                val connection = api.connection(server.origin)
                val first = async(Dispatchers.Default) {
                    if (expire) assertFailsWith<AiCallException> { runtime(connection).call(request("first", 1000)) }.failure
                    else { runtime(connection).call(request("first")); null }
                }
                val second = async(Dispatchers.Default) { runtime(connection).call(request("second")) }
                try {
                    await(started)
                    if (expire) {
                        assertEquals(AiCallFailure.Kind.DEADLINE_EXCEEDED, first.await()!!.kind)
                    } else withTimeout(3000) { first.cancelAndJoin() }
                    assertTrue(second.isActive)
                    release.countDown()
                    assertEquals(AiStepOutcome.COMPLETE, second.await().outcome)
                    assertEquals(AiStepOutcome.COMPLETE, runtime(connection).call(request("afterwards")).outcome)
                } finally { release.countDown(); first.cancelAndJoin(); second.cancelAndJoin() }
            }
        }
    }

    @Test
    fun `a shared dispatcher does not add the default five requests per host bottleneck`() = runBlocking {
        val started = CountDownLatch(7)
        val release = CountDownLatch(1)
        SdkHttpTestServer(List(7) { SdkHttpTestServer.Reply(Api.RESPONSES.reply) }, concurrentRequests = 7,
            beforeReply = { started.countDown(); check(release.await(10, TimeUnit.SECONDS)) }).use { server ->
            val connection = Api.RESPONSES.connection(server.origin)
            val calls = List(7) { index -> async(Dispatchers.Default) { runtime(connection).call(request("parallel-$index")) } }
            try {
                await(started)
                release.countDown()
                calls.awaitAll().forEach { assertEquals(AiStepOutcome.COMPLETE, it.outcome) }
                assertEquals(7, server.requests.size)
            } finally { release.countDown(); calls.forEach { it.cancelAndJoin() } }
        }
    }

    @Test
    fun `shutdown closes active and retired sessions including bodies still being parsed`() = runBlocking {
        for (partialBody in listOf(false, true)) SdkHttpClientPool().use { owner ->
            StalledServer(partialBody).use { stalled ->
                SdkHttpTestServer(Api.RESPONSES.reply).use { next ->
                    val first = async(Dispatchers.Default) {
                        assertFailsWith<AiCallException> { runtime(Api.RESPONSES.connection(stalled.origin), owner).call(request()) }
                    }
                    try {
                        await(stalled.started)
                        runtime(Api.RESPONSES.connection(next.origin), owner).call(request())
                        assertTrue(first.isActive)
                        owner.close()
                        assertEquals(-1, withContext(Dispatchers.IO) { stalled.peerClosed.get(3, TimeUnit.SECONDS) })
                        withTimeout(3000) { first.await() }
                        val error = assertFailsWith<AiCallException> { runtime(Api.RESPONSES.connection(next.origin), owner).call(request()) }
                        assertEquals(AiCallFailure.Outcome.NOT_STARTED, error.failure.outcome)
                        assertEquals(1, next.requests.size)
                    } finally { first.cancelAndJoin() }
                }
            }
        }
    }

    @Test
    fun `idle resource expiration uses monotonic time without retiring a recently used connection`() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong()
        SdkHttpClientPool(now::get).use { owner ->
            SdkHttpTestServer(List(4) { SdkHttpTestServer.Reply(Api.RESPONSES.reply) }).use { server ->
                val connection = Api.RESPONSES.connection(server.origin)
                runtime(connection, owner).call(request())
                now.addAndGet(TimeUnit.MINUTES.toNanos(4))
                runtime(connection, owner).call(request())
                now.addAndGet(TimeUnit.MINUTES.toNanos(6))
                runtime(connection, owner).call(request())
                runtime(connection, owner).call(request())
                val addresses = server.requests.map { it.remoteAddress }
                assertEquals(addresses[0], addresses[1])
                assertNotEquals(addresses[1], addresses[2])
                assertEquals(addresses[2], addresses[3])
            }
        }
    }

    @Test
    fun `Spring injects the transport owner and destroys it with its application context`() = runBlocking {
        org.springframework.context.annotation.AnnotationConfigApplicationContext().use { context ->
            context.registerBean(SettingsProvider::class.java, java.util.function.Supplier { Sources })
            context.register(SdkHttpClientPool::class.java, OpenAiSdkClientFactory::class.java)
            context.refresh()
            val owner = context.getBean(SdkHttpClientPool::class.java)
            assertNotNull(context.getBean(OpenAiSdkClientFactory::class.java))
            SdkHttpTestServer(Api.RESPONSES.reply).use { server ->
                val connection = Api.RESPONSES.connection(server.origin)
                runtime(connection, owner).call(request())
                context.close()
                val failure = assertFailsWith<AiCallException> { runtime(connection, owner).call(request()) }.failure
                assertEquals(AiCallFailure.Outcome.NOT_STARTED, failure.outcome)
                assertEquals(1, server.requests.size)
            }
        }
    }

    @Test
    fun `metric events exclude URL content secrets and the URL-bearing OkHttp thread name`() = runBlocking {
        val logger = org.slf4j.LoggerFactory.getLogger(SdkHttpCallMetrics::class.java) as ch.qos.logback.classic.Logger
        val rows = java.util.concurrent.CopyOnWriteArrayList<String>()
        val appender = object : ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
            override fun append(event: ch.qos.logback.classic.spi.ILoggingEvent) {
                assertNull(event.throwableProxy)
                rows += event.threadName + " " + event.formattedMessage
            }
        }.apply { context = logger.loggerContext; start() }
        logger.addAppender(appender)
        try {
            val reply = Api.RESPONSES.reply.replace("OK", "private-response-marker")
            SdkHttpTestServer(reply).use { server ->
                runtime(Api.RESPONSES.connection(server.origin + "/private-route-marker")).call(request("private-request-marker"))
            }
            assertTrue(rows.isNotEmpty())
            for (row in rows) {
                assertTrue(row.startsWith("sdk-http-metrics SDK HTTP "))
                for (marker in listOf("http://", "https://", "127.0.0.1", "private-route-marker", "private-request-marker", "private-response-marker", TOKEN)) {
                    assertFalse(row.contains(marker), "Private data found in metric event")
                }
            }
        } finally { logger.detachAppender(appender); appender.stop() }
    }

    @Test
    fun `synthetic HTTPS benchmark compares per-call pools with a shared pool on long history`() = runBlocking {
        val samples = 20
        val warmups = 3
        val history = List(32) { index ->
            Conversation.Message(Conversation.Message.Id("history-$index"), Conversation.Id("synthetic"),
                role = Conversation.Message.Role.USER,
                content = listOf(Conversation.Message.ContentItem.UserMessage("Synthetic $index " + "x".repeat(4096))),
                createdAt = Instant.fromEpochMilliseconds(index.toLong()))
        }
        val longRequest = request().copy(messages = history)
        SdkTlsTestIdentity().use { identity ->
            for (api in listOf(Api.CHAT, Api.RESPONSES)) {
                SdkHttpTestServer(List(2 * (samples + warmups)) { SdkHttpTestServer.Reply(api.reply) }, identity.context).use { server ->
                    val connection = api.connection(server.origin + "/api/llm", settings = settings().copy(caBundlePath = identity.caBundle.toString()))
                    SdkHttpClientPool().use { shared ->
                        repeat(warmups) {
                            SdkHttpClientPool().use { fresh -> runtime(connection, fresh).call(longRequest) }
                            runtime(connection, shared).call(longRequest)
                        }
                        val freshNanos = mutableListOf<Long>()
                        val reusedNanos = mutableListOf<Long>()
                        val freshSockets = mutableSetOf<java.net.InetSocketAddress>()
                        val reusedSockets = mutableSetOf<java.net.InetSocketAddress>()
                        repeat(samples) {
                            var start = System.nanoTime()
                            SdkHttpClientPool().use { fresh -> runtime(connection, fresh).call(longRequest) }
                            freshNanos += System.nanoTime() - start
                            freshSockets += server.requests.last().remoteAddress
                            start = System.nanoTime()
                            runtime(connection, shared).call(longRequest)
                            reusedNanos += System.nanoTime() - start
                            reusedSockets += server.requests.last().remoteAddress
                        }
                        assertEquals(samples, freshSockets.size)
                        assertEquals(1, reusedSockets.size)
                        val sizes = server.requests.map { it.body.toByteArray(Charsets.UTF_8).size }.distinct()
                        assertEquals(1, sizes.size)
                        fun percentile(values: List<Long>, p: Double) = "%.2f".format(java.util.Locale.ROOT,
                            values.sorted()[((values.size - 1) * p).toInt()] / 1_000_000.0)
                        println("SDK_HTTP_BENCH api=$api samples=$samples request_bytes=${sizes.single()} " +
                            "fresh_sockets=${freshSockets.size} reused_sockets=${reusedSockets.size} " +
                            "fresh_p50_ms=${percentile(freshNanos, 0.5)} fresh_p95_ms=${percentile(freshNanos, 0.95)} " +
                            "reused_p50_ms=${percentile(reusedNanos, 0.5)} reused_p95_ms=${percentile(reusedNanos, 0.95)}")
                    }
                }
            }
        }
    }

    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Expected concurrent HTTP requests to reach the server")
    }

    private fun runtime(connection: AiConnection, owner: SdkHttpClientPool = httpClients): AiRuntime {
        val backend = if (connection.kind == AiConnection.Kind.ANTHROPIC_API)
            AnthropicSdkRuntimeBackend(Sources, owner, TestAiAdmission())
        else OpenAiSdkRuntimeBackend(OpenAiSdkClientFactory(Sources, owner), admission = TestAiAdmission())
        val model = AiModelConfiguration(AiModelConfiguration.Id("pool-model"), connection.id, MODEL, "Synthetic")
        val spec = AiModelSpec(MODEL, connection.kind.provider, setOf(AiModelCapability.TEXT_GENERATION),
            AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(64_000, 8192)))
        return RoutingAiRuntimeProvider(listOf(backend)).getRuntime(ResolvedAiRuntime(connection, model, spec), null)
    }

    private fun request(thread: String = "synthetic", deadlineMillis: Long? = null) = AiRuntimeRequest(listOf("Synthetic system"), listOf(
        Conversation.Message(Conversation.Message.Id(thread), Conversation.Id(thread), role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(thread)), createdAt = Instant.fromEpochMilliseconds(0))),
        options = AiRuntimeOptions(maxOutputTokens = 128, toolContext = mapOf("threadId" to thread),
            executionDeadline = deadlineMillis?.let { Clock.System.now() + it.milliseconds }))

    private object Sources : SettingsProvider {
        override val userProfile: UserProfile get() = error("Not used")
        override val userDeviceSettings: UserDeviceSettings get() = error("Not used")
        override val mode: AppMode get() = error("Not used")
        override val homeDirectory: String get() = error("Not used")
        override fun resolveSecret(secretRef: SecretRef?): String? = when (secretRef) {
            is SecretRef.Inline -> secretRef.value
            is SecretRef.File -> Files.readString(Path.of(secretRef.path)).trim()
            else -> null
        }
    }

    private enum class Api(val path: String, val reply: String) {
        CHAT("/chat/completions", """{"id":"fixture","object":"chat.completion","created":1,"model":"account/exact-model","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}]}"""),
        RESPONSES("/responses", """{"id":"fixture","object":"response","created_at":1,"status":"completed","model":"account/exact-model","output":[{"type":"message","id":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"OK","annotations":[]}]}]}"""),
        MESSAGES("/v1/messages", """{"id":"fixture","type":"message","role":"assistant","model":"account/exact-model","content":[{"type":"text","text":"OK"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""");
        fun connection(url: String, id: String = name, settings: AiHttpSettings = settings(), token: SecretRef = SecretRef.Inline(TOKEN)): AiConnection = when (this) {
            CHAT -> AiConnection.OpenAiCompatible(AiConnection.Id(id), "Synthetic", baseUrl = url, apiKey = token, httpSettings = settings)
            RESPONSES -> AiConnection.OpenAiApi(AiConnection.Id(id), "Synthetic", baseUrl = url, apiKey = token, httpSettings = settings)
            MESSAGES -> AiConnection.AnthropicApi(AiConnection.Id(id), "Synthetic", baseUrl = url, apiKey = token, httpSettings = settings)
        }
    }
    private companion object {
        const val TOKEN = "synthetic-pooling-secret"
        const val MODEL = "account/exact-model"
        fun settings() = AiHttpSettings(authentication = AiHttpSettings.Authentication.BEARER, noProxy = listOf("*"),
            extraHeaders = mapOf("x-tfy-strict-openai" to "false", "X-TFY-METADATA" to """{"application":"pool-fixture"}"""))
    }
}
