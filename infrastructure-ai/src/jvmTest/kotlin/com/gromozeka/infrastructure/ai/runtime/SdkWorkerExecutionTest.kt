package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.*
import com.gromozeka.infrastructure.ai.openai.OpenAiSdkClientFactory
import com.gromozeka.infrastructure.ai.openai.OpenAiSdkRuntimeBackend
import com.gromozeka.remote.protocol.*
import com.gromozeka.worker.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Real executor + JSON/CBOR codecs + SDK/HTTP, not a separately started Worker or gateway socket. */
class SdkWorkerExecutionTest {
    @Test
    fun `model-list Worker operation executes one GET and replays its saved result`() = runBlocking<Unit> {
        SdkHttpTestServer("""{"object":"list","data":[{"id":"exact-account/model"}]}""").use { server ->
            val fixture = Fixture(server.origin, modelListing = true)
            try {
                fixture.executor.initialize()
                fixture.executor.accept(fixture.envelope())
                val response = withTimeout(3000) { fixture.responses.receive() }
                assertEquals(WorkerGatewayMessage.Response.Status.SUCCEEDED, response.status)
                assertEquals("exact-account/model", AiRequestResponseGatewayCodec.decodeModelsResponse(requireNotNull(response.payload)).models.single().id)
                assertEquals("GET", server.requests.single().method)
                assertEquals("/models", server.requests.single().target)
                fixture.executor.accept(fixture.envelope())
                assertEquals(response, fixture.responses.receive())
                assertEquals(1, server.requests.size)
            } finally { fixture.close() }
        }
    }

    @Test
    fun `cancellation delivered by Worker envelope closes SDK body socket and replay never repeats HTTP`() = runBlocking {
        for (listing in listOf(false, true)) StalledServer(true).use { server ->
            val fixture = Fixture(server.origin, modelListing = listing)
            try {
                fixture.executor.initialize()
                fixture.executor.accept(fixture.envelope())
                withContext(Dispatchers.IO) { assertTrue(server.started.await(5, TimeUnit.SECONDS)) }
                fixture.executor.accept(fixture.envelope(cancel = true))
                val response = withTimeout(3000) { fixture.responses.receive() }
                assertEquals("CANCELLED", response.errorCode)
                assertEquals(-1, withContext(Dispatchers.IO) { server.peerClosed.get(2, TimeUnit.SECONDS) })
                fixture.executor.accept(fixture.envelope())
                assertEquals(response, withTimeout(1000) { fixture.responses.receive() })
                assertEquals(1, server.requests.get())
                assertEquals(WorkerRequestReceipt.State.COMPLETED, fixture.journal.receipts.getValue("http-request").state)
            } finally { fixture.close() }
        }
    }

