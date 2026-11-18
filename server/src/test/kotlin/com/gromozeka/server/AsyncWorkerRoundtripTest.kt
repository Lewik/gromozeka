package com.gromozeka.server

import com.gromozeka.application.service.*
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.remote.protocol.*
import com.gromozeka.worker.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Real Server request service, strict RPC, Worker executor and on-disk snapshot journal; parent storage is a component double. */
class AsyncWorkerRoundtripTest {
    @Test fun `lost submit ACK and Worker completion ACK survive recreation without effects replay and preserve all result fields`() = runBlocking {
        for (toolError in listOf(false, true)) {
            val f = Fixture(toolError)
            try {
                f.loseSubmitAck = true
                assertFailsWith<IllegalStateException> { f.gateway.submit(f.operation, emptyMap()) }
                val canonical = assertNotNull(f.records.find(f.operation.id.value)).request.copyOf()
                f.gateway.submit(f.operation, emptyMap())
                assertContentEquals(canonical, assertNotNull(f.records.find(f.operation.id.value)).request)
                assertEquals(1, f.records.records.size)
                val request = f.dispatch()
                val journal = f.journal()
                var worker = f.worker(journal)
                worker.initialize(); worker.accept(request)
                val dropped = withTimeout(5_000) { f.responses.receive() }
                assertEquals(1, f.effects.get())
                assertEquals(WorkerRequestReceipt.State.COMPLETED, journal.load().single().state)
                worker.stop()
                worker = f.worker(f.journal())
                worker.initialize()
                assertEquals(dropped, worker.pendingResponses().single())
                assertTrue(f.requests.accept(f.identity.workerId, worker.pendingResponses().single()))
                f.requests.maintain()
                assertEquals(if (toolError) AsyncToolOperation.Phase.FAILED else AsyncToolOperation.Phase.SUCCEEDED, f.operation.phase)
                val outcome = assertNotNull(f.operation.outcome)
                assertEquals(toolError, outcome.isError)
                assertEquals("actual body", assertIs<ContentItem.ToolResult.Data.Text>(outcome.content.first()).content)
                assertIs<ContentItem.ToolResult.Data.Base64Data>(outcome.content[1])
                assertEquals(f.structured, outcome.structuredContent)
                assertNotNull(f.records.find(f.operation.id.value)?.completionProjectedAt)
                worker.accept(request)
                assertTrue(f.requests.accept(f.identity.workerId, withTimeout(5_000) { f.responses.receive() }))
                f.requests.maintain()
                assertEquals(outcome, f.operation.outcome)
                assertEquals(1, f.effects.get())
                worker.acknowledge(request.id); worker.stop()
                val notification = f.outcomes.notification(f.operation)
                val event = assertIs<ContentItem.RuntimeNotification>(notification.content.single())
                assertEquals(f.operation.invocation.origin.sourceToolCallId, event.toolUseId)
                assertTrue(event.attachments.any { it is ContentItem.ToolResult.Data.Base64Data })
                assertEquals(1, f.received.size)
                assertEquals(mapOf("call" to f.fingerprint), f.received.single().preapprovedFingerprints)
                Mockito.verifyNoInteractions(f.approvals)
            } finally { f.close() }
        }
    }

    @Test fun `cancellation before dispatch is cancelled while cancellation after dispatch retains possible effects`() = runBlocking {
        for (dispatched in listOf(false, true)) {
            val f = Fixture()
            try {
                f.gateway.submit(f.operation, emptyMap())
                if (dispatched) {
                    val request = f.dispatch()
                    assertTrue(f.requests.accept(f.identity.workerId, WorkerGatewayMessage.Response(request.id,
                        WorkerGatewayMessage.Response.Status.FAILED, errorCode = "CANCELLED", errorMessage = "Partial effects are possible")))
                } else f.requests.cancel(f.operation.id.value)
                f.requests.maintain()
                assertEquals(if (dispatched) AsyncToolOperation.Phase.OUTCOME_UNKNOWN else AsyncToolOperation.Phase.CANCELLED, f.operation.phase)
                assertEquals(dispatched, f.operation.startedAt != null)
                assertEquals(0, f.effects.get())
            } finally { f.close() }
        }
    }

