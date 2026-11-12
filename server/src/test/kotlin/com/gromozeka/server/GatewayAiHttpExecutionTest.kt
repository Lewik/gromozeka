package com.gromozeka.server

import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.WorkerRequestRepository
import com.gromozeka.domain.service.*
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

class GatewayAiHttpExecutionTest {
    private val workerId = ConversationRuntimeWorkerId("fixture-worker")
    private val identity = ConversationRuntimeWorkerIdentity(workerId, ConversationRuntimeWorkerSessionId("session"))
    private val connection = AiConnection.OpenAiCompatible(AiConnection.Id("http"), "HTTP", enabled = true,
        baseUrl = "http://fixture.invalid", apiKey = SecretRef.Inline("synthetic-token"), executionTarget = AiExecutionTarget.Worker(workerId.value))
    private val runtime = ResolvedAiRuntime(connection,
        AiModelConfiguration(AiModelConfiguration.Id("model"), connection.id, "exact-model", "Model"),
        AiModelSpec("exact-model", connection.kind.provider, setOf(AiModelCapability.TEXT_GENERATION),
            AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(32000, 8192))))
    private val request = AiRuntimeRequest(emptyList(), emptyList())
    private fun requests(repository: WorkerRequestRepository) = WorkerRequestService(repository, WorkerRequestAuthorization {})

    @Test
    fun `model-list gateway records the actor and exact connection without resolving a model`() = runBlocking<Unit> {
        val actor = com.gromozeka.domain.model.User.Id("inspection-owner")
        val repository = TestWorkerRequestRepository()
        val requests = WorkerRequestService(repository, WorkerRequestAuthorization { assertEquals(actor, it.actorUserId) })
        val gateway = GatewayAiRequestResponseExecutionClient(requests, 5000)
        val session = WorkerGatewaySession(identity)
        val delivery = launch { requests.deliver(session) }
        val deadline = Clock.System.now() + 3000.milliseconds
        val expected = AiConnectionModels(listOf(AiModelCandidate("route/without-model-spec")), Clock.System.now())
        val worker = launch {
            val envelope = withTimeout(3000) { session.outgoingMessages().receive() } as WorkerGatewayMessage.Request
            assertFalse(envelope.payload.decodeToString().contains("modelSpec"))
            val response = AiRequestResponseGatewayCodec.execute(envelope.payload, object : UnusedHandler() {
                override suspend fun listModels(selected: AiConnection, actualDeadline: kotlin.time.Instant): AiConnectionModels {
                    assertEquals(connection, selected); assertEquals(deadline, actualDeadline); return expected
                }
            }, workerId)
            requests.accept(workerId, WorkerGatewayMessage.Response(envelope.id, WorkerGatewayMessage.Response.Status.SUCCEEDED, response))
        }
        try {
            assertEquals(expected, gateway.listModels(identity, connection, deadline, actor))
            assertEquals(actor, repository.records.values.single().actorUserId)
            assertEquals(1, repository.records.size)
        } finally { worker.cancelAndJoin(); delivery.cancelAndJoin() }
    }

    @Test
    fun `model-list gateway rejects mismatched disabled and expired requests before submission`() = runBlocking<Unit> {
        val repository = TestWorkerRequestRepository()
        val gateway = GatewayAiRequestResponseExecutionClient(requests(repository), 5000)
        val actor = com.gromozeka.domain.model.User.Id("inspection-owner")
        val deadline = Clock.System.now() + 1000.milliseconds
        assertFailsWith<IllegalArgumentException> { gateway.listModels(identity, connection.copy(enabled = false), deadline, actor) }
        assertFailsWith<IllegalArgumentException> { gateway.listModels(identity, connection.copy(executionTarget = AiExecutionTarget.Worker("other")), deadline, actor) }
        assertFailsWith<AiCallException> { gateway.listModels(identity, connection, Clock.System.now() - 1.milliseconds, actor) }
        assertTrue(repository.records.isEmpty())
    }

    @Test
    fun `gateway retains typed failure and absolute deadline with one durable request`(): Unit = runBlocking {
        val repository = TestWorkerRequestRepository()
        val requests = requests(repository)
        val gateway = GatewayAiRequestResponseExecutionClient(requests, 5000)
        val session = WorkerGatewaySession(identity)
        val delivery = launch { requests.deliver(session) }
        val expected = AiCallFailure(AiCallFailure.Kind.RATE_LIMITED, AiCallFailure.Outcome.REJECTED, 3, 429, 1000)
        val deadline = Clock.System.now() + 3000.milliseconds
        val worker = launch {
            val envelope = withTimeout(3000) { session.outgoingMessages().receive() } as WorkerGatewayMessage.Request
            val bytes = AiRequestResponseGatewayCodec.execute(envelope.payload, object : UnusedHandler() {
                override suspend fun call(runtime: ResolvedAiRuntime, workspaceRootPath: String?, request: AiRuntimeRequest): AiRuntimeResponse {
                    assertEquals(deadline, request.options.executionDeadline)
                    assertEquals(connection, runtime.connection)
                    throw AiCallException(expected)
                }
            })
            requests.accept(workerId, WorkerGatewayMessage.Response(envelope.id, WorkerGatewayMessage.Response.Status.SUCCEEDED, bytes))
        }
        try {
            val error = assertFailsWith<AiCallException> { gateway.call(identity, runtime, null,
                request.copy(options = AiRuntimeOptions(executionDeadline = deadline))) }
            assertEquals(expected.copy(workerRequestId = repository.records.keys.single()), error.failure)
            worker.join()
            assertEquals(1, repository.records.size)
            assertNull(error.cause); assertTrue(error.suppressed.isEmpty())
        } finally { worker.cancelAndJoin(); delivery.cancelAndJoin() }
    }

    @Test
    fun `parent cancellation durably cancels the same dispatched request without resubmitting`(): Unit = runBlocking {
        val repository = TestWorkerRequestRepository()
        val requests = requests(repository)
        val session = WorkerGatewaySession(identity)
        val delivery = launch { requests.deliver(session) }
        val job = launch { GatewayAiRequestResponseExecutionClient(requests, 5000).call(identity, runtime, null, request) }
        try {
            val first = withTimeout(3000) { session.outgoingMessages().receive() } as WorkerGatewayMessage.Request
            job.cancelAndJoin()
            val cancellation = withTimeout(3000) { session.outgoingMessages().receive() } as WorkerGatewayMessage.Request
            assertEquals(first.id, cancellation.id)
            assertContentEquals(first.payload, cancellation.payload)
            assertTrue(cancellation.delivery!!.cancelRequested)
            assertNotNull(repository.records.getValue(first.id).cancelRequestedAt)
            assertEquals(1, repository.records.size)
        } finally { job.cancelAndJoin(); delivery.cancelAndJoin() }
    }

    @Test
    fun `offline logical deadline requests cancellation and expired calls submit nothing`(): Unit = runBlocking {
        val repository = TestWorkerRequestRepository()
        val gateway = GatewayAiRequestResponseExecutionClient(requests(repository), 150)
        val error = assertFailsWith<AiCallException> { gateway.call(identity, runtime, null, request) }
        assertEquals(AiCallFailure.Kind.DEADLINE_EXCEEDED, error.failure.kind)
        assertEquals(AiCallFailure.Outcome.UNKNOWN, error.failure.outcome)
        assertEquals(repository.records.keys.single(), error.failure.workerRequestId)
        assertNotNull(repository.records.values.single().cancelRequestedAt)
        assertNull(error.failure.attempts)
        val expired = assertFailsWith<AiCallException> { gateway.call(identity, runtime, null,
            request.copy(options = AiRuntimeOptions(executionDeadline = Clock.System.now() - 1.milliseconds))) }
        assertEquals(AiCallFailure.Outcome.NOT_STARTED, expired.failure.outcome)
        assertEquals(0, expired.failure.attempts)
        assertEquals(1, repository.records.size)
    }

    @Test
    fun `outer Worker failures are safe unknown results not retryable SDK errors`(): Unit = runBlocking {
        for (code in listOf("OUTCOME_UNKNOWN", "EXECUTION_TIMEOUT", "FAILED", "EXPIRED")) {
            val repository = TestWorkerRequestRepository()
            val requests = requests(repository)
            val session = WorkerGatewaySession(identity)
            val delivery = launch { requests.deliver(session) }
            val worker = launch {
                val envelope = withTimeout(3000) { session.outgoingMessages().receive() } as WorkerGatewayMessage.Request
                requests.accept(workerId, WorkerGatewayMessage.Response(envelope.id, WorkerGatewayMessage.Response.Status.FAILED,
                    errorCode = code, errorMessage = "synthetic-private-token-and-response"))
            }
            try {
                val error = assertFailsWith<AiCallException> { GatewayAiRequestResponseExecutionClient(requests, 5000).call(identity, runtime, null, request) }
                assertEquals(AiCallFailure.Kind.WORKER_FAILURE, error.failure.kind)
                assertEquals(if (code == "EXPIRED") AiCallFailure.Outcome.NOT_STARTED else AiCallFailure.Outcome.UNKNOWN, error.failure.outcome)
                assertEquals(repository.records.keys.single(), error.failure.workerRequestId)
                assertFalse(error.stackTraceToString().contains("synthetic-private-token-and-response"))
                assertEquals(1, repository.records.size)
            } finally { worker.cancelAndJoin(); delivery.cancelAndJoin() }
        }
    }

    @Test
    fun `invalid Worker AI payload becomes a safe unknown result retaining its request id`(): Unit = runBlocking {
        val repository = TestWorkerRequestRepository()
        val requests = requests(repository)
        val session = WorkerGatewaySession(identity)
        val delivery = launch { requests.deliver(session) }
        val worker = launch {
            val envelope = withTimeout(3000) { session.outgoingMessages().receive() } as WorkerGatewayMessage.Request
            requests.accept(workerId, WorkerGatewayMessage.Response(envelope.id, WorkerGatewayMessage.Response.Status.SUCCEEDED,
                "synthetic-private-invalid-payload".encodeToByteArray()))
        }
        try {
            val error = assertFailsWith<AiCallException> { GatewayAiRequestResponseExecutionClient(requests, 5000).call(identity, runtime, null, request) }
            assertEquals(AiCallFailure.Kind.WORKER_FAILURE, error.failure.kind)
            assertEquals(AiCallFailure.Outcome.UNKNOWN, error.failure.outcome)
            assertEquals(repository.records.keys.single(), error.failure.workerRequestId)
            assertFalse(error.stackTraceToString().contains("synthetic-private-invalid-payload"))
            assertNull(error.cause); assertTrue(error.suppressed.isEmpty())
        } finally { worker.cancelAndJoin(); delivery.cancelAndJoin() }
    }

    @Test
    fun `cancellation racing with durable submission keeps the id and cancels the inserted request`(): Unit = runBlocking {
        val delegate = TestWorkerRequestRepository()
        val inserted = CompletableDeferred<Unit>()
        val returnFromInsert = CompletableDeferred<Unit>()
        val repository = object : WorkerRequestRepository by delegate {
            override suspend fun create(request: StoredWorkerRequest) {
                delegate.create(request); inserted.complete(Unit); returnFromInsert.await()
            }
        }
        val gateway = GatewayAiRequestResponseExecutionClient(requests(repository), 5000)
        val job = launch { gateway.call(identity, runtime, null, request) }
        inserted.await()
        job.cancel()
        returnFromInsert.complete(Unit)
        withTimeout(3000) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals(1, delegate.records.size)
        assertNotNull(delegate.records.values.single().cancelRequestedAt)
    }

    private open class UnusedHandler : AiRequestResponseExecutionHandler {
        override suspend fun inspectContext(runtime: com.gromozeka.domain.service.ResolvedAiRuntime, workspaceRootPath: String?,
            request: com.gromozeka.domain.model.ai.AiRuntimeRequest): com.gromozeka.domain.model.ai.AiContextInspection = error("Unused inspectContext")
        override suspend fun compact(runtime: com.gromozeka.domain.service.ResolvedAiRuntime, workspaceRootPath: String?,
            request: com.gromozeka.domain.model.ai.AiRuntimeRequest): com.gromozeka.domain.model.ai.AiRuntimeResponse = error("Unused compact")

        override suspend fun listModels(connection: com.gromozeka.domain.model.ai.AiConnection, deadline: kotlin.time.Instant):
            com.gromozeka.domain.model.ai.AiConnectionModels = error("Unused model listing")

        override suspend fun call(runtime: ResolvedAiRuntime, workspaceRootPath: String?, request: AiRuntimeRequest): AiRuntimeResponse = error("Unused")
        override suspend fun embed(runtime: ResolvedAiRuntime, request: AiEmbeddingRequest): AiEmbeddingResponse = error("Unused")
        override suspend fun transcribe(runtime: ResolvedAiRuntime?, localWhisperSettings: UserProfile.SpeechSettings.SpeechToText.LocalWhisper?, request: AiSpeechTranscriptionRequest): String = error("Unused")
        override suspend fun synthesize(runtime: ResolvedAiRuntime, request: AiSpeechSynthesisRequest): AiSpeechSynthesisResponse = error("Unused")
        override suspend fun readSubscriptionQuota(request: AiSubscriptionQuotaRequest): AiSubscriptionQuotaSnapshot = error("Unused")
    }
}
