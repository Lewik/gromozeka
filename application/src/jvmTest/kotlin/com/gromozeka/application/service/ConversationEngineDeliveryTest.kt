package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.*
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
        f.draft = AgentResponseDraft("draft", AgentEndpoint(conversation.id, conversation.currentThread, agentId), user,
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

    private inner class Fixture(enableReview: Boolean = false, withEnricher: Boolean = false) {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val pins = ReadOnlyTestPins()
        val messages = mutableListOf(requireNotNull(input("root").userMessageOrNull()))
        val emitted = mutableListOf<Conversation.Message>()
        val requests = mutableListOf<AiRuntimeRequest>()
        var onCall: suspend (AiRuntimeRequest) -> AiRuntimeResponse = { answer() }
        var onAppend: suspend (Conversation.Message) -> Unit = {}
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
                "findById" -> conversation; "getProject" -> project; "loadCurrentMessages" -> messages.toList()
                else -> Mockito.RETURNS_DEFAULTS.answer(it)
            }
        }
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
            toolApprovalService = mock(), toolExecutionTaskService = mock(), conversationService = conversations,
            conversationMessageAppender = object : ConversationRuntimeMessageAppender {
                override suspend fun appendRuntimeMessage(conversationId: Conversation.Id, message: Conversation.Message): Conversation {
                    messages += message; onAppend(message); return conversation
                }
            }, historyMutationExecutor = mock(), memoryApplicationService = mock(), memoryToolApplicationService = mock(),
            backgroundActivityCompletionApplicationService = mock(), memoryMessageRoutingApplicationService = mock(),
            toolCallSequenceFixerService = responding { ToolCallSequenceFixerService.FixResult(false, 0, 0, 0) }, settingsProvider = responding { UserProfile() },
            aiConfigurationProvider = responding { resolved }, runtimeCoordinator = coordinator, runtimeStateSyncService = mock(),
            distributedToolCatalog = responding { catalog }, agentSkillRuntimeCatalogService = responding { AgentSkillRuntimeCatalog(catalog, null) },
            aiToolRuntimeCatalogService = responding { AiToolRuntimeSelection(emptyList(), emptyList()) },
            aiToolCapabilityCatalogService = mock(), toolRoutingService = mock(),
            artifactService = responding {
                when (it.method.name.substringBefore('-')) {
                    "materialize" -> it.arguments[1]
                    "persistAndCommitMessageToolResults" -> it.arguments[2]
                    else -> Mockito.RETURNS_DEFAULTS.answer(it)
                }
            }, activeGenerationPublisher = mock(), messageTemporalContextService = MessageTemporalContextService(),
            stickyMessageInstructionService = StickyMessageInstructionService(), pendingSecretRevealService = PendingSecretRevealService(),
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
