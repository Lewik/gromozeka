package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.model.memory.MemoryRun
import kotlinx.serialization.json.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import kotlin.test.*
import kotlin.time.Instant

/** Real engine and coordinator; the model is deterministic and never uses a subscription. */
class ConversationEngineDeliveryTest {
    private val now = Instant.fromEpochSeconds(1)
    private val user = User.Id("user")
    private val bob = User.Id("bob")
    private val project = Project(Project.Id("project"), "Test", createdAt = now, lastUsedAt = now)
    private val agentId = AgentDefinition.Id("agent")
    private val otherAgentId = AgentDefinition.Id("other-agent")
    private val conversation = Conversation(Conversation.Id("conversation"), project.id,
        setOf(Conversation.Participant.User(user), Conversation.Participant.User(bob), Conversation.Participant.Agent(agentId), Conversation.Participant.Agent(otherAgentId)),
        currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now)
    private val executor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server"))
    private val requirements = ConversationRuntimeTaskRequirements(ConversationRuntimeCapability.entries.toSet(), ConversationRuntimeTaskTarget.Server)
    private fun input(id: String, actor: User.Id = user, broadcast: Boolean = false): ConversationRuntimeTask {
        val message = Conversation.Message(Conversation.Message.Id(id), conversation.id, role = Conversation.Message.Role.USER,
            author = Conversation.Message.Author.User(actor, actor.value), content = listOf(ContentItem.UserMessage(id)), createdAt = now)
        return ConversationRuntimeTask(ConversationRuntimeTask.Id(id), conversation.id, actorUserId = actor,
            payload = if (broadcast) ConversationRuntimeTask.Payload.PostMessage(message, setOf(agentId))
                else ConversationRuntimeTask.Payload.AgentInvocation(message, agentId),
            placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = id, requirements = requirements, createdAt = now)
    }
    private fun answer(outcome: AiStepOutcome = AiStepOutcome.COMPLETE) = AiRuntimeResponse(
        listOf(AiAssistantMessage(listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText("Answer"))))), outcome = outcome)

    @Test fun `queued idle bursts produce one request containing every distinct message in either mode`() = runBlocking {
        for (mode in UserMessageDeliveryMode.entries) {
            val f = Fixture(); f.messages.clear()
            for ((index, id) in listOf("a", "b", "c").withIndex()) {
                assertTrue(f.coordinator.submitUserInput(input(id, broadcast = index % 2 == 0), mode))
            }
            f.drain()
            val request = f.requests.single()
            assertEquals(listOf("a", "b", "c"), request.messages.map { it.id.value })
            assertEquals("user", request.options.toolContext[TOOL_CONTEXT_USER_ID])
            assertTrue(request.messages.none { it.instructions.contains(liveSteeringInstruction) })
            assertEquals(listOf("a", "b", "c"), f.emitted.filter { it.author is Conversation.Message.Author.User }.map { it.id.value })
            assertTrue(f.coordinator.listPending(conversation.id).isEmpty())
        }
    }

    @Test fun `after turn backlog starts one new response for the entire batch`() = runBlocking {
        val f = Fixture(); val running = f.begin()
        for (id in listOf("a", "b", "c")) f.coordinator.submitUserInput(input(id), UserMessageDeliveryMode.AFTER_CURRENT_TURN)
        val outcome = f.engine.runRuntimeTask(running, executor) {}
        assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(outcome)
        assertEquals(listOf("root"), f.requests.single().messages.map { it.id.value })
        assertTrue(f.coordinator.completeActiveTask(conversation.id, running.id, executor, outcome))
        f.drain()
        assertEquals(2, f.requests.size)
        assertEquals(listOf("root", "a", "b", "c"), f.requests.last().messages.filter { it.author is Conversation.Message.Author.User }.map { it.id.value })
    }

    @Test fun `a single safe point delivers all steer messages before one model response`() = runBlocking {
        val f = Fixture(); val running = f.begin()
        for (id in listOf("a", "b", "c")) f.coordinator.submitUserInput(input(id, broadcast = true), UserMessageDeliveryMode.STEER)
        val outcome = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(running, executor) {})
        assertTrue(f.requests.isEmpty())
        assertEquals(listOf("a", "b", "c"), f.messages.takeLast(3).map { it.id.value })
        assertTrue(f.messages.takeLast(3).all { it.instructions.contains(liveSteeringInstruction) })
        assertTrue(f.coordinator.completeActiveTask(conversation.id, running.id, executor, outcome))
        f.drain()
        assertEquals(listOf("root", "a", "b", "c"), f.requests.single().messages.map { it.id.value })
    }

    @Test fun `late after-turn input does not leak into preparation of the already claimed batch`() = runBlocking {
        for (hasFollower in listOf(false, true)) {
            val f = Fixture(); f.messages.clear()
            f.coordinator.submitUserInput(input("a"), UserMessageDeliveryMode.AFTER_CURRENT_TURN)
            if (hasFollower) f.coordinator.submitUserInput(input("b"), UserMessageDeliveryMode.AFTER_CURRENT_TURN)
            f.onAppend = { message ->
                if (message.id.value == "a") {
                    assertTrue(f.coordinator.submitUserInput(input("late"), UserMessageDeliveryMode.AFTER_CURRENT_TURN))
                }
            }
            f.drain()
            assertEquals(2, f.requests.size)
            assertEquals(if (hasFollower) listOf("a", "b") else listOf("a"), f.requests.first().messages.map { it.id.value })
            assertTrue(f.requests.last().messages.any { it.id.value == "late" })
        }
    }

    @Test fun `broadcast batch invokes each selected agent once rather than once per message`() = runBlocking {
        val f = Fixture(); f.messages.clear()
        for (id in listOf("a", "b", "c")) {
            val task = input(id)
            val post = task.copy(payload = ConversationRuntimeTask.Payload.PostMessage(requireNotNull(task.userMessageOrNull()), setOf(otherAgentId, agentId)))
            f.coordinator.submitUserInput(post, UserMessageDeliveryMode.AFTER_CURRENT_TURN)
        }
        f.drain()
        assertEquals(2, f.requests.size)
        for (request in f.requests) {
            assertEquals(listOf("a", "b", "c"), request.messages.filter { it.author is Conversation.Message.Author.User }.map { it.id.value })
        }
        assertEquals(listOf("a", "b", "c"), f.messages.filter { it.author is Conversation.Message.Author.User }.map { it.id.value })
    }

    @Test fun `different actor is a batch boundary and retains its own model tool authority`() = runBlocking {
        val f = Fixture(); f.messages.clear()
        for (task in listOf(input("a"), input("b", bob), input("c", bob), input("d"))) {
            f.coordinator.submitUserInput(task, UserMessageDeliveryMode.AFTER_CURRENT_TURN)
        }
        f.drain()
        assertEquals(listOf("user", "bob", "user"), f.requests.map { it.options.toolContext[TOOL_CONTEXT_USER_ID] })
        assertEquals(listOf(listOf("a"), listOf("a", "b", "c"), listOf("a", "b", "c", "d")),
            f.requests.map { request -> request.messages.filter { it.author is Conversation.Message.Author.User }.map { it.id.value } })
    }

    @Test fun `partially appended reserved batch is recovered without duplicating messages`() = runBlocking {
        val f = Fixture(); f.messages.clear()
        val root = input("a")
        for (task in listOf(root, input("b"), input("c"))) f.coordinator.submitUserInput(task, UserMessageDeliveryMode.AFTER_CURRENT_TURN)
        f.claim(root)
        f.onAppend = { if (it.id.value == "b") error("synthetic interruption") }
        assertFailsWith<IllegalStateException> { f.engine.runRuntimeTask(root, executor) {} }
        assertEquals(listOf("a", "b"), f.messages.map { it.id.value })
        assertTrue(f.requests.isEmpty())
        f.coordinator.abort(conversation.id)
        assertTrue(f.coordinator.requestResume(conversation.id))
        f.onAppend = {}
        f.drain()
        assertEquals(listOf("a", "b", "c"), f.requests.single().messages.map { it.id.value })
        assertEquals(listOf("a", "b", "c"), f.messages.filter { it.author is Conversation.Message.Author.User }.map { it.id.value })
    }

    @Test fun `queued broadcast is delivered before a new model call and preserves human provenance`() = runBlocking {
        val f = Fixture(); val task = f.begin()
        assertTrue(f.coordinator.submitUserInput(input("steer", bob, broadcast = true), UserMessageDeliveryMode.STEER))
        val next = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(task, executor) { f.emitted += it }).nextTask
        assertTrue(f.requests.isEmpty())
        assertEquals(bob, next.actorUserId)
        assertEquals(listOf("steer"), f.emitted.map { it.id.value })
        assertTrue(f.messages.last().instructions.contains(liveSteeringInstruction))
        f.advance(task, next)
        assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(f.engine.runRuntimeTask(next, executor) {})
        assertEquals("bob", f.requests.single().options.toolContext[TOOL_CONTEXT_USER_ID])
        assertTrue(f.requests.single().messages.any { it.id.value == "steer" })
    }
    @Test fun `steer during model-only response waits for call and continues the same turn`() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val task = f.begin()
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            f.onCall = { started.complete(Unit); finish.await(); answer() }
            val running = async { f.engine.runRuntimeTask(task, executor) { f.emitted += it } }
            started.await()
            f.coordinator.submitUserInput(input("steer"), UserMessageDeliveryMode.STEER)
            yield()
            assertFalse(running.isCompleted)
            assertFalse(f.messages.any { it.id.value == "steer" })
            finish.complete(Unit)
            val next = assertIs<ConversationRuntimeTaskOutcome.Continue>(running.await()).nextTask
            assertEquals(task.turnId, next.turnId)
            assertEquals(listOf(Conversation.Message.Role.USER, Conversation.Message.Role.ASSISTANT, Conversation.Message.Role.USER), f.messages.map { it.role })
            f.advance(task, next)
            f.onCall = { answer() }
            assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(f.engine.runRuntimeTask(next, executor) {})
            assertTrue(f.requests.last().messages.any { it.id.value == "steer" })
        }
    }
    @Test fun `after turn does not enter model-only continuations`() = runBlocking {
        val f = Fixture(); val task = f.begin()
        f.onCall = {
            f.coordinator.submitUserInput(input("later"), UserMessageDeliveryMode.AFTER_CURRENT_TURN)
            answer(AiStepOutcome.CONTINUE)
        }
        val next = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(task, executor) {}).nextTask
        f.advance(task, next); f.onCall = { answer() }
        val outcome = f.engine.runRuntimeTask(next, executor) {}
        assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(outcome)
        assertTrue(f.requests.all { request -> request.messages.none { it.id.value == "later" } })
        f.coordinator.completeActiveTask(conversation.id, next.id, executor, outcome)
        assertEquals("later", f.coordinator.listPending(conversation.id).single().id.value)
    }
    @Test fun `tool results remain ahead of steer even with return direct`() = runBlocking {
        for (mode in UserMessageDeliveryMode.entries) {
            val f = Fixture(); val model = f.begin()
            val toolResult = Conversation.Message(Conversation.Message.Id("result"), conversation.id,
                role = Conversation.Message.Role.USER, content = listOf(ContentItem.ToolResult(
                    ContentItem.ToolCall.Id("call"), "test", listOf(ContentItem.ToolResult.Data.Text("done")), false)), createdAt = now)
            val processing = model.copy(id = ConversationRuntimeTask.Id("processing"), parentTaskId = model.id,
                idempotencyKey = "processing", payload = ConversationRuntimeTask.Payload.ToolResultProcessing(
                    Conversation.Message.Id("root"), toolResult.id, agentId, 1, returnDirect = true))
            f.advance(model, processing)
            f.messages += toolResult
            f.coordinator.submitUserInput(input("new"), mode)
            val result = f.engine.runRuntimeTask(processing, executor) {}
            if (mode == UserMessageDeliveryMode.STEER) {
                assertIs<ConversationRuntimeTaskOutcome.Continue>(result)
                assertEquals(listOf("result", "new"), f.messages.takeLast(2).map { it.id.value })
            } else {
                assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(result)
                assertEquals(toolResult, f.messages.last())
            }
            assertTrue(f.requests.isEmpty())
        }
    }
    @Test fun `multiple steering users do not inherit the original callers credentials`() = runBlocking {
        val f = Fixture(); val task = f.begin()
        f.coordinator.submitUserInput(input("a", user), UserMessageDeliveryMode.STEER)
        f.coordinator.submitUserInput(input("b", bob), UserMessageDeliveryMode.STEER)
        val next = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(task, executor) {}).nextTask
        assertNull(next.actorUserId)
        assertEquals(listOf("a", "b"), f.messages.takeLast(2).map { it.id.value })
    }

    @Test fun `steer invalidates an unpublished response review and preserves replay without stale visible answer`() = runBlocking {
        val f = Fixture(enableReview = true); val model = f.begin()
        val stale = Conversation.Message(Conversation.Message.Id("stale"), conversation.id, role = Conversation.Message.Role.ASSISTANT,
            content = listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText("Stale answer"))), createdAt = now)
        f.draft = AgentResponseDraft("draft", AgentContextRef(conversation.id, conversation.currentThread, agentId), user,
            Conversation.Message.Id("root"), model.turnId.value, 1, listOf(stale), emptyList(), emptyList(), emptyList(), "", listOf("root"), now)
        val task = model.copy(id = ConversationRuntimeTask.Id("review"), parentTaskId = model.id, idempotencyKey = "review",
            payload = ConversationRuntimeTask.Payload.ResponseReview("draft", agentId))
        f.advance(model, task)
        f.coordinator.submitUserInput(input("new", bob, broadcast = true), UserMessageDeliveryMode.STEER)
        val next = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(task, executor) { f.emitted += it }).nextTask
        assertEquals(bob, next.actorUserId)
        assertTrue(f.requests.isEmpty(), "Queued steer must bypass stale response checking")
        assertTrue(f.emitted.flatMap { it.content }.filterIsInstance<ContentItem.AssistantMessage>().isEmpty())
        assertTrue(f.messages.first { it.id == stale.id }.providerMetadata.isNotEmpty(), "Original provider output is retained for replay")
        assertEquals("new", f.emitted.last().id.value)
        assertTrue(Mockito.mockingDetails(f.reviewRepository).invocations.none { it.method.name.startsWith("applyDecision") })
    }

    @Test fun `pin add edit delete affects next ordinary or post-tool call but never the in-flight request`() = runBlocking<Unit> {
        val f = Fixture(withEnricher = true); val first = f.begin()
        f.onCall = { request ->
            f.pins.values = listOf(testPin(conversation.id, "PIN_ADDED"))
            assertFalse(request.systemPrompts.any { "PIN_ADDED" in it })
            answer(AiStepOutcome.CONTINUE)
        }
        val next = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(first, executor) {}).nextTask
        f.advance(first, next)
        val callId = ContentItem.ToolCall.Id("pin-tool")
        f.messages += Conversation.Message(Conversation.Message.Id("tool-call"), conversation.id,
            role = Conversation.Message.Role.ASSISTANT, content = listOf(ContentItem.ToolCall(callId,
                ContentItem.ToolCall.Data("test", kotlinx.serialization.json.JsonObject(emptyMap())))), createdAt = now)
        val toolResult = Conversation.Message(Conversation.Message.Id("tool-result"), conversation.id,
            role = Conversation.Message.Role.USER, content = listOf(ContentItem.ToolResult(callId, "test",
                listOf(ContentItem.ToolResult.Data.Text("done")), false)), createdAt = now)
        f.messages += toolResult
        val processing = next.copy(id = ConversationRuntimeTask.Id("process-pins"), parentTaskId = next.id,
            idempotencyKey = "process-pins", requirements = requirements, payload = ConversationRuntimeTask.Payload.ToolResultProcessing(
                Conversation.Message.Id("root"), toolResult.id, agentId, 2, returnDirect = false))
        f.advance(next, processing)
        val postTool = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(processing, executor) {}).nextTask
        assertEquals(1, f.pins.reads.size, "Tool result processing itself is not a model request")
        f.advance(processing, postTool)
        f.onCall = { request ->
            assertTrue(request.systemPrompts.last().contains("PIN_ADDED"))
            f.pins.values = listOf(testPin(conversation.id, "PIN_EDITED"))
            assertFalse(request.systemPrompts.last().contains("PIN_EDITED"))
            answer(AiStepOutcome.CONTINUE)
        }
        val edited = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(postTool, executor) {}).nextTask
        f.advance(postTool, edited)
        f.onCall = { request ->
            assertTrue(request.systemPrompts.last().contains("PIN_EDITED"))
            f.pins.values = emptyList()
            answer(AiStepOutcome.CONTINUE)
        }
        val deleted = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(edited, executor) {}).nextTask
        f.advance(edited, deleted); f.onCall = { answer() }
        f.engine.runRuntimeTask(deleted, executor) {}
        assertEquals(List(4) { conversation.id }, f.pins.reads)
        assertEquals(f.requests.first().systemPrompts, f.requests.last().systemPrompts)
        assertEquals("ENRICHER_LAST", f.requests[1].systemPrompts[f.requests[1].systemPrompts.lastIndex - 1])
        assertTrue(f.requests[1].messages.any { it.id == toolResult.id })
        assertFalse(f.messages.any { message -> message.content.any { it is ContentItem.UserMessage && "PIN_" in it.text } })
    }

    @Test fun `model preparation loads checkpoint context rather than the archive`() = runBlocking {
        val f = Fixture(); f.allowFullHistoryLoad = false
        val root = f.messages.single()
        val archive = (0 until 100).map { root.copy(id = Conversation.Message.Id("old-$it")) }
        val checkpoint = root.copy(id = Conversation.Message.Id("checkpoint"), role = Conversation.Message.Role.ASSISTANT,
            content = listOf(ContentItem.ContextCompactionResult(
                ContentItem.ContextCompactionResult.Payload.ReadableSummary("summary"), ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
            )))
        f.messages.clear(); f.messages += archive + checkpoint + root
        val original = f.messages.toList()
        val task = f.begin()
        f.engine.runRuntimeTask(task, executor) {}
        assertEquals(listOf<String?>("OPENAI_SUBSCRIPTION"), f.contextReads)
        val request = f.requests.single()
        assertEquals(3, request.messages.size)
        assertEquals(listOf(checkpoint.id, root.id), request.projectedMessages("OPENAI_SUBSCRIPTION").map { it.id })
        assertEquals(original, f.messages.take(original.size))
    }

    @Test fun `point append and replay never load the archive`() = runBlocking {
        val f = Fixture(); f.allowFullHistoryLoad = false
        val task = input("new-message")
        assertTrue(f.coordinator.submit(task)); f.claim(task)
        repeat(2) {
            assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(task, executor) { f.emitted += it })
        }
        assertEquals(1, f.messages.count { it.id.value == "new-message" })
        assertEquals(listOf("new-message"), f.emitted.map { it.id.value })
        assertTrue(f.requests.isEmpty())
    }

    @Test fun `point tool result processing never loads the archive or reapplies the result`() = runBlocking {
        val f = Fixture(); f.allowFullHistoryLoad = false
        val result = Conversation.Message(Conversation.Message.Id("tool-result"), conversation.id,
            role = Conversation.Message.Role.USER, content = listOf(ContentItem.ToolResult(
                ContentItem.ToolCall.Id("call"), "synthetic", listOf(ContentItem.ToolResult.Data.Text("result")))), createdAt = now)
        f.messages += result
        val running = f.begin()
        val task = running.copy(id = ConversationRuntimeTask.Id("process-result"), parentTaskId = running.id,
            payload = ConversationRuntimeTask.Payload.ToolResultProcessing(
                Conversation.Message.Id("root"), result.id, agentId, 1, returnDirect = false))
        f.advance(running, task)
        repeat(2) {
            assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(task, executor) { f.emitted += it })
        }
        assertEquals(1, f.messages.count { it.id == result.id })
        assertTrue(f.emitted.isEmpty()); assertTrue(f.requests.isEmpty())
    }

    @Test fun `point tool execution replays the stored result without repeating effects or loading history`() = runBlocking {
        val f = Fixture(); f.allowFullHistoryLoad = false
        val running = f.begin()
        val call = ContentItem.ToolCall(ContentItem.ToolCall.Id("synthetic-call"),
            ContentItem.ToolCall.Data("synthetic", kotlinx.serialization.json.JsonObject(emptyMap())))
        val payload = ConversationRuntimeTask.Payload.ToolExecution(Conversation.Message.Id("root"), agentId, 1,
            listOf(call), false, mapOf(call.id.value to ConversationRuntimeTaskTarget.Server))
        val task = running.copy(id = ConversationRuntimeTask.Id("execute-tool"), parentTaskId = running.id, payload = payload)
        f.advance(running, task)
        repeat(2) {
            val next = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.toolTasks.run(task, executor, payload) { f.emitted += it }).nextTask
            assertEquals(Conversation.Message.Id("execute-tool:result"),
                assertIs<ConversationRuntimeTask.Payload.ToolResultProcessing>(next.payload).toolResultMessageId)
        }
        assertEquals(1, f.toolEffects)
        assertEquals(0, f.historyMessageReads)
        assertEquals(1, f.messages.count { it.id.value == "execute-tool:result" })
        assertEquals(listOf("execute-tool:result"), f.emitted.map { it.id.value })
    }

    @Test fun `point tool result processing fails when the result is absent from the current thread`() = runBlocking {
        val f = Fixture(); f.allowFullHistoryLoad = false
        val running = f.begin()
        val task = running.copy(id = ConversationRuntimeTask.Id("missing-result"), parentTaskId = running.id,
            payload = ConversationRuntimeTask.Payload.ToolResultProcessing(
                Conversation.Message.Id("root"), Conversation.Message.Id("not-in-current-thread"), agentId, 1, false))
        f.advance(running, task)
        val error = assertFailsWith<IllegalStateException> { f.engine.runRuntimeTask(task, executor) {} }
        assertTrue(error.message.orEmpty().startsWith("Tool result message not found:"))
        assertTrue(f.requests.isEmpty()); assertTrue(f.emitted.isEmpty())
    }

    @Test fun `background replay preserves an old call ID when only its call was stored before upgrade`() = runBlocking {
        val f = Fixture(); f.allowFullHistoryLoad = false
        val oldId = ContentItem.ToolCall.Id("cmd_legacy_long_identifier_status")
        val shortId = syntheticToolCallId(oldId.value)
        val call = f.messages.single().copy(id = Conversation.Message.Id("completion-call"), role = Conversation.Message.Role.ASSISTANT,
            content = listOf(ContentItem.ToolCall(shortId, ContentItem.ToolCall.Data("grz_get_command_task", kotlinx.serialization.json.JsonObject(emptyMap())))))
        val result = call.copy(id = Conversation.Message.Id("completion-result"), role = Conversation.Message.Role.USER,
            content = listOf(ContentItem.ToolResult(shortId, "grz_get_command_task", listOf(ContentItem.ToolResult.Data.Text("done")))))
        f.messages += call.copy(content = listOf((call.content.single() as ContentItem.ToolCall).copy(id = oldId)))
        f.backgroundBatch = BackgroundActivityCompletionApplicationService.Batch(listOf(mock<CommandTask>()), emptyList(), listOf(call, result))
        val task = input("background").copy(
            payload = ConversationRuntimeTask.Payload.BackgroundActivityCompletion("command-task:legacy"))
        assertTrue(f.coordinator.submit(task)); f.claim(task)
        repeat(2) { assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(f.engine.runRuntimeTask(task, executor) {}) }
        assertEquals(1, f.messages.count { it.id == call.id })
        val stored = f.messages.single { it.id == result.id }.content.single() as ContentItem.ToolResult
        assertEquals(oldId, stored.toolUseId)
        assertEquals(1, f.messages.count { it.id == result.id })
    }

    @Test fun `synthetic memory pairs use short IDs and retain stored legacy IDs on replay`() = runBlocking {
        for (withLegacyCall in listOf(false, true)) {
            val f = Fixture(); f.allowFullHistoryLoad = false
            val runId = MemoryRun.Id("memory-run")
            val toolName = "memory_run_status"
            val phase = "completed_async"
            val legacyId = ContentItem.ToolCall.Id("mem_memoryrun_memoryrunstatus_completedasync")
            val callMessageId = Conversation.Message.Id("${runId.value}:memory:$toolName:$phase:call")
            if (withLegacyCall) f.messages += f.messages.single().copy(id = callMessageId,
                role = Conversation.Message.Role.ASSISTANT,
                providerMetadata = buildJsonObject { put("synthetic", true); put("syntheticKind", "memory") },
                content = listOf(ContentItem.ToolCall(legacyId, ContentItem.ToolCall.Data(toolName, JsonObject(emptyMap())))))
            val task = input("memory-completed").copy(payload = ConversationRuntimeTask.Payload.MemoryRunCompletion(runId, agentId, toolName))
            assertTrue(f.coordinator.submit(task)); f.claim(task)
            repeat(2) { assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(task, executor) {}) }
            val calls = f.messages.flatMap { it.content }.filterIsInstance<ContentItem.ToolCall>()
            val results = f.messages.flatMap { it.content }.filterIsInstance<ContentItem.ToolResult>()
            assertEquals(1, calls.size); assertEquals(1, results.size)
            assertEquals(if (withLegacyCall) legacyId else syntheticToolCallId(legacyId.value), calls.single().id)
            assertEquals(calls.single().id, results.single().toolUseId)
            assertEquals(1, f.messages.count { it.id == callMessageId })
            val projected = AiRuntimeRequest(emptyList(), f.messages).projectedMessages()
            val projectedId = projected.flatMap { it.content }.filterIsInstance<ContentItem.ToolCall>().single().id
            assertTrue(Regex("[A-Za-z0-9]{9}").matches(projectedId.value))
            assertEquals(projectedId, projected.flatMap { it.content }.filterIsInstance<ContentItem.ToolResult>().single().toolUseId)
        }
    }

    @Test fun `COW compaction keeps the root and queued steering and consumes secrets only for inference`() = runBlocking {
        val f = Fixture()
        val root = f.messages.single()
        val running = f.begin()
        f.pendingSecrets.queue(conversation.id, user, mapOf("fixture" to "approved-model-only-value"))
        f.onCompact = { request ->
            assertTrue(f.requests.isEmpty())
            assertFalse(request.messages.any { message -> message.instructions.any { it is Conversation.Message.Instruction.RevealedSecretRuntimeContext } })
            val target = Conversation.Thread.Id("compacted-thread")
            f.messages += root.copy(id = Conversation.Message.Id("checkpoint"), role = Conversation.Message.Role.ASSISTANT,
                author = Conversation.Message.Author.Agent(agentId, "Test"),
                content = listOf(ContentItem.ContextCompactionResult(
                    payload = ContentItem.ContextCompactionResult.Payload.ReadableSummary("Continue the root task"),
                    origin = ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY,
                    sourceMessageIds = f.messages.map { it.id }, coverage = ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES,
                    historyTransition = ContentItem.ContextCompactionResult.HistoryTransition(f.currentConversation.currentThread, target),
                )))
            f.currentConversation = f.currentConversation.copy(currentThread = target)
            f.onCompact = { null }
            f.currentConversation
        }
        val outcome = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(running, executor) { f.emitted += it })
        assertTrue(f.requests.isEmpty())
        assertTrue(f.pendingSecrets.estimatedInputTokens(conversation.id, user) > 0)
        val continuation = assertIs<ConversationRuntimeTask.Payload.LlmCall>(outcome.nextTask.payload)
        assertTrue(continuation.afterCompaction)
        assertEquals(root.id, continuation.rootUserMessageId)
        assertEquals(running.turnId, outcome.nextTask.turnId)
        assertTrue(f.coordinator.completeActiveTask(conversation.id, running.id, executor, outcome))
        assertTrue(f.coordinator.submitUserInput(input("late-steering"), UserMessageDeliveryMode.STEER))
        f.drain()
        val request = f.requests.single()
        assertEquals("compacted-thread", request.options.toolContext["threadId"])
        assertEquals(root.id.value, request.options.toolContext[com.gromozeka.domain.tool.TOOL_CONTEXT_TARGET_MESSAGE_ID])
        assertTrue(request.messages.any { it.id.value == "late-steering" })
        val secrets = request.messages.flatMap { it.instructions }.filterIsInstance<Conversation.Message.Instruction.RevealedSecretRuntimeContext>()
        assertEquals(1, secrets.size)
        assertContains(secrets.single().serializeContent(), "approved-model-only-value")
        assertEquals(0, f.pendingSecrets.estimatedInputTokens(conversation.id, user))
        assertEquals(root, f.messages.single { it.id == root.id })
        assertEquals(listOf(false, true), f.compactionChecks.map { it.second })
    }

    private inner class Fixture(enableReview: Boolean = false, withEnricher: Boolean = false) {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        var currentConversation = conversation
        val pendingSecrets = PendingSecretRevealService()
        var onCompact: (AiRuntimeRequest) -> Conversation? = { null }
        val compactionChecks = mutableListOf<Pair<AiRuntimeRequest, Boolean>>()
        private val compactions = responding<ConversationCompactionService> {
            if (it.method.name.startsWith("compactIfNeeded")) {
                val request = it.arguments[4] as AiRuntimeRequest
                compactionChecks += request to (it.arguments[5] as Boolean)
                onCompact(request)
            } else Mockito.RETURNS_DEFAULTS.answer(it)
        }
        val pins = ReadOnlyTestPins()
        val messages = mutableListOf(requireNotNull(input("root").userMessageOrNull()))
        val emitted = mutableListOf<Conversation.Message>()
        val requests = mutableListOf<AiRuntimeRequest>()
        var onCall: suspend (AiRuntimeRequest) -> AiRuntimeResponse = { answer() }
        var onAppend: suspend (Conversation.Message) -> Unit = {}
        var allowFullHistoryLoad = true
        var backgroundBatch: BackgroundActivityCompletionApplicationService.Batch? = null
        private val backgroundActivities = responding<BackgroundActivityCompletionApplicationService> {
            when (it.method.name.substringBefore('-')) {
                "prepareBatch" -> checkNotNull(backgroundBatch)
                "hasPendingConversationWork", "canContinue" -> false
                "markDelivered" -> Unit
                else -> Mockito.RETURNS_DEFAULTS.answer(it)
            }
        }
        private val runtime = object : AiRuntime {
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse { requests += request; return onCall(request) }
            override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = error("No streaming")
        }
        private val selection = AiRuntimeSelection(AiModelConfiguration.Id("config"))
        private val agent = AgentDefinition(agentId, name = "Test", prompts = emptyList(), runtimeSelection = selection,
            type = AgentDefinition.Type.Global, createdAt = now, updatedAt = now)
        private val connection = AiConnection.OpenAiSubscription(AiConnection.Id("test"), "Test", enabled = true)
        private val resolved = ResolvedAiRuntime(connection,
            AiModelConfiguration(selection.modelConfigurationId, connection.id, "test-model", "Test"),
            AiModelSpec("test-model", AiProvider.OPENAI, capabilities = setOf(AiModelCapability.TEXT_GENERATION),
                limits = AiModelSpec.Limits(textGeneration = AiModelSpec.Limits.TextGeneration(contextWindowTokens = 1000))))
        private val catalog = DistributedAiToolCatalogSnapshot(emptyList(), emptyMap(), emptyList(), "revision", "environment")
        private val conversations = responding<ConversationDomainService> {
            when (it.method.name.substringBefore('-')) {
                "findById" -> currentConversation; "getProject" -> project
                "loadCurrentMessages" -> {
                    check(allowFullHistoryLoad) { "Point operation must not load the archive" }
                    messages.toList()
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(it)
            }
        }
        private val appender = object : ConversationRuntimeMessageAppender {
            override suspend fun appendRuntimeMessage(conversationId: Conversation.Id, message: Conversation.Message): Conversation {
                messages += message; onAppend(message); return currentConversation
            }
        }
        private val artifacts = responding<ConversationArtifactApplicationService> {
            when (it.method.name.substringBefore('-')) {
                "materialize" -> it.arguments[1]
                "persistAndCommitMessageToolResults", "persistAndCommitToolResults" -> it.arguments[2]
                else -> Mockito.RETURNS_DEFAULTS.answer(it)
            }
        }
        val contextReads = mutableListOf<String?>()
        private val contextHistory = responding<com.gromozeka.domain.repository.ThreadMessageRepository> {
            check(it.method.name.substringBefore('-') == "getContextMessagesByThread")
            assertEquals(currentConversation.currentThread.value, it.arguments[0])
            val provider = it.arguments[1] as String?
            contextReads += provider
            val selected = selectModelHistoryMessageIds(
                messages.map { message -> MessageContextHeader(message.id, MessageContextMetadata.from(message)) },
                messages.filter { message -> MessageContextMetadata.from(message).hasCompaction }, provider,
            )
            messages.filter { message -> message.id in selected }
        }
        var historyMessageReads = 0
        private val history = ConversationHistoryReadService(
            responding<com.gromozeka.domain.repository.ConversationRepository> { currentConversation },
            responding<com.gromozeka.domain.repository.ConversationHistoryRepository> {
                assertEquals(currentConversation.currentThread.value, it.arguments[0])
                val index = messages.indexOfFirst { message -> message.id.value == it.arguments[1] }
                when (it.method.name.substringBefore('-')) {
                    "position" -> index.takeIf { position -> position >= 0 }
                    "message" -> {
                        historyMessageReads++
                        index.takeIf { position -> position >= 0 }?.let { position ->
                            com.gromozeka.domain.repository.PositionedConversationMessage(position, messages[position])
                        }
                    }
                    else -> error("Unexpected history operation: ${it.method.name}")
                }
            }, coordinator,
        )
        var toolEffects = 0
        val toolTasks = ConversationToolExecutionTaskService(
            conversationService = conversations, conversationHistory = history, conversationMessageAppender = appender,
            workspaceService = mock(), runtimeCoordinator = coordinator, runtimeStateSyncService = mock(),
            parallelToolExecutor = responding {
                check(it.method.name.substringBefore('-') == "executeParallel")
                toolEffects++
                @Suppress("UNCHECKED_CAST")
                val calls = it.arguments[0] as List<ContentItem.ToolCall>
                ToolExecutionResult(calls.map { call -> ContentItem.ToolResult(call.id, call.call.name,
                    listOf(ContentItem.ToolResult.Data.Text("synthetic result"))) }, false)
            },
            workerAccessService = mock(), workerTargetResolver = mock(), workerToolExecutionClient = mock(),
            artifactService = artifacts, toolSecretResolutionService = responding { emptyMap<String, String>() },
        )
        var draft: AgentResponseDraft? = null
        val reviewRepository = responding<com.gromozeka.domain.repository.AgentCollaborationRepository> {
            if (it.method.name.startsWith("findDraft")) draft else Mockito.RETURNS_DEFAULTS.answer(it)
        }
        private val collaboration = responding<AgentCollaborationService> {
            when (it.method.name.substringBefore('-')) {
                "getRepository" -> reviewRepository
                "validateMessage" -> true
                else -> Mockito.RETURNS_DEFAULTS.answer(it)
            }
        }
        val engine = ConversationEngineService(
            aiRuntimeProvider = responding { runtime }, aiToolProvider = responding { emptyList<Any>() },
            agentDomainService = responding { invocation ->
                if (invocation.arguments.firstOrNull() in listOf(otherAgentId, otherAgentId.value)) agent.copy(id = otherAgentId) else agent
            }, agentPromptAssemblyService = responding { emptyList<String>() },
            toolApprovalService = mock(), toolExecutionTaskService = mock(), conversationService = conversations, conversationHistory = history, contextHistory = contextHistory,
            conversationMessageAppender = appender, historyMutationExecutor = mock(), compactions = compactions, memoryApplicationService = mock(), memoryToolApplicationService = responding {
                if (it.method.name.substringBefore('-') == "memoryRunStatus") "{}" else Mockito.RETURNS_DEFAULTS.answer(it)
            },
            backgroundActivityCompletionApplicationService = backgroundActivities, memoryMessageRoutingApplicationService = mock(),
            toolCallSequenceFixerService = responding { ToolCallSequenceFixerService.FixResult(false, 0, 0, 0) }, settingsProvider = responding { UserProfile() },
            aiConfigurationProvider = responding { resolved }, runtimeCoordinator = coordinator, runtimeStateSyncService = mock(),
            distributedToolCatalog = responding { catalog }, agentSkillRuntimeCatalogService = responding { AgentSkillRuntimeCatalog(catalog, null) },
            aiToolRuntimeCatalogService = responding { AiToolRuntimeSelection(emptyList(), emptyList()) },
            aiToolCapabilityCatalogService = mock(), toolRoutingService = mock(),
            artifactService = artifacts, activeGenerationPublisher = mock(), messageTemporalContextService = MessageTemporalContextService(),
            stickyMessageInstructionService = StickyMessageInstructionService(), pendingSecretRevealService = pendingSecrets,
            suggestedRepliesGenerationService = mock(),
            conversationPins = ConversationPinnedPrompt(pins),
            requestEnrichers = if (withEnricher) listOf(object : ConversationRequestEnricher {
                override suspend fun enrich(conversationId: Conversation.Id, rootUserMessageId: Conversation.Message.Id,
                    turnId: ConversationRuntimeTurnId, model: AiModelSpec, request: AiRuntimeRequest): AiRuntimeRequest =
                    request.copy(systemPrompts = request.systemPrompts + "ENRICHER_LAST")
            }) else emptyList(),
            collaboration = collaboration.takeIf { enableReview },
            responseReview = if (enableReview) AgentResponseReviewService(collaboration, reviewRepository, coordinator, ConversationPinnedPrompt(pins), mock()) else null,
        )
        suspend fun begin(): ConversationRuntimeTask {
            val root = input("root")
            coordinator.submit(root)
            claim(root)
            val task = root.copy(id = ConversationRuntimeTask.Id("root:llm:1"), parentTaskId = root.id,
                idempotencyKey = "root:llm:1", payload = ConversationRuntimeTask.Payload.LlmCall(Conversation.Message.Id("root"), agentId, 1))
            advance(root, task)
            return task
        }
        suspend fun advance(parent: ConversationRuntimeTask, next: ConversationRuntimeTask) {
            assertTrue(coordinator.completeActiveTask(conversation.id, parent.id, executor, ConversationRuntimeTaskOutcome.Continue(next)))
            claim(next)
        }
        suspend fun drain() {
            repeat(20) {
                val ready = coordinator.listReadyWorkItems(1).singleOrNull() ?: return
                val task = coordinator.listPending(conversation.id).first { it.id == ready.taskId }
                claim(task)
                val outcome = engine.runRuntimeTask(task, executor) { emitted += it }
                assertTrue(coordinator.completeActiveTask(conversation.id, task.id, executor, outcome))
            }
            error("Runtime did not drain within the bounded number of steps")
        }
        suspend fun claim(task: ConversationRuntimeTask) {
            assertNotNull(coordinator.claimDeliveredTask(conversation.id, task.id, executor, requirements.capabilities, emptySet()))
            assertTrue(coordinator.markActiveTaskStarted(conversation.id, task.id, executor, now))
        }
    }
    private inline fun <reified T : Any> mock(): T = Mockito.mock(T::class.java)
    private inline fun <reified T : Any> responding(crossinline answer: (InvocationOnMock) -> Any?): T =
        Mockito.mock(T::class.java) { answer(it) }
}
