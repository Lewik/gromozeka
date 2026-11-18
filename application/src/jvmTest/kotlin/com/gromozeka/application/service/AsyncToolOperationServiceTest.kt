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
            val message = f.outcomes.notification(operation)
            assertEquals(Conversation.Message.Role.RUNTIME_NOTIFICATION, message.role)
            assertIs<Conversation.Message.Author.Runtime>(message.author)
            val event = assertIs<ContentItem.RuntimeNotification>(message.content.single())
            assertEquals(f.origin.sourceToolCallId, event.toolUseId)
            assertEquals(operation.id.value, event.referenceId)
            assertTrue(event.attachments.any { it is ContentItem.ToolResult.Data.Text && it.content == "actual body" })
            assertTrue(event.attachments.any { it is ContentItem.ToolResult.Data.Base64Data })
            assertTrue(message.content.none { it is ContentItem.ToolCall || it is ContentItem.ToolResult })
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
            assertTrue(f.operations.claim(f.id, old, at, at + 1.seconds))
            assertTrue(f.operations.markStarted(f.id, old, at))
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

    private class Fixture(val block: Boolean = false, val deny: Boolean = false) {
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
            TOOL_CONTEXT_AGENT_DEFINITION_ID to origin.agentDefinitionId.value, "toolCallId" to origin.sourceToolCallId.value), asyncOrigin = origin)
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
                put("_grz", buildJsonObject { put("async", true) }) })), ConversationRuntimeTaskTarget.Server,
            context.asMap().mapValues { it.value as String })
        private val prepared = invocation.toolCall.copy(call = invocation.toolCall.call.copy(input = JsonObject(emptyMap())))
        private val admission = mock<AsyncToolAdmissionService> {
            if (it.method.name.substringBefore('-') == "prepare") {
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
        private val secrets = mock<ToolSecretResolutionService> { emptyMap<String, Map<String, String>>() }
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
        private inline fun <reified T : Any> mock(crossinline answer: (InvocationOnMock) -> Any?): T =
            Mockito.mock(T::class.java) { invocation -> answer(invocation) }
    }
}