    @Test fun `forged approval revision crosses strict RPC but fails at the real Worker executor before effects`() = runBlocking {
        val f = Fixture()
        try {
            f.gateway.submit(f.operation, emptyMap())
            val request = f.dispatch()
            val input = f.json.decodeFromString<WorkerToolExecutionRequest>(request.payload.decodeToString())
            for (proof in listOf(mapOf("call" to "0".repeat(64)), mapOf("another-call" to f.fingerprint))) {
                val modified = input.copy(preapprovedFingerprints = proof)
                val decoded = f.json.decodeFromString<WorkerToolExecutionRequest>(f.json.encodeToString(WorkerToolExecutionRequest.serializer(), modified))
                assertFailsWith<IllegalArgumentException> { f.execute(decoded) }
            }
            assertEquals(0, f.effects.get())
            Mockito.verifyNoInteractions(f.approvals)
        } finally { f.close() }
    }

    private class Fixture(val toolError: Boolean = false) {
        val now = Clock.System.now()
        val home = Files.createTempDirectory("async-worker-journal-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        val identity = ConversationRuntimeWorkerIdentity(ConversationRuntimeWorkerId("worker"), ConversationRuntimeWorkerSessionId("session"))
        val owner = ConversationRuntimeServerSessionId("server")
        val effects = AtomicInteger()
        val responses = Channel<WorkerGatewayMessage.Response>(Channel.UNLIMITED)
        val received = mutableListOf<WorkerToolExecutionRequest>()
        val structured = buildJsonObject { put("status", "WORKING"); put("task_id", "nested-command") }
        val approvals = Mockito.mock(ToolApprovalService::class.java)
        private val callback = object : AiToolCallback {
            override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.WORKSPACE)
            override val definition = AiToolDefinition("callback", "Synthetic Worker callback", """{"type":"object","properties":{}}""")
            override fun call(toolInput: String, context: ToolExecutionContext?) = error("Result callback required")
            override suspend fun callResult(toolInput: String, context: ToolExecutionContext?): AiToolCallResult {
                assertEquals("/captured-checkout", context?.getString(TOOL_CONTEXT_WORKSPACE_ROOT_PATH))
                assertEquals("{}", toolInput)
                effects.incrementAndGet()
                return AiToolCallResult(listOf(AiToolResult.Text("actual body"), AiToolResult.Binary(byteArrayOf(1, 2, 3), "result.bin", "application/octet-stream")), structured, toolError)
            }
        }
        val fingerprint = AiToolDescriptor(callback.definition, callback.metadata).contractFingerprint()
        private val parallel = ParallelToolExecutor(object : AiToolProvider { override fun getTools() = listOf(callback) }, approvals)
        private val origin = AsyncToolOrigin(User.Id("actor"), Project.Id("project"), Conversation.Id("conversation"), Conversation.Thread.Id("thread"),
            AgentDefinition.Id("agent"), ConversationRuntimeTask.Id("source"), ContentItem.ToolCall.Id("call"), Conversation.Message.Id("source:result"), false, null)
        private val target = ConversationRuntimeTaskTarget.Worker(identity.workerId, WorkspaceMount.Id("mount"), WorkerRequestPolicy(executionTimeoutMillis = 60_000))
        private val invocation = AsyncToolInvocation(origin, "callback_v1", fingerprint,
            ContentItem.ToolCall(origin.sourceToolCallId, ContentItem.ToolCall.Data("callback", buildJsonObject { put("_grz", buildJsonObject { put("async", true); put("workspace_mount_id", "mount") }) })),
            target, mapOf(TOOL_CONTEXT_USER_ID to "actor", TOOL_CONTEXT_PROJECT_ID to "project", TOOL_CONTEXT_CONVERSATION_ID to "conversation",
                TOOL_CONTEXT_THREAD_ID to "thread", TOOL_CONTEXT_AGENT_DEFINITION_ID to "agent", "toolCallId" to "call",
                TOOL_CONTEXT_WORKER_ID to "worker", TOOL_CONTEXT_WORKSPACE_ID to "workspace", TOOL_CONTEXT_WORKSPACE_MOUNT_ID to "mount", TOOL_CONTEXT_WORKSPACE_ROOT_PATH to "/captured-checkout"))
        @Volatile var operation = AsyncToolOperation(AsyncToolOperation.Id("operation"), invocation, now, AsyncToolOperation.Phase.CLAIMED,
            acknowledgedAt = now, claimedAt = now, executorSessionId = owner, leaseDeadline = now + 30.seconds)
        val records = TestWorkerRequestRepository()
        var loseSubmitAck = false
        private val childRepository = object : WorkerRequestRepository by records {
            override suspend fun create(request: StoredWorkerRequest) {
                check(operation.workerRequestCreatedAt == null && operation.acknowledgedAt != null)
                records.create(request)
                operation = operation.copy(workerRequestCreatedAt = request.createdAt)
                if (loseSubmitAck) { loseSubmitAck = false; throw IllegalStateException("Lost durable submit acknowledgement") }
            }
        }
        private val operations = Mockito.mock(AsyncToolOperationRepository::class.java) { call -> synchronized(this) {
            when (call.method.name.substringBefore('-')) {
                "find" -> operation
                "recordWorkerDispatch" -> {
                    val dispatched = records.records[operation.id.value]?.dispatchedAt
                    if (operation.phase != AsyncToolOperation.Phase.CLAIMED || dispatched == null) false
                    else { operation = operation.copy(phase = AsyncToolOperation.Phase.RUNNING, startedAt = dispatched); true }
                }
                "renewLease" -> if (operation.phase.isTerminal) false else { operation = operation.copy(leaseDeadline = call.arguments[2] as kotlin.time.Instant); true }
                "finish" -> {
                    val observed = call.arguments[0] as AsyncToolOperation
                    if (operation.phase != observed.phase || operation.phase.isTerminal) false else {
                        operation = operation.copy(phase = call.arguments[1] as AsyncToolOperation.Phase,
                            outcome = call.arguments[2] as AsyncToolOutcome, completedAt = call.arguments[3] as kotlin.time.Instant, leaseDeadline = null); true
                    }
                }
                else -> error("Unexpected parent operation ${call.method.name}")
            }
        } }
        val outcomes = AsyncToolOutcomeService(operations, Mockito.mock(AsyncToolScopeAccess::class.java))
        private val projector = AsyncWorkerResultProjector(operations, childRepository, outcomes)
        val requests = WorkerRequestService(childRepository, WorkerRequestAuthorization { }, listOf(projector))
        val gateway = GatewayWorkerToolExecutionClient(requests, 60_000, Mockito.mock(SlotApplicationService::class.java), projector)
        fun journal() = SnapshotWorkerRequestJournal(object : WorkerRequestSnapshotStore {
            private val path = home.resolve("journal.json")
            override suspend fun read() = if (Files.exists(path)) Files.readString(path) else null
            override suspend fun write(snapshot: String) {
                val temporary = home.resolve("journal.next")
                Files.writeString(temporary, snapshot)
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        })
        suspend fun execute(input: WorkerToolExecutionRequest): ToolExecutionResult = parallel.executeParallel(input.toolCalls,
            ToolExecutionContext(input.toolContext), null, ConversationRuntimeExecutorIdentity.Worker(identity), input.executionTarget,
            input.resolvedSecretsByToolCallId, input.preapprovedFingerprints)
        fun worker(journal: WorkerRequestJournal) = WorkerRequestExecutor(journal, scope, WorkerRequestHandler { request ->
            val input = json.decodeFromString<WorkerToolExecutionRequest>(request.payload.decodeToString())
            received += input
            val result = execute(input)
            WorkerGatewayMessage.Response(request.id, WorkerGatewayMessage.Response.Status.SUCCEEDED,
                json.encodeToString(WorkerToolExecutionResponse.serializer(), WorkerToolExecutionResponse(result.results, result.returnDirect, result.structuredContentByCallId)).encodeToByteArray())
        }, { responses.send(it) })
        suspend fun dispatch(): WorkerGatewayMessage.Request {
            val session = WorkerGatewaySession(identity)
            val delivery = scope.launch { requests.deliver(session) }
            return try {
                val request = withTimeout(5_000) { session.outgoingMessages().receive() as WorkerGatewayMessage.Request }
                WorkerGatewayCodec.decode(WorkerGatewayCodec.encode(request)) as WorkerGatewayMessage.Request
            } finally { delivery.cancelAndJoin() }
        }
        suspend fun close() {
            scope.coroutineContext.job.cancelAndJoin()
            Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
