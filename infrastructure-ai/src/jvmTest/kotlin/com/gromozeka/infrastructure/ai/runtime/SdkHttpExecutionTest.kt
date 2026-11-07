package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.*
import com.gromozeka.infrastructure.ai.anthropic.AnthropicSdkRuntimeBackend
import com.gromozeka.infrastructure.ai.openai.OpenAiSdkClientFactory
import com.gromozeka.infrastructure.ai.openai.OpenAiSdkRuntimeBackend
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class SdkHttpExecutionTest {
    @Test
    fun `all SDK APIs retry only complete 429 with one token snapshot and unchanged model`() = runBlocking {
        for (api in Api.entries) {
            val sources = Sources()
            SdkHttpTestServer(listOf(rate(), rate(), SdkHttpTestServer.Reply(api.reply()))).use { server ->
                assertEquals(AiStepOutcome.COMPLETE, runtime(api.connection(server.origin), sources).call(request()).outcome)
                assertEquals(3, server.requests.size)
                assertEquals(1, sources.reads.get())
                assertEquals(1, server.requests.map { it.body }.distinct().size)
                assertTrue(server.requests.all { it.headers["authorization"] == listOf("Bearer $TOKEN") })
            }
        }
    }

    @Test
    fun `attempt cap is shared with SDK and Retry-After cannot exceed the logical budget`() = runBlocking {
        for (api in Api.entries) {
            SdkHttpTestServer(List(3) { rate() }).use { server ->
                val error = assertFailsWith<AiCallException> { runtime(api.connection(server.origin)).call(request()) }
                assertEquals(AiCallFailure.Kind.RATE_LIMITED, error.failure.kind)
                assertEquals(AiCallFailure.Outcome.REJECTED, error.failure.outcome)
                assertEquals(3, error.failure.attempts)
                assertEquals(3, server.requests.size)
            }
            SdkHttpTestServer(listOf(rate("3600"))).use { server ->
                val error = assertFailsWith<AiCallException> { runtime(api.connection(server.origin)).call(request(500)) }
                assertEquals(AiCallFailure.Kind.RATE_LIMITED, error.failure.kind)
                assertEquals(3_600_000, error.failure.retryAfterMillis)
                assertEquals(1, server.requests.size)
            }
            SdkHttpTestServer(listOf(rate())).use { server ->
                val connection = api.connection(server.origin, settings = settings().copy(maxAttempts = 1))
                assertEquals(1, assertFailsWith<AiCallException> { runtime(connection).call(request()) }.failure.attempts)
                assertEquals(1, server.requests.size)
            }
        }
    }

    @Test
    fun `Retry-After seconds dates overflow and bounded jitter have deterministic lower bounds`() {
        val now = java.time.Instant.parse("2026-11-07T00:00:00Z")
        assertEquals(2000, parseRetryAfter("2", now))
        assertEquals(2000, parseRetryAfter("Sat, 7 Nov 2026 00:00:02 GMT", now))
        assertEquals(0, parseRetryAfter("Fri, 6 Nov 2026 00:00:00 GMT", now))
        assertEquals(Long.MAX_VALUE, parseRetryAfter("999999999999999999999999", now))
        assertNull(parseRetryAfter("-1", now)); assertNull(parseRetryAfter("private-marker", now))
        assertEquals(2250, retryDelayMillis(1, 2000, 250))
        assertEquals(250, retryDelayMillis(1, null, 0))
        assertEquals(4250, retryDelayMillis(5, null, 250))
        assertEquals(Long.MAX_VALUE, retryDelayMillis(2, Long.MAX_VALUE, 250))
    }

    @Test
    fun `malformed body and missing required response fields become safe typed failures without retry`() = runBlocking {
        for (api in Api.entries) for (body in listOf("{}", "private-invalid-response-marker")) {
            SdkHttpTestServer(body).use { server ->
                val error = assertFailsWith<AiCallException> { runtime(api.connection(server.origin)).call(request()) }
                assertEquals(AiCallFailure.Kind.INVALID_RESPONSE, error.failure.kind)
                assertEquals(AiCallFailure.Outcome.UNKNOWN, error.failure.outcome)
                assertEquals(1, error.failure.attempts)
                assertEquals(1, server.requests.size)
                assertNull(error.cause); assertTrue(error.suppressed.isEmpty())
                assertFalse(error.stackTraceToString().contains("private-invalid-response-marker"))
            }
        }
    }

    @Test
    fun `actual Retry-After wait is not shortened`() = runBlocking {
        SdkHttpTestServer(listOf(rate("1"), SdkHttpTestServer.Reply(Api.CHAT.reply()))).use { server ->
            val before = System.nanoTime()
            runtime(Api.CHAT.connection(server.origin)).call(request())
            assertTrue(System.nanoTime() - before >= 1_000_000_000)
            assertEquals(2, server.requests.size)
        }
    }

    @Test
    fun `408 409 5xx and redirects never cause hidden retry or route change`() = runBlocking {
        for (api in Api.entries) for (status in listOf(408, 409, 500, 502, 503, 504, 307)) {
            val reply = SdkHttpTestServer.Reply(ERROR, status,
                mapOf("x-should-retry" to "true", "Location" to "/another-route", "Retry-After" to "0"))
            SdkHttpTestServer(listOf(reply)).use { server ->
                val error = assertFailsWith<AiCallException> { runtime(api.connection(server.origin)).call(request()) }
                assertEquals(status, error.failure.httpStatus)
                assertEquals(AiCallFailure.Outcome.UNKNOWN, error.failure.outcome)
                assertEquals(1, error.failure.attempts)
                assertEquals(1, server.requests.size)
                assertFalse(error.stackTraceToString().contains("private-error-marker"))
                assertNull(error.cause); assertTrue(error.suppressed.isEmpty())
            }
        }
    }

    @Test
    fun `cancellation closes real socket before headers and during SDK body parsing for every API`() = runBlocking {
        for (api in Api.entries) for (partialBody in listOf(false, true)) {
            StalledServer(partialBody).use { server ->
                val job = launch(Dispatchers.Default) { runtime(api.connection(server.origin)).call(request()) }
                server.started.awaitCancellable()
                withTimeout(2000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertEquals(-1, withContext(Dispatchers.IO) { server.peerClosed.get(2, TimeUnit.SECONDS) })
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test
    fun `deadline closes actual stalled body and returns unknown without another attempt`() = runBlocking {
        for (api in Api.entries) {
            StalledServer(true).use { server ->
                val error = assertFailsWith<AiCallException> { runtime(api.connection(server.origin), timeoutSeconds = 1).call(request()) }
                assertEquals(AiCallFailure.Kind.DEADLINE_EXCEEDED, error.failure.kind)
                assertEquals(AiCallFailure.Outcome.UNKNOWN, error.failure.outcome)
                assertEquals(1, error.failure.attempts)
                assertEquals(-1, withContext(Dispatchers.IO) { server.peerClosed.get(2, TimeUnit.SECONDS) })
            }
        }
    }

    @Test
    fun `transport reset and truncated successful or 429 body are not repeated`() = runBlocking {
        for (api in Api.entries) for (status in listOf(0, 200, 429)) {
            StalledServer(partialBody = status != 0, drop = true, status = status).use { server ->
                val error = assertFailsWith<AiCallException> { runtime(api.connection(server.origin)).call(request()) }
                assertEquals(AiCallFailure.Outcome.UNKNOWN, error.failure.outcome)
                assertNotEquals(AiCallFailure.Kind.RATE_LIMITED, error.failure.kind)
                assertEquals(1, error.failure.attempts)
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test
    fun `logical incomplete response is returned once and is not an HTTP retry`() = runBlocking {
        for (api in Api.entries) {
            val incomplete = when (api) {
                Api.CHAT -> api.reply().replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"length\"")
                Api.RESPONSES -> api.reply().replace("\"status\":\"completed\"", "\"status\":\"incomplete\"")
                Api.MESSAGES -> api.reply().replace("\"stop_reason\":\"end_turn\"", "\"stop_reason\":\"max_tokens\"")
            }
            SdkHttpTestServer(incomplete).use { server ->
                assertEquals(AiStepOutcome.INCOMPLETE, runtime(api.connection(server.origin)).call(request()).outcome)
                assertEquals(1, server.requests.size)
            }
        }
    }

    @Test
    fun `all models and runtime instances share connection concurrency and release permits`() = runBlocking {
        ConcurrentServer(Api.CHAT.reply()).use { server ->
            val connection = Api.CHAT.connection(server.origin, settings = settings().copy(maxConcurrentCalls = 2))
            val jobs = List(4) { index -> async(Dispatchers.Default) { runtime(connection, modelId = "configuration-$index").call(request()) } }
            server.twoArrivals.awaitCancellable()
            delay(100)
            assertEquals(2, server.requests.get())
            server.release.countDown()
            withTimeout(5000) { jobs.awaitAll() }
            assertEquals(4, server.requests.get())
            assertEquals(2, server.peak.get())
        }
    }

    @Test
    fun `cancelled and expired queue waits do not launch or strand permits and other connections remain independent`() = runBlocking {
        StalledServer(false).use { stalled ->
            val id = UUID.randomUUID().toString()
            val connection = Api.CHAT.connection(stalled.origin, id, settings().copy(maxConcurrentCalls = 1))
            val running = launch(Dispatchers.Default) { runtime(connection).call(request()) }
            stalled.started.awaitCancellable()
            try {
                val error = assertFailsWith<AiCallException> { runtime(connection).call(request(150)) }
                assertEquals(AiCallFailure.Outcome.NOT_STARTED, error.failure.outcome)
                assertEquals(0, error.failure.attempts)
                val queued = launch(Dispatchers.Default) { runtime(connection).call(request()) }
                delay(100); queued.cancelAndJoin()
                SdkHttpTestServer(Api.CHAT.reply()).use { independent ->
                    withTimeout(3000) { runtime(Api.CHAT.connection(independent.origin)).call(request()) }
                    assertEquals(1, independent.requests.size)
                }
                assertEquals(1, stalled.requests.get())
            } finally { running.cancelAndJoin() }
            SdkHttpTestServer(Api.CHAT.reply()).use { next ->
                withTimeout(3000) { runtime(Api.CHAT.connection(next.origin, id, settings().copy(maxConcurrentCalls = 1))).call(request()) }
                assertEquals(1, next.requests.size)
            }
        }
    }

    @Test
    fun `cancellation during retry wait releases the shared permit and never starts a late retry`() = runBlocking {
        SdkHttpTestServer(listOf(rate("2"), SdkHttpTestServer.Reply(Api.CHAT.reply()))).use { server ->
            val connection = Api.CHAT.connection(server.origin, settings = settings().copy(maxConcurrentCalls = 1))
            val first = launch(Dispatchers.Default) { runtime(connection).call(request()) }
            try {
                withTimeout(3000) { while (server.requests.isEmpty()) delay(10) }
                val second = async(Dispatchers.Default) { runtime(connection, modelId = "another-model").call(request()) }
                try {
                    delay(150)
                    assertEquals(1, server.requests.size, "Backoff still owns the connection permit")
                    withTimeout(1000) { first.cancelAndJoin() }
                    assertEquals(AiStepOutcome.COMPLETE, withTimeout(3000) { second.await() }.outcome)
                    delay(2200)
                    assertEquals(2, server.requests.size)
                } finally { second.cancelAndJoin() }
            } finally { first.cancelAndJoin() }
        }
    }

    @Test
    fun `cancellation is installed before SDK request preparation can reach native HTTP`() = runBlocking {
        SdkHttpTestServer(Api.CHAT.reply()).use { server ->
            val connection = Api.CHAT.connection(server.origin)
            val factory = OpenAiSdkClientFactory(Sources())
            val executor = SdkHttpCallExecutor(connection, 5) { factory.createInferenceClient(connection, it) }
            val preparing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val params = com.gromozeka.infrastructure.ai.openai.OpenAiSdkMessageMapper(connection.kind, connection.id.value, MODEL)
                .toCreateParams(MODEL, request())
            val job = launch(Dispatchers.Default) {
                executor.call(AiRuntimeOptions()) { client, _ ->
                    preparing.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    // A synchronous SDK call makes a too-late cancellation registration observable.
                    client.chat().completions().create(params)
                    CompletableFuture.completedFuture(Unit)
                }
            }
            try {
                preparing.awaitCancellable()
                job.cancel()
                release.countDown()
                withTimeout(2000) { job.join() }
                assertTrue(job.isCancelled)
                assertTrue(server.requests.isEmpty())
            } finally { release.countDown(); job.cancelAndJoin() }
        }
    }

    @Test
    fun `deadline during credential setup and already expired deadline never start HTTP`() = runBlocking {
        for (api in Api.entries) {
            SdkHttpTestServer(api.reply()).use { server ->
                val sources = Sources(setupDelayMillis = 150)
                val rt = runtime(api.connection(server.origin), sources)
                val delayed = assertFailsWith<AiCallException> { rt.call(request(50)) }
                assertEquals(AiCallFailure.Outcome.NOT_STARTED, delayed.failure.outcome)
                assertEquals(0, delayed.failure.attempts)
                assertEquals(AiCallFailure.Kind.DEADLINE_EXCEEDED, assertFailsWith<AiCallException> { rt.call(request(-1)) }.failure.kind)
                assertTrue(server.requests.isEmpty())
                assertEquals(1, sources.reads.get())
            }
        }
    }

    private fun settings() = AiHttpSettings(authentication = AiHttpSettings.Authentication.BEARER, noProxy = listOf("*"))
    private fun rate(after: String = "0") = SdkHttpTestServer.Reply(ERROR, 429, mapOf("Retry-After" to after, "x-should-retry" to "true"))
    private fun request(deadlineMillis: Long? = null) = AiRuntimeRequest(listOf("Synthetic system"), listOf(
        Conversation.Message(Conversation.Message.Id("one"), Conversation.Id("synthetic"), role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Hello")), createdAt = Instant.fromEpochMilliseconds(0))),
        options = AiRuntimeOptions(maxOutputTokens = 128, executionDeadline = deadlineMillis?.let { Clock.System.now() + it.milliseconds }))
    private fun runtime(connection: AiConnection, sources: Sources = Sources(), timeoutSeconds: Int? = null, modelId: String = "synthetic"): AiRuntime {
        val backend = if (connection.kind == AiConnection.Kind.ANTHROPIC_API) AnthropicSdkRuntimeBackend(sources)
            else OpenAiSdkRuntimeBackend(OpenAiSdkClientFactory(sources))
        val model = AiModelConfiguration(AiModelConfiguration.Id(modelId), connection.id, MODEL, "Synthetic",
            defaultParameters = AiModelConfiguration.DefaultParameters(timeoutSeconds = timeoutSeconds))
        val spec = AiModelSpec(MODEL, connection.kind.provider, setOf(AiModelCapability.TEXT_GENERATION),
            AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(32_000, 8192)))
        return RoutingAiRuntimeProvider(listOf(backend)).getRuntime(ResolvedAiRuntime(connection, model, spec), null)
    }
    private class Sources(private val setupDelayMillis: Long = 0) : SettingsProvider {
        val reads = AtomicInteger()
        override val userProfile: UserProfile get() = error("Not used")
        override val userDeviceSettings: UserDeviceSettings get() = error("Not used")
        override val mode: AppMode get() = error("Not used")
        override val homeDirectory: String get() = error("Not used")
        override fun resolveSecret(secretRef: SecretRef?): String { reads.incrementAndGet(); Thread.sleep(setupDelayMillis); return TOKEN }
    }
    private enum class Api {
        CHAT, RESPONSES, MESSAGES;
        fun connection(url: String, id: String = UUID.randomUUID().toString(),
            settings: AiHttpSettings = AiHttpSettings(authentication = AiHttpSettings.Authentication.BEARER, noProxy = listOf("*"))): AiConnection = when (this) {
            CHAT -> AiConnection.OpenAiCompatible(AiConnection.Id(id), "Synthetic", enabled = true, baseUrl = url, apiKey = SecretRef.Inline(TOKEN), httpSettings = settings)
            RESPONSES -> AiConnection.OpenAiApi(AiConnection.Id(id), "Synthetic", enabled = true, baseUrl = url, apiKey = SecretRef.Inline(TOKEN), httpSettings = settings)
            MESSAGES -> AiConnection.AnthropicApi(AiConnection.Id(id), "Synthetic", enabled = true, baseUrl = url, apiKey = SecretRef.Inline(TOKEN), httpSettings = settings)
        }
        fun reply(): String = when (this) {
            CHAT -> """{"id":"fixture","object":"chat.completion","created":1,"model":"$MODEL","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}]}"""
            RESPONSES -> """{"id":"fixture","object":"response","created_at":1,"status":"completed","model":"$MODEL","output":[{"type":"message","id":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"OK","annotations":[]}]}]}"""
            MESSAGES -> """{"id":"fixture","type":"message","role":"assistant","model":"$MODEL","content":[{"type":"text","text":"OK"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""
        }
    }
    private suspend fun CountDownLatch.awaitCancellable() = withContext(Dispatchers.IO) { assertTrue(await(5, TimeUnit.SECONDS)) }
    private companion object {
        const val TOKEN = "synthetic-execution-secret"
        const val MODEL = "account/exact-model"
        const val ERROR = """{"error":{"type":"rate_limit_error","message":"private-error-marker","code":"limited"}}"""
    }
}

/** A real HTTP/1 socket makes peer EOF observable even when the response parser is blocked. */
internal class StalledServer(partialBody: Boolean, drop: Boolean = false, status: Int = 200) : AutoCloseable {
    private val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null
    val origin = "http://127.0.0.1:${listener.localPort}"
    val started = CountDownLatch(1)
    val requests = AtomicInteger()
    val peerClosed = CompletableFuture<Int>()
    init {
        executor.submit {
            try {
                listener.accept().use { accepted ->
                    socket = accepted
                    accepted.soTimeout = 8000
                    val input = BufferedInputStream(accepted.getInputStream())
                    fun line(): String = buildString {
                        while (true) { val ch = input.read(); check(ch >= 0); if (ch == 10) break; if (ch != 13) append(ch.toChar()) }
                    }
                    line()
                    val headers = generateSequence { line().takeIf(String::isNotEmpty) }.map { it.split(':', limit = 2) }
                        .associate { it[0].lowercase() to it[1].trim() }
                    val length = requireNotNull(headers["content-length"]).toInt()
                    check(input.readNBytes(length).size == length)
                    requests.incrementAndGet()
                    if (partialBody) {
                        accepted.getOutputStream().write(("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\n" +
                            "Content-Length: 100000\r\nRetry-After: 0\r\nConnection: close\r\n\r\n{\"id\":\"partial").toByteArray())
                        accepted.getOutputStream().flush()
                    }
                    started.countDown()
                    if (!drop) peerClosed.complete(input.read())
                }
            } catch (error: Exception) { peerClosed.completeExceptionally(error); started.countDown() }
        }
    }
    override fun close() { listener.close(); socket?.close(); executor.shutdownNow(); check(executor.awaitTermination(3, TimeUnit.SECONDS)) }
}

private class ConcurrentServer(private val reply: String) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val origin = "http://127.0.0.1:${server.address.port}"
    val release = CountDownLatch(1)
    val twoArrivals = CountDownLatch(2)
    val requests = AtomicInteger()
    val peak = AtomicInteger()
    private val active = AtomicInteger()
    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.use {
                exchange.requestBody.readBytes()
                requests.incrementAndGet()
                val count = active.incrementAndGet(); peak.accumulateAndGet(count, ::maxOf); twoArrivals.countDown()
                try {
                    check(release.await(8, TimeUnit.SECONDS))
                    val bytes = reply.toByteArray(); exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
                } finally { active.decrementAndGet() }
            }
        }
        server.start()
    }
    override fun close() { release.countDown(); server.stop(0); executor.shutdownNow(); check(executor.awaitTermination(3, TimeUnit.SECONDS)) }
}
