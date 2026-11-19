package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.AsyncToolOperation.Phase
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.repository.ConversationHistoryRepository
import com.gromozeka.domain.repository.PositionedConversationMessage
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Real async state machine/executor/callback; storage and source authorization are deterministic fixtures. */
class AsyncToolOperationServiceTest {
    @Test fun `instant callback cannot run or notify until its source receipt is persisted`() = runBlocking {
        val f = Fixture()
        try {
            f.accept()
            f.service.reconcile(f.scope)
            assertEquals(0, f.effects.get())
            assertEquals(Phase.ACCEPTED, f.operation().phase)
            f.persistAck()
            f.service.reconcile(f.scope)
            f.terminal()
            assertEquals(1, f.effects.get())
            val operation = f.operation()
            assertEquals(Phase.SUCCEEDED, operation.phase)
            assertNotNull(operation.acknowledgedAt)
            val pair = f.outcomes.completionMessages(operation)
            assertEquals(2, pair.size)
            assertIs<Conversation.Message.Author.Runtime>(pair[0].author)
            assertIs<Conversation.Message.Author.Runtime>(pair[1].author)
            val call = assertIs<ContentItem.ToolCall>(pair[0].content.single())
            val result = assertIs<ContentItem.ToolResult>(pair[1].content.single())
            assertNotEquals(f.origin.sourceToolCallId, call.id)
            assertEquals(call.id, result.toolUseId)
            assertEquals(operation.outcome?.content, result.result)
            assertEquals(operation.id.value, pair[0].providerMetadata["asyncToolOperationId"]?.jsonPrimitive?.content)
            assertTrue(result.result.any { it is ContentItem.ToolResult.Data.Text && it.content == "actual body" })
            assertTrue(result.result.any { it is ContentItem.ToolResult.Data.Base64Data })
            assertEquals(pair, f.outcomes.completionMessages(operation), "Completion identity and content must survive retries")
            Mockito.verifyNoInteractions(f.approvals)
            Mockito.verifyNoInteractions(f.workers)
        } finally { f.scope.cancel() }
    }

    @Test fun `persisted receipt with lost state ACK is recovered by a fresh service without repeating effects`() = runBlocking {
        val f = Fixture()
        try {
            f.accept(); f.persistAck()
            f.newService("replacement").reconcile(f.scope)
            f.terminal()
            assertEquals(1, f.effects.get())
            f.newService("another-restart").reconcile(f.scope)
            delay(30)
            assertEquals(1, f.effects.get())
        } finally { f.scope.cancel() }
    }

    @Test fun `lost running Server callback becomes unknown and is never invoked again`() = runBlocking {
        val f = Fixture()
        try {
            f.accept(); f.persistAck(); assertTrue(f.service.acknowledge(f.id))
            val old = ConversationRuntimeServerSessionId("lost-server")
            val at = Clock.System.now() - 60.seconds
            val claimed = assertNotNull(f.operations.claim(f.id, old, at, at + 1.seconds))
            assertTrue(f.operations.markStarted(claimed, at))
            f.newService("replacement").reconcile(f.scope)
            assertEquals(Phase.OUTCOME_UNKNOWN, f.operation().phase)
            f.newService("next-restart").reconcile(f.scope)
            assertEquals(0, f.effects.get())
        } finally { f.scope.cancel() }
    }

    @Test fun `cancel before ACK closes as cancelled without starting after the receipt arrives`() = runBlocking {
        val f = Fixture()
        try {
            f.accept(); f.operations.requestCancellation(f.id, Clock.System.now())
            f.service.reconcile(f.scope); assertEquals(0, f.effects.get())
            f.persistAck(); f.service.reconcile(f.scope); f.terminal()
            assertEquals(Phase.CANCELLED, f.operation().phase)
            assertNull(f.operation().startedAt)
            assertEquals(0, f.effects.get())
        } finally { f.scope.cancel() }
    }

