package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.model.memory.MemoryRun
import kotlinx.serialization.json.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ServerToolMetadata
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
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

    @Test fun `handled provider failure records a failed completion instead of success`() = runBlocking {
        val f = Fixture(); val task = f.begin()
        f.onCall = { throw IllegalStateException("PRIVATE_PROVIDER_ERROR_BODY") }
        val outcome = assertIs<ConversationRuntimeTaskOutcome.Failed>(f.engine.runRuntimeTask(task, executor) {})
        assertEquals(ConversationRuntimeCompletion.Reason.MODEL_ERROR, outcome.reason)
        assertEquals("IllegalStateException", outcome.errorType)
        assertTrue(f.coordinator.completeActiveTask(conversation.id, task.id, executor, outcome))
        val completion = assertNotNull(f.coordinator.schedulingSnapshot(conversation.id).lastCompletion)
        assertEquals(ConversationRuntimeCompletion.Status.FAILED, completion.status)
        assertEquals(outcome.reason, completion.reason)
        assertFalse(completion.toString().contains("PRIVATE_PROVIDER_ERROR_BODY"))
    }

    @Test fun `model failure and refusal have explicit distinct completion reasons`() = runBlocking {
        for ((step, reason) in listOf(AiStepOutcome.FAILED to ConversationRuntimeCompletion.Reason.MODEL_RESPONSE_INCOMPLETE,
            AiStepOutcome.REFUSED to ConversationRuntimeCompletion.Reason.MODEL_REFUSED)) {
            val f = Fixture(); val task = f.begin()
            f.onCall = { answer(step) }
            val outcome = assertIs<ConversationRuntimeTaskOutcome.Failed>(f.engine.runRuntimeTask(task, executor) {})
            assertEquals(reason, outcome.reason)
            assertTrue(f.coordinator.completeActiveTask(conversation.id, task.id, executor, outcome))
            assertEquals(ConversationRuntimeCompletion.Status.FAILED, f.coordinator.snapshot(conversation.id).lastCompletion?.status)
        }
    }

    @Test fun `iteration limit is a failed turn and performs no extra model call`() = runBlocking {
        val f = Fixture(); val task = f.begin()
        val exhausted = task.copy(payload = (task.payload as ConversationRuntimeTask.Payload.LlmCall).copy(iteration = Int.MAX_VALUE))
        val outcome = assertIs<ConversationRuntimeTaskOutcome.Failed>(f.engine.runRuntimeTask(exhausted, executor) {})
        assertEquals(ConversationRuntimeCompletion.Reason.ITERATION_LIMIT, outcome.reason)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun `background preparation begins after each committed successful model response`() = runBlocking {
        val f = Fixture(); val task = f.begin()
        var emitted = false
        f.onPrepare = { assertTrue(emitted, "Preparation must follow answer delivery") }
        assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(f.engine.runRuntimeTask(task, executor) { message ->
            if (message.content.any { it is ContentItem.AssistantMessage }) emitted = true
        })
        val continued = Fixture(); val active = continued.begin(); continued.onCall = { answer(AiStepOutcome.CONTINUE) }
        assertIs<ConversationRuntimeTaskOutcome.Continue>(continued.engine.runRuntimeTask(active, executor) {})
        assertEquals(1, continued.preparations.size)
        assertEquals(1, continued.requests.size)
        assertEquals(1, f.preparations.size)
        assertTrue(f.preparations.single().messages.any { message -> message.content.any { it is ContentItem.AssistantMessage } })
        for (outcome in listOf(AiStepOutcome.FAILED, AiStepOutcome.REFUSED)) {
            val other = Fixture(); val active = other.begin(); other.onCall = { answer(outcome) }
            other.engine.runRuntimeTask(active, executor) {}
            assertTrue(other.preparations.isEmpty(), outcome.name)
        }
    }

    @Test fun `history mutation batch rechecks proactive compaction once even when its last mutation is stale`() = runBlocking {
        val reasoning = AiReasoningConfig(effort = AiReasoningEffort.MAX)
        val tool = object : AiToolCallback {
            override val definition = AiToolDefinition("inspect_state", "Inspect current state", """{"type":"object"}""")
            override val metadata = ServerToolMetadata
            override fun call(toolInput: String, context: ToolExecutionContext?) = error("Not executed")
        }
        val releaseTool = object : AiToolCallback {
            override val definition = AiToolDefinition("grz_slot_release", "Release the held slot", """{"type":"object"}""")
            override val metadata = ServerToolMetadata
            override fun call(toolInput: String, context: ToolExecutionContext?) = error("Not executed")
        }
        val f = Fixture(proactive = true, withEnricher = true, reasoningOverride = reasoning,
            maxOutputTokens = 256, selectedTools = listOf(tool), compactionEnrichedTools = listOf(releaseTool))
        f.pins.values = listOf(testPin(conversation.id, "CURRENT_PIN"))
        val source = f.begin()
        val preparation = ProactiveCompaction(
            id = "stale-preparation",
            conversationId = conversation.id,
            sourceTaskId = source.id,
            sourceTurnId = source.turnId,
            executor = executor,
            agentId = agentId,
            actorUserId = user,
            threadId = conversation.currentThread,
            capturedMessageCount = 1,
            historyFingerprint = "a".repeat(64),
            scopeFingerprint = "b".repeat(64),
            inputTokens = 600,
            sourceCount = 1,
            retainedCount = 0,
            createdAt = now,
        )
        assertTrue(f.coordinator.beginProactiveCompaction(preparation))

        fun mutation(id: String) = ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id(id),
            conversationId = conversation.id,
            actorUserId = user,
            payload = ConversationRuntimeTask.Payload.HistoryMutation(
                mutation = ConversationHistoryMutation.Delete(listOf(Conversation.Message.Id("root"))),
                expectedThreadId = conversation.currentThread,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = id,
            requirements = requirements,
            createdAt = now,
        )

        val first = mutation("history-1")
        val second = mutation("history-2")
        assertTrue(f.coordinator.submit(first))
        assertTrue(f.coordinator.submit(second))
        assertNotNull(f.coordinator.cancelProactiveCompactionForHistoryMutation(conversation.id))
        assertTrue(f.coordinator.completeActiveTask(conversation.id, source.id, executor, ConversationRuntimeTaskOutcome.CompleteTurn))

        f.claim(first)
        val firstOutcome = f.engine.runRuntimeTask(first, executor) {}
        assertIs<ConversationRuntimeTaskOutcome.HistoryChanged>(firstOutcome)
        assertTrue(f.preparations.isEmpty(), "The second mutation keeps the batch open")
        f.currentConversation = f.currentConversation.copy(currentThread = Conversation.Thread.Id("after-history-1"))
        assertTrue(f.coordinator.completeActiveTask(conversation.id, first.id, executor, firstOutcome))

        f.claim(second)
        f.historyMutationFailure = IllegalStateException("stale mutation")
        assertFailsWith<IllegalStateException> {
            f.engine.runRuntimeTask(second, executor) {}
        }
        val request = f.preparations.single()
        assertEquals("after-history-1", request.options.toolContext["threadId"])
        assertEquals(reasoning, request.options.reasoning)
        assertEquals(256, request.options.maxOutputTokens)
        assertEquals(listOf("inspect_state", "grz_slot_release"), request.tools.map { it.definition.name })
        assertTrue("COMPACTION_SNAPSHOT_ENRICHER" in request.systemPrompts)
        assertFalse("SNAPSHOT_ENRICHER" in request.systemPrompts)
        assertFalse("ENRICHER_LAST" in request.systemPrompts)
        assertTrue(request.systemPrompts.none { "CURRENT_PIN" in it },
            "prepareAfterResponse owns the fresh pin snapshot")
    }

    @Test fun `foreground pins are not baked into the input used to prepare the next compact`() = runBlocking {
        val f = Fixture(proactive = true)
        val task = f.begin()
        f.pins.values = listOf(testPin(conversation.id, "ORIGINAL_PIN"))
        f.onCall = { request ->
            assertTrue(request.systemPrompts.any { "ORIGINAL_PIN" in it })
            f.pins.values = listOf(testPin(conversation.id, "REPLACEMENT_PIN"))
            answer()
        }
        f.engine.runRuntimeTask(task, executor) {}
        val base = f.preparations.single().systemPrompts
        assertFalse(base.any { "ORIGINAL_PIN" in it || "REPLACEMENT_PIN" in it })
    }

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

    @Test fun `reviewed responses prepare compaction only after committing the accepted draft`() = runBlocking {
        for (text in listOf("Visible final", null)) {
            val f = Fixture(enableReview = true, proactive = true); val model = f.begin()
            val candidate = Conversation.Message(Conversation.Message.Id("candidate"), conversation.id, role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText("Original answer"))), createdAt = now)
            f.draft = AgentResponseDraft("draft", AgentContextRef(conversation.id, conversation.currentThread, agentId), user,
                Conversation.Message.Id("root"), model.turnId.value, 1, listOf(candidate), emptyList(), emptyList(), emptyList(), "", listOf("root"), now,
                decision = AgentResponseDecision(text, AgentResponseDecision.Next.COMPLETE, emptyList(), emptyList()))
            f.acceptReview = true
            val task = model.copy(id = ConversationRuntimeTask.Id("review"), parentTaskId = model.id, idempotencyKey = "review",
                payload = ConversationRuntimeTask.Payload.ResponseReview("draft", agentId))
            f.advance(model, task)
            f.onPrepare = {
                assertTrue(f.emitted.any { it.providerMetadata["collaborationReviewId"] == JsonPrimitive("draft") })
                if (text != null) assertTrue(f.emitted.any { m -> m.content.any { it is ContentItem.AssistantMessage && it.structured.fullText == text } })
            }
            val result = f.engine.runRuntimeTask(task, executor) { f.emitted += it }
            if (text == null) assertIs<ConversationRuntimeTaskOutcome.CompleteWithoutNotification>(result)
            else assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(result)
            assertEquals(1, f.preparations.size)
        }
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

    @Test fun `custom inline suggestions instruction reaches the main model without a second call`() = runBlocking {
        val f = Fixture()
        f.profile = UserProfile(suggestedRepliesSettings = UserProfile.SuggestedRepliesSettings(
            inlinePrompt = "CUSTOM_INLINE_INSTRUCTION", separatePrompt = "SEPARATE_ONLY_INSTRUCTION",
        ))
        val task = f.begin()
        assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(f.engine.runRuntimeTask(task, executor) {})
        val prompts = f.requests.single().systemPrompts.joinToString("\n")
        assertContains(prompts, "suggestedReplies: CUSTOM_INLINE_INSTRUCTION")
        assertFalse("SEPARATE_ONLY_INSTRUCTION" in prompts)
        assertTrue(f.suggestionRequests.isEmpty())
    }

    @Test fun `full suggestions context loads actual thread history rather than the compacted model prefix`() = runBlocking {
        for (mode in UserProfile.SuggestedRepliesSettings.ContextMode.entries) {
            val f = Fixture()
            f.profile = UserProfile(suggestedRepliesSettings = UserProfile.SuggestedRepliesSettings(
                mode = UserProfile.SuggestedRepliesSettings.Mode.SEPARATE_RUNTIME,
                separatePrompt = "CUSTOM_SEPARATE_INSTRUCTION", contextMode = mode,
            ))
            f.allowFullHistoryLoad = mode == UserProfile.SuggestedRepliesSettings.ContextMode.FULL_THREAD
            val root = f.messages.single()
            val old = root.copy(id = Conversation.Message.Id("archived"), content = listOf(ContentItem.UserMessage("ARCHIVE_VISIBLE_SENTINEL")))
            val checkpoint = root.copy(id = Conversation.Message.Id("checkpoint"), role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ContextCompactionResult(
                    ContentItem.ContextCompactionResult.Payload.ReadableSummary("summary"), ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                    coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                )))
            val replayAnchor = old.copy(id = Conversation.Message.Id("replay-anchor"), content = listOf(ContentItem.UserMessage("REPLAY_ANCHOR")))
            f.messages.clear(); f.messages += listOf(old, replayAnchor, checkpoint, root)
            val task = f.begin()
            assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(f.engine.runRuntimeTask(task, executor) {})
            assertFalse(f.requests.single().messages.any { it.id == old.id })
            val request = f.suggestionRequests.single()
            val text = (request.messages.single().content.single() as ContentItem.UserMessage).text
            assertEquals(mode == UserProfile.SuggestedRepliesSettings.ContextMode.FULL_THREAD, "ARCHIVE_VISIBLE_SENTINEL" in text)
            assertContains(text, "CUSTOM_SEPARATE_INSTRUCTION")
            assertContains(text, "ASSISTANT: Answer")
            assertFalse("CUSTOM_SEPARATE_INSTRUCTION" in f.requests.single().systemPrompts.joinToString("\n"))
            assertEquals(listOf("Next"), f.messages.last().content.filterIsInstance<ContentItem.AssistantMessage>().single().structured.suggestedReplies)
        }
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

    @Test fun `incident recovers only the admitted async receipt and never repeats ordinary or missing async siblings`() = runBlocking {
        val operations = TestAsyncToolOperations()
        val recovery = AsyncToolAdmissionService(operations, mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock())
        val f = Fixture(asyncRecovery = recovery)
        val running = f.begin()
        fun call(id: String, async: Boolean) = ContentItem.ToolCall(ContentItem.ToolCall.Id(id), ContentItem.ToolCall.Data(id,
            buildJsonObject { if (async) put("_grz", buildJsonObject { put("async", true) }) }))
        val admitted = call("admitted", true)
        val missing = call("not-admitted", true)
        val ordinary = call("ordinary", false)
        val payload = ConversationRuntimeTask.Payload.ToolExecution(Conversation.Message.Id("root"), agentId, 1,
            listOf(admitted, missing, ordinary), false, listOf(admitted, missing, ordinary).associate { it.id.value to ConversationRuntimeTaskTarget.Server })
        val source = running.copy(id = ConversationRuntimeTask.Id("mixed-source"), parentTaskId = running.id, payload = payload)
        f.advance(running, source)
        val origin = AsyncToolOrigin(user, project.id, conversation.id, conversation.currentThread, agentId, source.id, admitted.id,
            Conversation.Message.Id("mixed-source:result"), false, null)
        operations.admit(AsyncToolOperation.Id("durable-accepted"), AsyncToolInvocation(origin, admitted.call.name, "1".repeat(64), admitted,
            ConversationRuntimeTaskTarget.Server, mapOf(com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID to user.value,
                com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID to project.id.value, com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID to conversation.id.value,
                com.gromozeka.domain.tool.TOOL_CONTEXT_THREAD_ID to conversation.currentThread.value,
                com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID to agentId.value, "toolCallId" to admitted.id.value)), now)
        assertNotNull(f.coordinator.markActiveTaskInDoubt(conversation.id, source.id, executor, "Lost source result", "SyntheticFailure"))
        val incident = f.coordinator.listPending(conversation.id).single { it.payload is ConversationRuntimeTask.Payload.ExecutionIncident }
        f.claim(incident)
        repeat(2) { assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(incident, executor) {}) }
        val message = f.messages.single { it.id == origin.acknowledgementMessageId }
        val results = message.content.filterIsInstance<ContentItem.ToolResult>()
        assertEquals(listOf(admitted.id, missing.id, ordinary.id), results.map { it.toolUseId })
        assertFalse(results.first().isError)
        val receipt = Json.parseToJsonElement(assertIs<ContentItem.ToolResult.Data.Text>(results.first().result.single()).content).jsonObject
        assertEquals(JsonPrimitive("durable-accepted"), receipt["operation_id"])
        assertTrue(results.drop(1).all { it.isError })
        assertEquals(1, operations.records.size)
        assertEquals(AsyncToolOperation.Phase.ACCEPTED, operations.records.values.single().phase)
        assertEquals(0, f.toolEffects)
        assertTrue(f.requests.isEmpty())
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

    @Test fun `idle completion batch including failure triggers one ordinary model invocation`() = runBlocking {
        val f = Fixture(realBackground = true)
        f.queueCommandCompletion("failed", CommandTask.Status.FAILED)
        f.queueCommandCompletion("successful")
        f.drain()
        val request = f.requests.single()
        assertEquals(user.value, request.options.toolContext[TOOL_CONTEXT_USER_ID])
        for (id in listOf("failed", "successful")) {
            assertEquals(2, request.messages.count { it.providerMetadata["commandTaskId"] == JsonPrimitive(id) })
        }
        assertTrue(request.messages.flatMap { it.content }.filterIsInstance<ContentItem.ToolResult>()
            .flatMap { it.result }.filterIsInstance<ContentItem.ToolResult.Data.Text>().any { it.content.contains("\"exit_code\":23") })
        assertTrue(f.coordinator.listPending(conversation.id).isEmpty())
    }

    @Test fun `pending work from another actor cannot suppress the completion trigger`() = runBlocking {
        val f = Fixture(realBackground = true)
        f.queueCommandCompletion("failed", CommandTask.Status.FAILED)
        assertTrue(f.coordinator.submit(input("bob-next", bob)))
        f.drain()
        assertEquals(listOf(user.value, bob.value), f.requests.map { it.options.toolContext[TOOL_CONTEXT_USER_ID] })
        assertEquals(2, f.requests.first().messages.count { it.providerMetadata["commandTaskId"] == JsonPrimitive("failed") })
    }

    @Test fun `ready command completion joins the next model request without another turn`() = runBlocking {
        val f = Fixture(realBackground = true)
        val running = f.begin()
        val command = f.queueCommandCompletion("ready")
        val delivery = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(running, executor) { f.emitted += it })
        assertTrue(f.requests.isEmpty())
        assertEquals(user, delivery.nextTask.actorUserId)
        assertEquals(running.turnId, delivery.nextTask.turnId)
        assertNull(f.coordinator.findCommandTask(conversation.id, command.id)?.completionNotificationDeliveredAt)
        assertTrue(f.coordinator.completeActiveTask(conversation.id, running.id, executor, delivery))
        assertNotNull(f.coordinator.findCommandTask(conversation.id, command.id)?.completionNotificationDeliveredAt)
        f.drain()
        assertEquals(1, f.requests.size)
        assertEquals(user.value, f.requests.single().options.toolContext[TOOL_CONTEXT_USER_ID])
        assertEquals(2, f.requests.single().messages.count { it.providerMetadata["commandTaskId"] == JsonPrimitive(command.id.value) })
        assertTrue(f.coordinator.listPending(conversation.id).isEmpty())
    }

    @Test fun `owner failure after packet append cannot lose the model continuation obligation`(): Unit = runBlocking {
        val f = Fixture(realBackground = true)
        val running = f.begin()
        val command = f.queueCommandCompletion("before-commit")
        val prepared = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(running, executor) { f.emitted += it })
        assertTrue(prepared.backgroundDeliveryReceipts.isNotEmpty())
        assertTrue(f.requests.isEmpty())
        assertNull(f.coordinator.findCommandTask(conversation.id, command.id)?.completionNotificationDeliveredAt)
        assertNotNull(f.coordinator.markActiveTaskInDoubt(conversation.id, running.id, executor, "Synthetic failure before commit", "SyntheticFailure"))
        f.drain()
        assertEquals(1, f.requests.size)
        assertEquals(2, f.messages.count { it.providerMetadata["commandTaskId"] == JsonPrimitive(command.id.value) })
        assertNotNull(f.coordinator.findCommandTask(conversation.id, command.id)?.completionNotificationDeliveredAt)
    }

    @Test fun `stop before packet acknowledgement preserves it until explicit resume`(): Unit = runBlocking {
        val f = Fixture(realBackground = true)
        val running = f.begin()
        val command = f.queueCommandCompletion("stopped-before-commit")
        val prepared = f.engine.runRuntimeTask(running, executor) { f.emitted += it }
        assertTrue(f.coordinator.requestStop(conversation.id))
        assertTrue(f.coordinator.completeActiveTask(conversation.id, running.id, executor, prepared))
        assertNull(f.coordinator.findCommandTask(conversation.id, command.id)?.completionNotificationDeliveredAt)
        assertTrue(f.coordinator.listReadyWorkItems(10).isEmpty())
        assertTrue(f.requests.isEmpty())
        assertTrue(f.coordinator.requestResume(conversation.id))
        f.drain()
        assertEquals(1, f.requests.size)
        assertEquals(2, f.messages.count { it.providerMetadata["commandTaskId"] == JsonPrimitive(command.id.value) })
        assertNotNull(f.coordinator.findCommandTask(conversation.id, command.id)?.completionNotificationDeliveredAt)
    }

    @Test fun `completion received during inference is consumed in the same turn at the next boundary`() = runBlocking {
        val f = Fixture(realBackground = true)
        val running = f.begin()
        f.onCall = {
            if (f.requests.size == 1) f.queueCommandCompletion("during-model")
            answer()
        }
        val delivery = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(running, executor) { f.emitted += it })
        assertEquals(1, f.requests.size)
        assertEquals(running.turnId, delivery.nextTask.turnId)
        assertEquals(user, delivery.nextTask.actorUserId)
        assertTrue(f.coordinator.completeActiveTask(conversation.id, running.id, executor, delivery))
        f.drain()
        assertEquals(2, f.requests.size)
        assertEquals(2, f.messages.count { it.providerMetadata["commandTaskId"] == JsonPrimitive("during-model") })
        assertTrue(f.coordinator.listPending(conversation.id).isEmpty())
    }

    @Test fun `duplicate delivery of an already delivered event cannot create another model request`() = runBlocking {
        val f = Fixture(realBackground = true)
        val running = f.begin()
        f.onCall = {
            val command = f.queueCommandCompletion("observed-during-model")
            f.coordinator.upsertCommandTask(command.copy(completionNotificationDeliveredAt = now))
            answer()
        }
        val outcome = f.engine.runRuntimeTask(running, executor) { f.emitted += it }
        assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(outcome)
        assertTrue(f.coordinator.completeActiveTask(conversation.id, running.id, executor, outcome))
        f.drain()
        assertEquals(1, f.requests.size)
        assertFalse(f.messages.any { it.syntheticKind == SyntheticMessageKind.COMMAND_TASK_COMPLETION })
    }

    @Test fun `terminal query and completion event reach the model together even for return-direct tools`() = runBlocking {
        for (returnDirect in listOf(false, true)) {
            val f = Fixture(realBackground = true)
            val running = f.begin()
            val command = f.queueCommandCompletion("queried")
            val toolId = ContentItem.ToolCall.Id("query")
            val call = Conversation.Message(Conversation.Message.Id("query-call"), conversation.id, role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ToolCall(toolId, ContentItem.ToolCall.Data("grz_get_command_task__v3", buildJsonObject { put("task_id", command.id.value) }))), createdAt = now)
            val result = call.copy(id = Conversation.Message.Id("query-result"), role = Conversation.Message.Role.USER,
                content = listOf(ContentItem.ToolResult(toolId, "grz_get_command_task__v3", listOf(ContentItem.ToolResult.Data.Text(
                    buildJsonObject { put("task_id", command.id.value); put("status", "COMPLETED") }.toString())), executionToolName = "grz_get_command_task")))
            f.messages += listOf(call, result)
            val task = running.copy(id = ConversationRuntimeTask.Id("tool-result"), parentTaskId = running.id, idempotencyKey = "tool-result",
                payload = ConversationRuntimeTask.Payload.ToolResultProcessing(Conversation.Message.Id("root"), result.id, agentId, 1, returnDirect))
            f.advance(running, task)
            val outcome = f.engine.runRuntimeTask(task, executor) { f.emitted += it }
            assertTrue(f.coordinator.completeActiveTask(conversation.id, task.id, executor, outcome))
            f.drain()
            assertEquals(1, f.requests.size)
            assertEquals(2, f.requests.single().messages.count { it.providerMetadata["commandTaskId"] == JsonPrimitive(command.id.value) })
            assertTrue(f.requests.single().messages.any { it.id == result.id })
            assertNotNull(f.coordinator.findCommandTask(conversation.id, command.id)?.completionNotificationDeliveredAt)
        }
    }

    @Test fun `working tool output and completion are combined before inference without splitting the tool pair`() = runBlocking {
        val f = Fixture(realBackground = true)
        val running = f.begin()
        val command = f.queueCommandCompletion("quick-background")
        val toolId = ContentItem.ToolCall.Id("launch-quick-background")
        val call = Conversation.Message(Conversation.Message.Id("launch-call"), conversation.id, role = Conversation.Message.Role.ASSISTANT,
            content = listOf(ContentItem.ToolCall(toolId, ContentItem.ToolCall.Data("grz_execute_command__v9", buildJsonObject { put("command", command.command) }))), createdAt = now)
        val result = call.copy(id = Conversation.Message.Id("launch-result"), role = Conversation.Message.Role.USER,
            content = listOf(ContentItem.ToolResult(toolId, "grz_execute_command__v9", listOf(ContentItem.ToolResult.Data.Text(
                buildJsonObject { put("task_id", command.id.value); put("status", "WORKING") }.toString())), executionToolName = "grz_execute_command")))
        f.messages += listOf(call, result)
        val task = running.copy(id = ConversationRuntimeTask.Id("tool-result"), parentTaskId = running.id, idempotencyKey = "tool-result",
            payload = ConversationRuntimeTask.Payload.ToolResultProcessing(Conversation.Message.Id("root"), result.id, agentId, 1, false))
        f.advance(running, task)
        val outcome = f.engine.runRuntimeTask(task, executor) { f.emitted += it }
        assertTrue(f.coordinator.completeActiveTask(conversation.id, task.id, executor, outcome))
        f.drain()
        val request = f.requests.single()
        assertEquals(2, request.messages.count { it.providerMetadata["commandTaskId"] == JsonPrimitive(command.id.value) })
        val originalResultIndex = request.messages.indexOfFirst { it.id == result.id }
        val completionIndex = request.messages.indexOfFirst { it.providerMetadata["commandTaskId"] == JsonPrimitive(command.id.value) }
        assertTrue(originalResultIndex >= 0 && completionIndex > originalResultIndex)
        assertEquals(user.value, request.options.toolContext[TOOL_CONTEXT_USER_ID])
        assertTrue(f.coordinator.listPending(conversation.id).isEmpty())
    }

    @Test fun `failed inserted delivery remains undelivered and excluded from automatic retry`() = runBlocking {
        val f = Fixture(realBackground = true)
        val running = f.begin()
        val command = f.queueCommandCompletion("failed-insertion")
        val untouched = f.queueCommandCompletion("untouched")
        f.onAppend = { message ->
            if (message.providerMetadata["commandTaskId"] == JsonPrimitive(command.id.value)) error("Synthetic insertion failure")
        }
        assertFailsWith<IllegalStateException> { f.engine.runRuntimeTask(running, executor) {} }
        val incident = assertNotNull(f.coordinator.markActiveTaskInDoubt(conversation.id, running.id, executor, "Delivery failed", "SyntheticFailure"))
        assertEquals(setOf(command.notificationSourceKey()), f.coordinator.schedulingSnapshot(conversation.id).failedBackgroundActivitySourceKeys())
        assertNull(f.coordinator.findCommandTask(conversation.id, command.id)?.completionNotificationDeliveredAt)
        val service = BackgroundActivityCompletionApplicationService(f.coordinator, f.backgroundOrigins, BackgroundActivityContinuationPolicy { _, _ -> true })
        assertTrue(service.prepareBatch(conversation.id, command.notificationSourceKey()).isEmpty)
        assertEquals(listOf(untouched.notificationSourceKey()), f.coordinator.listPending(conversation.id)
            .mapNotNull { (it.payload as? ConversationRuntimeTask.Payload.BackgroundActivityCompletion)?.sourceKey })
        assertTrue(f.messages.none { it.syntheticKind == SyntheticMessageKind.COMMAND_TASK_COMPLETION })
        assertTrue(f.requests.isEmpty())
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
        f.backgroundBatch = BackgroundActivityCompletionApplicationService.Batch(listOf(CommandTask(CommandTask.Id("legacy"), conversation.id,
            ConversationRuntimeWorkerId("worker"), WorkspaceMount.Id("mount"), agentId, command = "unused", workingDirectory = "/tmp", status = CommandTask.Status.COMPLETED,
            completedAt = now, completionNotificationRequestedAt = now,
            processId = 1, processStartedAt = now, outputFile = "/tmp/unused", outputBytes = 0, createdAt = now, updatedAt = now)), emptyList(), listOf(call, result))
        val task = input("background").copy(
            payload = ConversationRuntimeTask.Payload.BackgroundActivityCompletion("command-task:legacy"))
        assertTrue(f.coordinator.submit(task)); f.claim(task)
        repeat(2) { assertIs<ConversationRuntimeTaskOutcome.CompleteBackgroundDelivery>(f.engine.runRuntimeTask(task, executor) {}) }
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
                providerMetadata = buildJsonObject { putSyntheticMessageMetadata(SyntheticMessageKind.MEMORY) },
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

    @Test fun `ready preparation is applied after the final response without a continuation or synthetic user message`() = runBlocking {
        val f = Fixture(); val task = f.begin()
        val original = f.currentConversation.currentThread
        f.onCall = { request ->
            assertEquals(original.value, request.options.toolContext["threadId"])
            answer()
        }
        var applications = 0
        f.onApply = {
            assertTrue(f.messages.any { it.content.any { content -> content is ContentItem.AssistantMessage } })
            applications++
            f.currentConversation = f.currentConversation.copy(currentThread = Conversation.Thread.Id("applied"))
            f.onApply = { null }
            f.currentConversation
        }
        val outcome = f.engine.runRuntimeTask(task, executor) { f.emitted += it }
        assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(outcome)
        assertEquals(1, f.requests.size)
        assertEquals(1, applications)
        assertTrue(f.preparations.isEmpty())
        assertEquals(listOf("root"), f.messages.filter { it.content.any { c -> c is ContentItem.UserMessage } }.map { it.id.value })
        assertFalse(f.emitted.any { it.syntheticKind == SyntheticMessageKind.COMPACTION_CONTINUATION })
    }

    @Test fun `ready preparation preserves an ordinary CONTINUE and never applies during the model call`() = runBlocking {
        val f = Fixture(); val task = f.begin()
        val source = f.currentConversation.currentThread
        var returned = false
        f.onCall = {
            assertEquals(source, f.currentConversation.currentThread)
            returned = true
            answer(AiStepOutcome.CONTINUE)
        }
        f.onApply = {
            assertTrue(returned)
            assertTrue(f.messages.any { it.content.any { c -> c is ContentItem.AssistantMessage } })
            f.onApply = { null }
            f.currentConversation.copy(currentThread = Conversation.Thread.Id("applied")).also { f.currentConversation = it }
        }
        val outcome = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(task, executor) {})
        assertEquals(task.turnId, outcome.nextTask.turnId)
        assertEquals(Conversation.Message.Id("root"), (outcome.nextTask.payload as ConversationRuntimeTask.Payload.LlmCall).rootUserMessageId)
        assertEquals(1, f.requests.size)
        assertTrue(f.preparations.isEmpty())
    }

    @Test fun `idle READY application performs no inference and preserves the recorded agent completion`() = runBlocking {
        val f = Fixture(); val task = f.begin()
        val preparation = ProactiveCompaction("idle-preparation", conversation.id, task.id, task.turnId, executor, agentId, user,
            conversation.currentThread, 1, "a".repeat(64), "b".repeat(64), 1000, 1, 0, now)
        assertTrue(f.coordinator.beginProactiveCompaction(preparation))
        val checkpoint = f.messages.single().copy(id = Conversation.Message.Id(preparation.id), role = Conversation.Message.Role.ASSISTANT,
            content = listOf(ContentItem.ContextCompactionResult(ContentItem.ContextCompactionResult.Payload.ReadableSummary("handoff"),
                ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY, sourceMessageIds = listOf(f.messages.single().id),
                coverage = ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES)))
        val ready = preparation.copy(status = ProactiveCompaction.Status.READY, checkpoint = checkpoint)
        assertTrue(f.coordinator.updateProactiveCompaction(preparation, ready))
        assertTrue(f.coordinator.listReadyWorkItems(10).isEmpty(), "READY must not race the current model task")
        assertTrue(f.coordinator.completeActiveTask(conversation.id, task.id, executor, ConversationRuntimeTaskOutcome.CompleteTurn))
        val completion = f.coordinator.snapshot(conversation.id).lastCompletion
        assertNotNull(completion)
        f.onApply = {
            assertTrue(runBlocking { f.coordinator.updateProactiveCompaction(ready, ready.copy(status = ProactiveCompaction.Status.APPLIED, checkpoint = null)) })
            f.currentConversation
        }
        f.drain()
        assertTrue(f.coordinator.finishIfIdle(conversation.id))
        assertEquals(completion, f.coordinator.snapshot(conversation.id).lastCompletion)
        assertTrue(f.requests.isEmpty()); assertTrue(f.preparations.isEmpty()); assertTrue(f.emitted.isEmpty())
        assertEquals(ProactiveCompaction.Status.APPLIED, f.coordinator.proactiveCompaction(conversation.id)?.status)
    }

    @Test fun `ready compaction applies before tool routing while keeping the pending call and root`() = runBlocking {
        val f = Fixture(); val task = f.begin()
        val call = ContentItem.ToolCall(ContentItem.ToolCall.Id("pending-call"), ContentItem.ToolCall.Data("example", JsonObject(emptyMap())))
        f.onCall = { AiRuntimeResponse(listOf(AiAssistantMessage(listOf(call))), outcome = AiStepOutcome.TOOL_CALLS) }
        var applied = false
        f.onApply = {
            assertTrue(f.messages.any { it.content.contains(call) })
            applied = true; f.onApply = { null }
            f.currentConversation.copy(currentThread = Conversation.Thread.Id("after-precompact")).also { f.currentConversation = it }
        }
        f.onRoute = { assertTrue(applied); assertEquals(Conversation.Thread.Id("after-precompact"), it.currentThread) }
        val outcome = assertIs<ConversationRuntimeTaskOutcome.Continue>(f.engine.runRuntimeTask(task, executor) {})
        val next = assertIs<ConversationRuntimeTask.Payload.ToolExecution>(outcome.nextTask.payload)
        assertEquals(listOf(call), next.toolCalls)
        assertEquals(Conversation.Message.Id("root"), next.rootUserMessageId)
        assertEquals(task.turnId, outcome.nextTask.turnId)
        assertEquals(1, f.requests.size)
        assertTrue(f.preparations.isEmpty())
    }

    @Test fun `review applies ready compaction only after accepted messages and routing receipt are committed`() = runBlocking {
        val f = Fixture(enableReview = true, proactive = true); val model = f.begin()
        val candidate = Conversation.Message(Conversation.Message.Id("candidate"), conversation.id, role = Conversation.Message.Role.ASSISTANT,
            content = listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText("Accepted answer"))), createdAt = now)
        f.draft = AgentResponseDraft("draft", AgentContextRef(conversation.id, conversation.currentThread, agentId), user,
            Conversation.Message.Id("root"), model.turnId.value, 1, listOf(candidate), emptyList(), emptyList(), emptyList(), "", listOf("root"), now,
            decision = AgentResponseDecision("Accepted answer", AgentResponseDecision.Next.COMPLETE, emptyList(), emptyList()))
        f.acceptReview = true
        val task = model.copy(id = ConversationRuntimeTask.Id("review"), parentTaskId = model.id, idempotencyKey = "review",
            payload = ConversationRuntimeTask.Payload.ResponseReview("draft", agentId))
        f.advance(model, task)
        f.onApply = {
            assertEquals(conversation.currentThread, f.currentConversation.currentThread)
            assertTrue(f.messages.any { it.id == candidate.id })
            assertTrue(f.messages.any { it.id.value == "draft:receipt" })
            f.onApply = { null }
            f.currentConversation.copy(currentThread = Conversation.Thread.Id("reviewed-and-compacted")).also { f.currentConversation = it }
        }
        assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(f.engine.runRuntimeTask(task, executor) { f.emitted += it })
        assertTrue(f.requests.isEmpty()); assertTrue(f.preparations.isEmpty())
        assertEquals(Conversation.Thread.Id("reviewed-and-compacted"), f.currentConversation.currentThread)
    }

    @Test fun `running command observations reach ordinary requests without changing history or acknowledgement`() = runBlocking {
        val f = Fixture(withCommandContext = true)
        val running = f.begin()
        val root = f.messages.single()
        val command = CommandTask(CommandTask.Id("background"), conversation.id, ConversationRuntimeWorkerId("worker"), WorkspaceMount.Id("mount"),
            agentDefinitionId = agentId, command = "PRIVATE_COMMAND", workingDirectory = "/tmp", status = CommandTask.Status.WORKING,
            processId = 1, processStartedAt = now, outputFile = "/tmp/background", outputBytes = 0,
            createdAt = now, updatedAt = now, completionNotificationRequestedAt = now, description = "Analyze data")
        f.coordinator.upsertCommandTask(command)
        val outcome = f.engine.runRuntimeTask(running, executor) { f.emitted += it }
        assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(outcome)
        assertEquals(JsonArray(listOf(buildJsonObject { put("task_id", "background"); put("description", "Analyze data") })), recordedCommands(f.requests.single()))
        assertEquals(1, f.requests.single().systemPrompts.count { it == RecordedRunningCommandsRequestEnricher.INSTRUCTION })
        assertFalse(f.requests.single().toString().contains("PRIVATE_COMMAND"))
        assertEquals(root, f.messages.single { it.id == root.id })
        assertTrue(f.coordinator.completeActiveTask(conversation.id, running.id, executor, outcome))
        f.coordinator.upsertCommandTask(command.copy(status = CommandTask.Status.COMPLETED, completedAt = now, updatedAt = now))
        assertTrue(f.coordinator.submit(input("next")))
        f.drain()
        assertEquals(2, f.requests.size)
        assertTrue(recordedCommands(f.requests.last()).isEmpty())
        assertNull(f.coordinator.findCommandTask(conversation.id, command.id)?.completionNotificationDeliveredAt)
        assertEquals(0, f.toolEffects)
        assertTrue(f.coordinator.listPending(conversation.id).isEmpty())
    }

    @Test fun `command observations preserve automatic batched completion and its one model continuation`() = runBlocking {
        val f = Fixture(realBackground = true, withCommandContext = true)
        f.queueCommandCompletion("successful")
        f.queueCommandCompletion("failed", CommandTask.Status.FAILED)
        f.drain()
        val request = f.requests.single()
        assertTrue(recordedCommands(request).isEmpty())
        for (id in listOf("successful", "failed")) {
            assertEquals(2, request.messages.count { it.providerMetadata["commandTaskId"] == JsonPrimitive(id) })
            assertNotNull(f.coordinator.findCommandTask(conversation.id, CommandTask.Id(id))?.completionNotificationDeliveredAt)
        }
        assertTrue(f.coordinator.listPending(conversation.id).isEmpty())
    }

    private fun recordedCommands(request: AiRuntimeRequest): JsonArray = (request.messages.flatMap { it.instructions }
        .filterIsInstance<Conversation.Message.Instruction.RuntimeObservation>().map { it.text } + request.systemPrompts)
        .last { it.startsWith("<runtime-command-context>") }
        .substringAfter("<runtime-command-context>").substringBefore("</runtime-command-context>")
        .let { Json.parseToJsonElement(it).jsonObject.getValue("recorded_running_commands").jsonArray }

    private inner class Fixture(enableReview: Boolean = false, withEnricher: Boolean = false, proactive: Boolean = false,
        realBackground: Boolean = false, withCommandContext: Boolean = false, asyncRecovery: AsyncToolAdmissionService? = null,
        reasoningOverride: AiReasoningConfig? = null, maxOutputTokens: Int? = null,
        selectedTools: List<AiToolCallback> = emptyList(), compactionEnrichedTools: List<AiToolCallback> = emptyList()) {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        var currentConversation = conversation
        val pendingSecrets = PendingSecretRevealService()
        var profile = UserProfile()
        val suggestionRequests = mutableListOf<AiRuntimeRequest>()
        var onCompact: (AiRuntimeRequest) -> Conversation? = { null }
        var onApply: () -> Conversation? = { null }
        val compactionChecks = mutableListOf<Pair<AiRuntimeRequest, Boolean>>()
        val preparations = mutableListOf<AiRuntimeRequest>()
        var onPrepare: () -> Unit = {}
        private val compactions = responding<ConversationCompactionService> {
            if (it.method.name.startsWith("compactIfNeeded")) {
                val request = it.arguments[4] as AiRuntimeRequest
                compactionChecks += request to (it.arguments[5] as Boolean)
                onCompact(request)
            } else if (it.method.name.startsWith("applyPreparedIfReady")) {
                onApply()
            } else if (it.method.name.startsWith("prepareAfterResponse")) {
                onPrepare(); preparations += it.arguments[4] as AiRuntimeRequest; true
            } else Mockito.RETURNS_DEFAULTS.answer(it)
        }
        val pins = ReadOnlyTestPins()
        val messages = mutableListOf(requireNotNull(input("root").userMessageOrNull()))
        val emitted = mutableListOf<Conversation.Message>()
        val requests = mutableListOf<AiRuntimeRequest>()
        var onCall: suspend (AiRuntimeRequest) -> AiRuntimeResponse = { answer() }
        var onAppend: suspend (Conversation.Message) -> Unit = {}
        var historyMutationFailure: Throwable? = null
        var allowFullHistoryLoad = true
        var backgroundBatch: BackgroundActivityCompletionApplicationService.Batch? = null
        val backgroundOrigins = TestBackgroundActivityOrigins()
        private val backgroundActivities = if (realBackground) BackgroundActivityCompletionApplicationService(
            coordinator, backgroundOrigins, BackgroundActivityContinuationPolicy { actor, _ -> actor == user },
        ) else responding<BackgroundActivityCompletionApplicationService> {
            when (it.method.name.substringBefore('-')) {
                "prepareBatch" -> checkNotNull(backgroundBatch)
                "canContinue" -> false
                else -> Mockito.RETURNS_DEFAULTS.answer(it)
            }
        }
        private val runtime = object : AiRuntime {
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse { requests += request; return onCall(request) }
            override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = error("No streaming")
        }
        private val selection = AiRuntimeSelection(AiModelConfiguration.Id("config"))
        private val agent = AgentDefinition(agentId, name = "Test", prompts = emptyList(), runtimeSelection = selection,
            runtimeOverrides = AiRuntimeOverrides(maxOutputTokens, reasoningOverride),
            type = AgentDefinition.Type.Global, createdAt = now, updatedAt = now)
        private val connection = AiConnection.OpenAiSubscription(AiConnection.Id("test"), "Test", enabled = true)
        private val resolved = ResolvedAiRuntime(connection,
            AiModelConfiguration(selection.modelConfigurationId, connection.id, "test-model", "Test",
                compaction = if (proactive) AiModelConfiguration.Compaction(900, 500) else AiModelConfiguration.Compaction()),
            AiModelSpec("test-model", AiProvider.OPENAI, capabilities = setOf(AiModelCapability.TEXT_GENERATION),
                limits = AiModelSpec.Limits(textGeneration = AiModelSpec.Limits.TextGeneration(contextWindowTokens = 1000))))
        var onRoute: (Conversation) -> Unit = {}
        private val routing = responding<ConversationRuntimeToolRoutingService> {
            if (it.method.name.startsWith("route")) {
                onRoute(it.arguments[0] as Conversation)
                @Suppress("UNCHECKED_CAST")
                val calls = it.arguments[2] as List<ContentItem.ToolCall>
                ConversationRuntimeToolRoutingResult.Accepted(calls.associate { call -> call.id.value to ConversationRuntimeTaskTarget.Server }, false)
            } else Mockito.RETURNS_DEFAULTS.answer(it)
        }
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
            override suspend fun appendRuntimeMessages(conversationId: Conversation.Id, batch: List<Conversation.Message>): List<Conversation.Message> {
                val appended = batch.filter { candidate -> messages.none { it.id == candidate.id } }
                appended.forEach { onAppend(it) }
                messages.addAll(appended)
                return appended
            }
            override suspend fun appendRuntimeMessage(conversationId: Conversation.Id, message: Conversation.Message): Conversation {
                messages += message; onAppend(message); return currentConversation
            }
        }
        private val artifacts = responding<ConversationArtifactApplicationService> {
            when (it.method.name.substringBefore('-')) {
                "materialize", "materializeSnapshot" -> it.arguments[1]
                "persistAndCommitMessageOutputs" -> it.arguments[2]
                "persistAndCommitToolResults" -> {
                    @Suppress("UNCHECKED_CAST")
                    val results = it.arguments[2] as List<ContentItem.ToolResult>
                    results.map { result -> result.copy(result = result.result.map { data ->
                        if (data is ContentItem.ToolResult.Data.Base64Data && data.mediaType.value == "application/octet-stream") {
                            // Represent the stdout text preview; this fixture does not persist binary artifacts.
                            ContentItem.ToolResult.Data.Text(java.util.Base64.getDecoder().decode(data.data).decodeToString())
                        } else data
                    }) }
                }
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
        var acceptReview = false
        val reviewRepository = responding<com.gromozeka.domain.repository.AgentCollaborationRepository> {
            when {
                it.method.name.startsWith("findDraft") -> draft
                it.method.name.startsWith("applyDecision") -> acceptReview
                else -> Mockito.RETURNS_DEFAULTS.answer(it)
            }
        }
        private val collaboration = responding<AgentCollaborationService> {
            when (it.method.name.substringBefore('-')) {
                "getRepository" -> reviewRepository
                "validateMessage" -> true
                else -> Mockito.RETURNS_DEFAULTS.answer(it)
            }
        }
        private val historyMutationExecutor = responding<ConversationRuntimeHistoryMutationExecutor> {
            when (it.method.name.substringBefore('-')) {
                "execute" -> {
                    historyMutationFailure?.let { failure -> throw failure }
                    currentConversation
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(it)
            }
        }
        val engine = ConversationEngineService(
            aiRuntimeProvider = responding { runtime }, aiToolProvider = responding { emptyList<Any>() },
            agentDomainService = responding { invocation ->
                if (invocation.arguments.firstOrNull() in listOf(otherAgentId, otherAgentId.value)) agent.copy(id = otherAgentId) else agent
            }, agentPromptAssemblyService = responding { emptyList<String>() },
            toolApprovalService = mock(), toolExecutionTaskService = mock(), conversationService = conversations, conversationHistory = history, contextHistory = contextHistory,
            conversationMessageAppender = appender, historyMutationExecutor = historyMutationExecutor, compactions = compactions, memoryApplicationService = mock(), memoryToolApplicationService = responding {
                if (it.method.name.substringBefore('-') == "memoryRunStatus") "{}" else Mockito.RETURNS_DEFAULTS.answer(it)
            },
            backgroundActivityCompletionApplicationService = backgroundActivities, memoryMessageRoutingApplicationService = mock(),
            toolCallSequenceFixerService = responding { ToolCallSequenceFixerService.FixResult(false, 0, 0, 0) }, settingsProvider = responding { profile },
            aiConfigurationProvider = responding { resolved }, runtimeCoordinator = coordinator, runtimeStateSyncService = mock(),
            distributedToolCatalog = responding { catalog }, agentSkillRuntimeCatalogService = responding { AgentSkillRuntimeCatalog(catalog, null) },
            aiToolRuntimeCatalogService = responding { AiToolRuntimeSelection(selectedTools, emptyList()) },
            aiToolCapabilityCatalogService = mock(), toolRoutingService = routing,
            artifactService = artifacts, activeGenerationPublisher = mock(), messageTemporalContextService = MessageTemporalContextService(),
            stickyMessageInstructionService = StickyMessageInstructionService(), pendingSecretRevealService = pendingSecrets,
            suggestedRepliesGenerationService = SuggestedRepliesGenerationService(
                responding { object : AiRuntime {
                    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                        suggestionRequests += request
                        return AiRuntimeResponse(listOf(AiAssistantMessage(listOf(ContentItem.AssistantMessage(
                            Conversation.Message.StructuredText("", suggestedReplies = listOf("Next")),
                        )))))
                    }
                    override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = error("Unexpected suggestions stream")
                } }, ConversationPinnedPrompt(pins), responding { agent.runtimeSelection },
            ),
            conversationPins = ConversationPinnedPrompt(pins),
            requestEnrichers = buildList {
                if (withEnricher) add(object : ConversationRequestEnricher {
                    override suspend fun enrich(conversationId: Conversation.Id, rootUserMessageId: Conversation.Message.Id,
                        turnId: ConversationRuntimeTurnId, model: AiModelSpec, request: AiRuntimeRequest): AiRuntimeRequest =
                        request.copy(systemPrompts = request.systemPrompts + "ENRICHER_LAST")
                    override suspend fun enrichSnapshot(conversationId: Conversation.Id, rootUserMessageId: Conversation.Message.Id,
                        turnId: ConversationRuntimeTurnId, model: AiModelSpec, request: AiRuntimeRequest): AiRuntimeRequest =
                        request.copy(systemPrompts = request.systemPrompts + "SNAPSHOT_ENRICHER")
                    override suspend fun enrichCompactionSnapshot(conversationId: Conversation.Id,
                        rootUserMessageId: Conversation.Message.Id, turnId: ConversationRuntimeTurnId,
                        model: AiModelSpec, request: AiRuntimeRequest): AiRuntimeRequest = request.copy(
                        systemPrompts = request.systemPrompts + "COMPACTION_SNAPSHOT_ENRICHER",
                        tools = request.tools + compactionEnrichedTools,
                    )
                })
                if (withCommandContext) add(RecordedRunningCommandsRequestEnricher(coordinator))
            },
            asyncAdmission = asyncRecovery,
            collaboration = collaboration.takeIf { enableReview },
            responseReview = if (enableReview) AgentResponseReviewService(collaboration, reviewRepository, coordinator, ConversationPinnedPrompt(pins), mock()) else null,
        )
        suspend fun queueCommandCompletion(id: String, status: CommandTask.Status = CommandTask.Status.COMPLETED): CommandTask {
            val command = CommandTask(CommandTask.Id(id), conversation.id, ConversationRuntimeWorkerId("worker"), WorkspaceMount.Id("mount"),
                agentDefinitionId = agentId, command = "echo done", workingDirectory = "/tmp", status = status,
                processId = 1, processStartedAt = now, outputFile = "/tmp/$id", outputBytes = 4,
                exitCode = when (status) { CommandTask.Status.FAILED -> 23; CommandTask.Status.CANCELLED -> null; else -> 0 },
                createdAt = now, updatedAt = now, completedAt = now, completionNotificationRequestedAt = now,
                terminalOutputStartByte = 0, terminalOutputContent = BinaryContent.fromText("done"),
                launchReference = BackgroundActivityLaunchReference("request-$id", "launch-$id"))
            coordinator.upsertCommandTask(command)
            backgroundOrigins.bindWhileRequestPending(com.gromozeka.domain.repository.BackgroundActivityOrigin(
                com.gromozeka.domain.repository.BackgroundActivityOrigin.Kind.COMMAND, id, conversation.id, agentId, user,
                command.workerId, command.workspaceMountId, "request-$id", "launch-$id"), now)
            val notification = input("notification-$id").copy(payload = ConversationRuntimeTask.Payload.BackgroundActivityCompletion(command.notificationSourceKey()))
            assertTrue(coordinator.submitBackgroundActivity(notification, agentId))
            return command
        }

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