    @Test
    fun `expired HTTP deadline stays not-started through Worker receipt replay`() = runBlocking {
        SdkHttpTestServer("{}").use { server ->
            val fixture = Fixture(server.origin, logicalSeconds = -1)
            try {
                fixture.executor.initialize()
                fixture.executor.accept(fixture.envelope())
                val response = withTimeout(3000) { fixture.responses.receive() }
                assertEquals(WorkerGatewayMessage.Response.Status.SUCCEEDED, response.status)
                val error = assertFailsWith<AiCallException> { AiRequestResponseGatewayCodec.decodeCallResponse(requireNotNull(response.payload)) }
                assertEquals(AiCallFailure.Kind.DEADLINE_EXCEEDED, error.failure.kind)
                assertEquals(AiCallFailure.Outcome.NOT_STARTED, error.failure.outcome)
                assertEquals(0, error.failure.attempts)
                assertTrue(server.requests.isEmpty())
                fixture.executor.accept(fixture.envelope())
                assertEquals(response, withTimeout(1000) { fixture.responses.receive() })
                assertTrue(server.requests.isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test
    fun `HTTP deadline is typed through Worker while execution receipt stays replayable`() = runBlocking {
        StalledServer(true).use { server ->
            val fixture = Fixture(server.origin, logicalSeconds = 5)
            try {
                fixture.executor.initialize()
                fixture.executor.accept(fixture.envelope())
                withContext(Dispatchers.IO) { assertTrue(server.started.await(5, TimeUnit.SECONDS), "HTTP must start before testing an in-flight deadline") }
                val response = withTimeout(6000) { fixture.responses.receive() }
                assertEquals(WorkerGatewayMessage.Response.Status.SUCCEEDED, response.status)
                val error = assertFailsWith<AiCallException> { AiRequestResponseGatewayCodec.decodeCallResponse(requireNotNull(response.payload)) }
                assertEquals(AiCallFailure.Kind.DEADLINE_EXCEEDED, error.failure.kind)
                assertEquals(AiCallFailure.Outcome.UNKNOWN, error.failure.outcome)
                assertEquals(1, error.failure.attempts)
                assertEquals(-1, withContext(Dispatchers.IO) { server.peerClosed.get(2, TimeUnit.SECONDS) })
                fixture.executor.accept(fixture.envelope())
                assertEquals(response, withTimeout(1000) { fixture.responses.receive() })
                assertEquals(1, server.requests.get())
            } finally { fixture.close() }
        }
    }

    @Test
    fun `exhausted rate limit survives receipt recovery without another provider call`() = runBlocking {
        val rate = SdkHttpTestServer.Reply("""{"error":{"type":"rate_limit_error","message":"private-error-marker"}}""", 429,
            mapOf("Retry-After" to "0", "x-tfy-resolved-model" to "private-route-marker"))
        SdkHttpTestServer(List(3) { rate }).use { server ->
            val fixture = Fixture(server.origin)
            try {
                fixture.executor.initialize()
                fixture.executor.accept(fixture.envelope())
                val response = withTimeout(4000) { fixture.responses.receive() }
                val failure = assertFailsWith<AiCallException> { AiRequestResponseGatewayCodec.decodeCallResponse(requireNotNull(response.payload)) }.failure
                assertEquals(AiCallFailure.Kind.RATE_LIMITED, failure.kind)
                assertEquals(3, failure.attempts)
                assertEquals(3, server.requests.size)
                assertFalse(response.payload!!.decodeToString().contains("private-"))
                fixture.executor.stop()
                val recovered = WorkerRequestExecutor(fixture.journal, fixture.scope,
                    WorkerRequestHandler { error("Receipt replay must not execute") }, fixture.responses::send)
                recovered.initialize()
                recovered.accept(fixture.envelope())
                assertEquals(response, withTimeout(1000) { fixture.responses.receive() })
                recovered.acknowledge("http-request")
                recovered.accept(fixture.envelope())
                assertEquals("RESULT_ACKNOWLEDGED", fixture.responses.receive().errorCode)
                assertEquals(3, server.requests.size)
                recovered.stop()
            } finally { fixture.close() }
        }
    }

    private class Journal : WorkerRequestJournal {
        val receipts = ConcurrentHashMap<String, WorkerRequestReceipt>()
        override suspend fun load() = receipts.values.toList()
        override suspend fun save(receipt: WorkerRequestReceipt) { receipts[receipt.id] = receipt }
        override suspend fun delete(id: String) { receipts.remove(id) }
    }
    private class Fixture(url: String, logicalSeconds: Int = 5, modelListing: Boolean = false) {
        val journal = Journal()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val responses = Channel<WorkerGatewayMessage.Response>(Channel.UNLIMITED)
        private val connection = AiConnection.OpenAiCompatible(AiConnection.Id("worker-http"), "Worker HTTP", enabled = true,
            baseUrl = url, apiKey = SecretRef.Inline("synthetic-worker-secret"), executionTarget = AiExecutionTarget.Worker("fixture-worker"),
            httpSettings = AiHttpSettings(noProxy = listOf("*")))
        private val runtime = ResolvedAiRuntime(connection,
            AiModelConfiguration(AiModelConfiguration.Id("model"), connection.id, "exact-model", "Model"),
            AiModelSpec("exact-model", connection.kind.provider, setOf(AiModelCapability.TEXT_GENERATION),
                AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(32000, 8192))))
        private val started = Clock.System.now()
        private val request = AiRuntimeRequest(listOf("Synthetic system"), listOf(Conversation.Message(
            Conversation.Message.Id("message"), Conversation.Id("conversation"), role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Hello")), createdAt = started)),
            options = AiRuntimeOptions(maxOutputTokens = 128, executionDeadline = started + logicalSeconds.seconds))
        private val payload = if (modelListing) AiRequestResponseGatewayCodec.encodeModelsRequest(connection, started + logicalSeconds.seconds)
            else AiRequestResponseGatewayCodec.encodeCallRequest(runtime, null, request)
        private val provider = RoutingAiRuntimeProvider(listOf(OpenAiSdkRuntimeBackend(OpenAiSdkClientFactory(Sources()))))
        private val handler = object : AiRequestResponseExecutionHandler {
        override suspend fun listModels(connection: com.gromozeka.domain.model.ai.AiConnection, deadline: kotlin.time.Instant):
            com.gromozeka.domain.model.ai.AiConnectionModels = SdkConnectionModelsExecutor(Sources()).listModels(connection, deadline)

            override suspend fun call(runtime: ResolvedAiRuntime, workspaceRootPath: String?, request: AiRuntimeRequest): AiRuntimeResponse =
                provider.getRuntime(runtime, workspaceRootPath).call(request)
            override suspend fun embed(runtime: ResolvedAiRuntime, request: AiEmbeddingRequest): AiEmbeddingResponse = error("Unused")
            override suspend fun transcribe(runtime: ResolvedAiRuntime?, localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?, request: AiSpeechTranscriptionRequest): String = error("Unused")
            override suspend fun synthesize(runtime: ResolvedAiRuntime, request: AiSpeechSynthesisRequest): AiSpeechSynthesisResponse = error("Unused")
            override suspend fun readSubscriptionQuota(request: AiSubscriptionQuotaRequest): AiSubscriptionQuotaSnapshot = error("Unused")
        }
        val executor = WorkerRequestExecutor(journal, scope, WorkerRequestHandler { envelope ->
            WorkerGatewayMessage.Response(envelope.id, WorkerGatewayMessage.Response.Status.SUCCEEDED,
                AiRequestResponseGatewayCodec.execute(envelope.payload, handler, ConversationRuntimeWorkerId("fixture-worker")))
        }, { response -> responses.send(WorkerGatewayCodec.decode(WorkerGatewayCodec.encode(response)) as WorkerGatewayMessage.Response) })
        fun envelope(cancel: Boolean = false): WorkerGatewayMessage.Request = WorkerGatewayCodec.decode(WorkerGatewayCodec.encode(
            WorkerGatewayMessage.Request("http-request", WorkerGatewayOperation.AI_REQUEST_RESPONSE, payload,
                WorkerRequestDelivery(started + 30.seconds, 10_000, cancelRequested = cancel)))) as WorkerGatewayMessage.Request
        suspend fun close() { executor.stop(); scope.cancel(); responses.close() }
    }
    private class Sources : SettingsProvider {
        override val userProfile: UserProfile get() = error("Unused")
        override val userDeviceSettings: UserDeviceSettings get() = error("Unused")
        override val mode: AppMode get() = error("Unused")
        override val homeDirectory: String get() = error("Unused")
        override fun resolveSecret(secretRef: SecretRef?): String? = (secretRef as SecretRef.Inline).value
    }
}