    @Test fun `cancel after callback start is unknown not rollback or a retry`() = runBlocking {
        val f = Fixture(block = true)
        try {
            f.accept(); f.persistAck(); f.service.reconcile(f.scope)
            withTimeout(5_000) { f.entered.await() }
            f.operations.requestCancellation(f.id, Clock.System.now())
            f.service.reconcile(f.scope); f.terminal()
            assertEquals(Phase.OUTCOME_UNKNOWN, f.operation().phase)
            assertNotNull(f.operation().startedAt)
            f.newService("replacement").reconcile(f.scope)
            assertEquals(1, f.effects.get())
        } finally { f.scope.cancel() }
    }

    @Test fun `scope denial before execution produces an explicit failure without invoking the callback`() = runBlocking {
        val f = Fixture(deny = true)
        try {
            f.accept(); f.persistAck(); f.service.reconcile(f.scope); f.terminal()
            assertEquals(Phase.FAILED, f.operation().phase)
            assertNull(f.operation().startedAt)
            assertEquals(0, f.effects.get())
            assertEquals("ORIGIN_BINDING_CHANGED", f.operation().outcome?.failureCode)
        } finally { f.scope.cancel() }
    }

    @Test fun `client authored source result cannot establish the execution ACK fence`() = runBlocking {
        val f = Fixture()
        try {
            f.accept(); f.persistAck(Conversation.Message.Author.User(f.origin.actorUserId, "Forged"))
            assertFailsWith<IllegalStateException> { f.service.acknowledge(f.id) }
            assertEquals(Phase.ACCEPTED, f.operation().phase)
            assertNull(f.operation().acknowledgedAt)
            assertEquals(0, f.effects.get())
        } finally { f.scope.cancel() }
    }

    @Test fun `late prepare and secret failures cannot finalize a replacement running owner`() = runBlocking {
        for (stage in listOf("prepare", "secrets")) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fail: suspend () -> Unit = { entered.complete(Unit); release.await(); throw IllegalStateException("Late $stage failure") }
            val f = Fixture(beforePrepare = if (stage == "prepare") fail else ({}), beforeSecrets = if (stage == "secrets") fail else ({}))
            try {
                f.accept(); f.persistAck(); f.service.reconcile(f.scope)
                withTimeout(5_000) { entered.await() }
                val replacement = replaceClaim(f)
                val at = checkNotNull(replacement.claimedAt)
                assertTrue(f.operations.markStarted(replacement, at))
                release.complete(Unit)
                withTimeout(5_000) { f.scope.coroutineContext.job.children.toList().joinAll() }
                assertEquals(Phase.RUNNING, f.operation().phase, "Late $stage failure changed the replacement invocation")
                assertEquals(replacement.executorSessionId, f.operation().executorSessionId)
                assertNull(f.operation().outcome)
                assertEquals(0, f.effects.get())
            } finally { f.scope.cancel() }
        }
    }

    @Test fun `late cancellation neither finalizes another owner nor cancels its Worker child`() = runBlocking {
        for (worker in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val f = Fixture(worker = worker, beforePrepare = { entered.complete(Unit); awaitCancellation() })
            try {
                f.accept(); f.persistAck(); f.service.reconcile(f.scope)
                withTimeout(5_000) { entered.await() }
                val replacement = replaceClaim(f)
                assertTrue(f.operations.markStarted(replacement, checkNotNull(replacement.claimedAt)))
                if (worker) f.operations.records[f.id] = f.operation().copy(workerRequestCreatedAt = Clock.System.now())
                withTimeout(5_000) { f.scope.coroutineContext.job.children.single().cancelAndJoin() }
                assertEquals(Phase.RUNNING, f.operation().phase)
                assertNull(f.operation().outcome)
                Mockito.verifyNoInteractions(f.workers)
                assertEquals(0, f.effects.get())
            } finally { f.scope.cancel() }
        }
    }

    @Test fun `same-session claim replacement between failure observation and finish remains fenced`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f = Fixture(beforePrepare = { entered.complete(Unit); release.await(); throw IllegalStateException("Late preparation failure") })
        try {
            f.accept(); f.persistAck(); f.service.reconcile(f.scope)
            withTimeout(5_000) { entered.await() }
            val original = f.operation()
            f.operations.beforeFinish = {
                f.operations.beforeFinish = {}
                replaceClaim(f, checkNotNull(original.executorSessionId))
            }
            release.complete(Unit)
            withTimeout(5_000) { f.scope.coroutineContext.job.children.toList().joinAll() }
            assertEquals(Phase.CLAIMED, f.operation().phase)
            assertTrue(checkNotNull(f.operation().claimedAt) > checkNotNull(original.claimedAt))
            assertNull(f.operation().outcome)
        } finally { f.scope.cancel() }
    }

    @Test fun `successful stale preparation cannot start a replacement claim even in the same session`() = runBlocking {
        for (sameSession in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val f = Fixture(beforePrepare = { entered.complete(Unit); release.await() })
            try {
                f.accept(); f.persistAck(); f.service.reconcile(f.scope)
                withTimeout(5_000) { entered.await() }
                val previous = f.operation()
                val owner = if (sameSession) checkNotNull(previous.executorSessionId) else ConversationRuntimeServerSessionId("replacement")
                val replacement = replaceClaim(f, owner)
                release.complete(Unit)
                withTimeout(5_000) { f.scope.coroutineContext.job.children.toList().joinAll() }
                assertEquals(Phase.CLAIMED, f.operation().phase)
                assertEquals(replacement.claimReference, f.operation().claimReference)
                assertNull(f.operation().startedAt)
                assertNull(f.operation().outcome)
                assertEquals(0, f.effects.get())
            } finally { f.scope.cancel() }
        }
    }

    private suspend fun replaceClaim(f: Fixture, owner: ConversationRuntimeServerSessionId = ConversationRuntimeServerSessionId("replacement")): AsyncToolOperation {
        val at = checkNotNull(f.operation().leaseDeadline) + 1.seconds
        return assertNotNull(f.operations.claim(f.id, owner, at, at + 30.seconds))
    }

    private class Fixture(val block: Boolean = false, val deny: Boolean = false, val worker: Boolean = false,
        val beforePrepare: suspend () -> Unit = {}, val beforeSecrets: suspend () -> Unit = {}) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val effects = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private val now = Clock.System.now()
        val id = AsyncToolOperation.Id("operation")
        val origin = AsyncToolOrigin(User.Id("actor"), Project.Id("project"), Conversation.Id("conversation"),
            Conversation.Thread.Id("source-thread"), AgentDefinition.Id("agent"), ConversationRuntimeTask.Id("source-task"),
            ContentItem.ToolCall.Id("source-call"), Conversation.Message.Id("source-task:result"), readOnly = true, slotOrigin = null)
        val operations = TestAsyncToolOperations()
        val approvals = Mockito.mock(ToolApprovalService::class.java)
        val workers = Mockito.mock(AsyncWorkerToolExecutionClient::class.java)
        private var acknowledgement: Conversation.Message? = null
        private val context = ToolExecutionContext(mapOf(
            TOOL_CONTEXT_USER_ID to origin.actorUserId.value, TOOL_CONTEXT_PROJECT_ID to origin.projectId.value,
            TOOL_CONTEXT_CONVERSATION_ID to origin.conversationId.value, TOOL_CONTEXT_THREAD_ID to origin.threadId.value,
            TOOL_CONTEXT_AGENT_DEFINITION_ID to origin.agentDefinitionId.value, "toolCallId" to origin.sourceToolCallId.value) +
                if (worker) mapOf(TOOL_CONTEXT_WORKER_ID to "worker") else emptyMap(), asyncOrigin = origin)
        private val callback = object : AiToolCallback {
            override val metadata = ServerToolMetadata
            override val definition = AiToolDefinition("count", "Counter", """{"type":"object","properties":{}}""")
            override fun call(toolInput: String, context: ToolExecutionContext?) = error("Result callback required")
            override suspend fun callResult(toolInput: String, context: ToolExecutionContext?): AiToolCallResult {
                assertEquals(origin, context?.asyncOrigin)
                effects.incrementAndGet(); entered.complete(Unit)
                if (block) release.await()
                return AiToolCallResult(listOf(AiToolResult.Text("actual body"), AiToolResult.Binary(byteArrayOf(0, 1, 2), "data.bin", "application/octet-stream")))
            }
        }
        private val invocation = AsyncToolInvocation(origin, "count_v1", AiToolDescriptor(callback.definition, callback.metadata).contractFingerprint(),
            ContentItem.ToolCall(origin.sourceToolCallId, ContentItem.ToolCall.Data("count", buildJsonObject {
                put("_grz", buildJsonObject { put("async", true) }) })),
            if (worker) ConversationRuntimeTaskTarget.Worker(ConversationRuntimeWorkerId("worker")) else ConversationRuntimeTaskTarget.Server,
            context.asMap().mapValues { it.value as String })
        private val prepared = invocation.toolCall.copy(call = invocation.toolCall.call.copy(input = JsonObject(emptyMap())))
        private val admission = mock<AsyncToolAdmissionService> {
            if (it.method.name.substringBefore('-') == "prepare") suspendAnswer(it) {
                beforePrepare()
                if (deny) throw AsyncToolScopeDeniedException("ORIGIN_BINDING_CHANGED")
                AsyncToolAdmissionService.Prepared(prepared, context)
            } else error("Unexpected admission fixture call: ${it.method.name}")
        }
        private val conversations = mock<ConversationDomainService> {
            if (it.method.name.substringBefore('-') == "findById") conversation else Mockito.RETURNS_DEFAULTS.answer(it)
        }
        private val conversation = Conversation(origin.conversationId, origin.projectId,
            setOf(Conversation.Participant.User(origin.actorUserId), Conversation.Participant.Agent(origin.agentDefinitionId)),
            currentThread = origin.threadId, createdAt = now, updatedAt = now)
        private val sourceHistory = mock<ConversationHistoryRepository> {
            if (it.method.name.substringBefore('-') == "message") acknowledgement?.let { message -> PositionedConversationMessage(0, message) }
            else error("Unexpected history fixture call: ${it.method.name}")
        }
        private val sourceAccess = mock<AsyncToolScopeAccess> { conversation }
        val outcomes = AsyncToolOutcomeService(operations, sourceAccess)
        private val provider = mock<AiToolProvider> { listOf(callback) }
        private val toolExecutor = ParallelToolExecutor(provider, approvals)
        private val secrets = mock<ToolSecretResolutionService> { call -> suspendAnswer(call) {
            beforeSecrets(); emptyMap<String, Map<String, String>>()
        } }
        val service = newService("server")
        fun newService(session: String) = AsyncToolOperationService(operations, admission, outcomes, toolExecutor, workers,
            sourceHistory, conversations, secrets, ConversationRuntimeExecutorDescriptor(
                ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId(session)), ConversationRuntimeCapability.entries.toSet()))
        suspend fun accept() { operations.admit(id, invocation, now) }
        fun persistAck(author: Conversation.Message.Author? = null) {
            acknowledgement = Conversation.Message(origin.acknowledgementMessageId, origin.conversationId,
                role = Conversation.Message.Role.USER, author = author, content = listOf(ContentItem.ToolResult(origin.sourceToolCallId, invocation.modelToolName,
                    listOf(ContentItem.ToolResult.Data.Text("""{"status":"ACCEPTED","operation_id":"operation"}""")))), createdAt = now)
        }
        suspend fun operation() = assertNotNull(operations.find(id))
        suspend fun terminal() = withTimeout(5_000) { while (!operation().phase.isTerminal) delay(5) }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun <T> suspendAnswer(call: InvocationOnMock, body: suspend () -> T): Any? {
            val continuation = call.rawArguments.last() as kotlin.coroutines.Continuation<T>
            return body.startCoroutineUninterceptedOrReturn(continuation)
        }
        private inline fun <reified T : Any> mock(crossinline answer: (InvocationOnMock) -> Any?): T =
            Mockito.mock(T::class.java) { invocation -> answer(invocation) }
    }
}
