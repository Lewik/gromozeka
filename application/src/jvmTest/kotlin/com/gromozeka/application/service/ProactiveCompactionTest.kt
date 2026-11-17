package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult as Checkpoint
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Clock

class ProactiveCompactionTest {
    @Test fun `ready summary preserves the retained suffix and concurrent append and applies only once`() = runBlocking {
        Fixture().use { f ->
            f.startTurn()
            f.prepare()
            assertEquals(ProactiveCompaction.Status.READY, f.preparation().status)
            assertEquals(1, f.generations)
            assertEquals(0, f.counts, "Preparation must not introduce a count RPC")
            assertContains(f.summaryRequests.single().messages.single().content.filterIsInstance<ContentItem.UserMessage>().single().text, "OLD_WORK")
            assertFalse(f.summaryRequests.single().toString().contains("RAW_TAIL"))
            f.messages = f.messages + f.message("new", "New user instruction")
            f.finishTurn(); f.startApplication()
            assertNotNull(f.applyReady())
            val active = ConversationContext(f.messages).messages()
            assertEquals(listOf("handoff", "RAW_TAIL" + "t".repeat(80_000), "New user instruction"), active.map { item ->
                when (val content = item.content.single()) {
                    is Checkpoint -> (content.payload as Checkpoint.Payload.ReadableSummary).text
                    is ContentItem.UserMessage -> content.text
                    else -> error("unexpected")
                }
            })
            assertEquals(ProactiveCompaction.Status.APPLIED, f.preparation().status)
            assertNull(f.applyReady())
            assertEquals(1, f.generations)
            assertEquals(1, f.commits)
        }
    }

    @Test fun `hard threshold waits for existing preparation rather than generating twice`() = runBlocking {
        Fixture().use { f ->
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.generate = { started.complete(Unit); release.await(); "handoff" }
            f.startTurn(); f.prepare(); started.await()
            f.finishTurn(); f.startTurn("next")
            f.inputTokens = 160_000
            val waiting = async { f.apply() }
            yield()
            assertFalse(waiting.isCompleted)
            assertEquals(1, f.generations)
            assertEquals(0, f.commits)
            release.complete(Unit)
            assertNotNull(waiting.await())
            assertEquals(1, f.generations)
        }
    }

    @Test fun `confirmed preparation failure uses one ordinary compaction at the hard threshold`() = runBlocking {
        Fixture().use { f ->
            f.generate = { throw IllegalStateException("private prompt and provider body") }
            f.startTurn(); f.prepare()
            assertEquals(ProactiveCompaction.Status.FAILED, f.preparation().status)
            assertFalse(f.preparation().toString().contains("private"))
            f.finishTurn(); f.startTurn("next"); f.inputTokens = 160_000
            f.generate = { "recovered" }
            assertNotNull(f.apply())
            assertEquals(2, f.generations)
            assertEquals(1, f.commits)
            assertEquals(ProactiveCompaction.Status.DISCARDED, f.preparation().status)
        }
    }

    @Test fun `Stop and interrupt while foreground is idle cancel preparation and reject late results`() = runBlocking {
        for (interrupt in listOf(false, true)) Fixture().use { f ->
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.generate = { withContext(NonCancellable) { started.complete(Unit); release.await(); "late" } }
            f.startTurn(); f.prepare(); started.await(); f.finishTurn()
            assertNull(f.coordinator.snapshot(f.conversation.id).activeTask)
            assertTrue(f.coordinator.snapshot(f.conversation.id).proactiveCompaction!!.pending)
            val cancelled = if (interrupt) f.coordinator.requestInterrupt(f.conversation.id) else f.coordinator.requestStop(f.conversation.id)
            assertTrue(cancelled)
            f.jobs.cancel(f.conversation.id)
            assertEquals(ProactiveCompaction.Status.CANCELLED, f.preparation().status)
            release.complete(Unit)
            yield()
            assertEquals(ProactiveCompaction.Status.CANCELLED, f.preparation().status)
            assertEquals(1, f.generations)
            assertEquals(0, f.commits)
        }
    }

    @Test fun `duplicate preparation and source-turn replay never start another model call`() = runBlocking {
        Fixture().use { f ->
            f.startTurn(); f.prepare(); f.prepare()
            assertEquals(1, f.generations)
            assertNotNull(f.apply())
            f.prepare()
            assertEquals(1, f.generations)
        }
    }

    @Test fun `editing captured input or changing prompt config or agent invalidates readiness`() = runBlocking {
        for (change in listOf("history", "prompt", "config", "agent", "pin", "thread")) Fixture().use { f ->
            f.startTurn(); f.prepare()
            when (change) {
                "history" -> f.messages = listOf(f.messages.first().copy(content = listOf(ContentItem.UserMessage("edited")))) + f.messages.drop(1)
                "prompt" -> f.prompts = listOf("Changed prompt")
                "config" -> f.resolved = f.resolved.copy(modelConfiguration = f.resolved.modelConfiguration.copy(displayName = "Changed"))
                "agent" -> f.agent = f.agent.copy(name = "Changed")
                "pin" -> f.pin = "Changed pin"
                "thread" -> f.conversation = f.conversation.copy(currentThread = Conversation.Thread.Id("fork"))
            }
            assertNull(f.apply())
            assertEquals(ProactiveCompaction.Status.DISCARDED, f.preparation().status, change)
            assertEquals(0, f.commits)
        }
    }

    @Test fun `revocation prevents publishing a prepared result`() = runBlocking {
        Fixture().use { f ->
            val release = CompletableDeferred<Unit>()
            f.generate = { release.await(); "unauthorized" }
            f.startTurn(); f.prepare()
            f.user = f.user.copy(aiAllowed = false)
            release.complete(Unit); yield()
            assertEquals(ProactiveCompaction.Status.FAILED, f.preparation().status)
            assertNull(f.preparation().checkpoint)
            assertEquals(0, f.commits)
        }
    }

    @Test fun `restart keeps ready results but does not repeat an unknown in flight invocation`() = runBlocking {
        Fixture().use { f ->
            f.startTurn(); f.prepare()
            f.restartedJobs().recoverAfterRestart(); yield()
            assertEquals(ProactiveCompaction.Status.READY, f.preparation().status)
            assertNotNull(f.apply())
        }
        Fixture().use { f ->
            val release = CompletableDeferred<Unit>()
            f.generate = { withContext(NonCancellable) { release.await(); "late" } }
            f.startTurn(); f.prepare()
            f.restartedJobs().recoverAfterRestart(); yield()
            assertEquals(ProactiveCompaction.Status.FAILED, f.preparation().status)
            assertTrue(f.preparation().outcomeUnknown)
            f.inputTokens = 160_000
            assertFailsWith<IllegalStateException> { f.apply() }
            release.complete(Unit); yield()
            assertEquals(ProactiveCompaction.Status.FAILED, f.preparation().status)
            assertEquals(1, f.generations)
        }
    }

    @Test fun `native detached compaction receives only the captured prefix in a separate transport namespace`() = runBlocking {
        Fixture(native = true).use { f ->
            f.startTurn(); f.prepare()
            assertEquals(1, f.nativeCalls)
            assertEquals(0, f.generations)
            assertEquals(listOf(f.messages.first().id), f.nativeRequest!!.messages.map { it.id })
            assertEquals(f.preparation().id, f.nativeRequest!!.options.toolContext["compactionTransportId"])
            assertEquals(f.conversation.id.value, f.nativeRequest!!.options.toolContext["conversationId"])
            assertNotNull(f.apply())
            assertEquals(listOf("old"), (f.messages[1].content.single() as Checkpoint).sourceMessageIds.map { it.value })
            assertEquals("tail", ConversationContext(f.messages).messages().last().id.value)
        }
    }

    @Test fun `zero prefix cannot trigger endless full compaction`() = runBlocking {
        Fixture().use { f ->
            f.messages = listOf(f.messages.last())
            f.startTurn(); f.prepare()
            assertNull(f.coordinator.proactiveCompaction(f.conversation.id))
            f.inputTokens = 160_000
            assertFailsWith<IllegalArgumentException> { f.apply() }
            assertEquals(0, f.generations)
        }
    }

    @Test fun `public Stop while waiting at the hard threshold never starts fallback`() = runBlocking {
        Fixture().use { f ->
            f.generate = { awaitCancellation() }
            f.startTurn(); f.prepare(); f.finishTurn(); f.startTurn("next")
            f.inputTokens = 160_000
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { f.apply() }
            assertFalse(waiting.isCompleted)
            val dispatcher = ConversationRuntimeDispatcher(f.coordinator, Mockito.mock(ConversationRuntimeEventBus::class.java),
                f.sync, Mockito.mock(ArtifactReferenceValidator::class.java), f.jobs)
            assertTrue(dispatcher.controlExecution(f.conversation.id, ConversationRuntimeControlAction.INTERRUPT))
            assertFailsWith<CancellationException> { waiting.await() }
            assertEquals(1, f.generations)
            assertEquals(0, f.commits)
            assertEquals(ProactiveCompaction.Status.CANCELLED, f.preparation().status)
        }
    }

    @Test fun `unknown outcome is not bypassed by changing config and Stop explicitly discards it`() = runBlocking {
        Fixture().use { f ->
            f.generate = { throw AiCallException(AiCallFailure(AiCallFailure.Kind.TRANSPORT, AiCallFailure.Outcome.UNKNOWN, 1)) }
            f.startTurn(); f.prepare()
            assertTrue(f.preparation().outcomeUnknown)
            f.resolved = f.resolved.copy(modelConfiguration = f.resolved.modelConfiguration.copy(displayName = "Changed"))
            f.prepare()
            assertEquals(1, f.generations)
            f.inputTokens = 160_000
            assertFailsWith<IllegalStateException> { f.apply() }
            assertEquals(1, f.generations)
            assertTrue(f.coordinator.requestStop(f.conversation.id))
            assertEquals(ProactiveCompaction.Status.CANCELLED, f.preparation().status)
            f.finishTurn(); f.startTurn("retry")
            f.generate = { "recovered" }
            assertNotNull(f.apply())
            assertEquals(2, f.generations)
        }
    }

    @Test fun `blocking generation also rechecks permissions and pins before publication`() = runBlocking {
        for (revoke in listOf(false, true)) Fixture().use { f ->
            f.startTurn(); f.inputTokens = 160_000
            f.generate = { if (revoke) f.user = f.user.copy(aiAllowed = false) else f.pin = "new pin"; "stale" }
            assertFails { f.apply() }
            assertEquals(1, f.generations)
            assertEquals(0, f.commits)
        }
    }

    @Test fun `preparation finishing after the agent becomes idle schedules application without AI capability or counting`() = runBlocking {
        Fixture().use { f ->
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.generate = { started.complete(Unit); release.await(); "handoff" }
            f.startTurn(); f.prepare(); started.await(); f.finishTurn()
            val finishedAgent = f.coordinator.snapshot(f.conversation.id).lastCompletion
            assertTrue(f.coordinator.listReadyWorkItems(10).isEmpty())
            release.complete(Unit); yield()
            val preparation = f.preparation()
            val work = f.coordinator.listReadyWorkItems(10).single()
            assertEquals(preparation.applicationTask().id, work.taskId)
            assertFalse(ConversationRuntimeCapability.AI_REQUEST_RESPONSE in work.requirements.capabilities)
            f.startApplication()
            assertFalse(f.task!!.isAgentWork())
            assertNotNull(f.applyReady())
            assertTrue(f.coordinator.completeActiveTask(f.conversation.id, f.task!!.id, f.executor, ConversationRuntimeTaskOutcome.CompleteWithoutNotification))
            assertTrue(f.coordinator.finishIfIdle(f.conversation.id))
            assertEquals(finishedAgent, f.coordinator.snapshot(f.conversation.id).lastCompletion)
            assertEquals(1, f.generations); assertEquals(0, f.counts); assertEquals(1, f.commits)
            assertTrue(f.coordinator.listPending(f.conversation.id).isEmpty())
        }
    }

    @Test fun `Stop removes queued application and preserves the completed agent turn`() = runBlocking {
        Fixture().use { f ->
            f.startTurn(); f.prepare(); f.finishTurn()
            val finishedAgent = f.coordinator.snapshot(f.conversation.id).lastCompletion
            assertEquals(1, f.coordinator.listReadyWorkItems(10).size)
            assertTrue(f.coordinator.requestStop(f.conversation.id))
            assertEquals(ProactiveCompaction.Status.CANCELLED, f.preparation().status)
            assertTrue(f.coordinator.listReadyWorkItems(10).isEmpty())
            assertTrue(f.coordinator.listPending(f.conversation.id).isEmpty())
            assertEquals(finishedAgent, f.coordinator.snapshot(f.conversation.id).lastCompletion)
        }
    }

    @Test fun `foreground application removes its queued idle work and leaves a new user input intact`() = runBlocking {
        Fixture().use { f ->
            f.startTurn(); f.prepare()
            val queued = f.task!!.copy(id = ConversationRuntimeTask.Id("new-user"), turnId = ConversationRuntimeTurnId("new-user"), idempotencyKey = "new-user")
            assertTrue(f.coordinator.submit(queued))
            assertNotNull(f.applyReady())
            assertEquals(listOf(queued.id), f.coordinator.listPending(f.conversation.id).map { it.id })
            assertEquals(1, f.commits)
        }
    }

    internal class Fixture(private val native: Boolean = false) : AutoCloseable {
        val now = Clock.System.now()
        var user = User(User.Id("user"), displayName = "User", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
        var conversation = Conversation(Conversation.Id("conversation"), Project.Id("project"),
            setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(AgentDefinition.Id("agent"))),
            currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now)
        var agent = AgentDefinition(AgentDefinition.Id("agent"), name = "Agent", prompts = emptyList(),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model")), type = AgentDefinition.Type.Global, createdAt = now, updatedAt = now)
        var resolved = ResolvedAiRuntime(AiConnection.OpenAiApi(AiConnection.Id("connection"), "Connection"),
            AiModelConfiguration(agent.runtimeSelection.modelConfigurationId, AiConnection.Id("connection"), "model", "Model",
                compaction = AiModelConfiguration.Compaction(thresholdTokens = 150_000, proactiveThresholdTokens = 75_000)),
            AiModelSpec("model", AiProvider.OPENAI, setOf(AiModelCapability.TEXT_GENERATION), AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(300_000, 10_000))))
        var messages = listOf(message("old", "OLD_WORK" + "o".repeat(320_000)), message("tail", "RAW_TAIL" + "t".repeat(80_000)))
        var prompts = listOf("Instructions")
        var pin = ""
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val executor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server"))
        val descriptor = ConversationRuntimeExecutorDescriptor(executor, ConversationRuntimeCapability.entries.toSet())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sync = Mockito.mock(ConversationRuntimeStateSyncService::class.java)
        val jobs = ProactiveCompactionJobs(coordinator, sync, descriptor, scope)
        var task: ConversationRuntimeTask? = null
        var inputTokens = 100_000
        var counts = 0
        var generations = 0
        var commits = 0
        var nativeCalls = 0
        var nativeRequest: AiRuntimeRequest? = null
        val summaryRequests = mutableListOf<AiRuntimeRequest>()
        var generate: suspend () -> String = { "handoff" }
        val runtime = object : AiRuntime {
            override val capabilities = AiRuntimeCapabilities(supportsNativeCompaction = native, supportsDetachedNativeCompaction = native)
            override suspend fun inspectContext(request: AiRuntimeRequest, allowProviderCount: Boolean): AiContextInspection {
                if (allowProviderCount) counts++
                return AiContextInspection(inputTokens, AiContextInspection.Precision.PROVIDER_COUNT, native)
            }
            override suspend fun compact(request: AiRuntimeRequest): AiRuntimeResponse {
                nativeCalls++; nativeRequest = request
                return AiRuntimeResponse(listOf(AiAssistantMessage(listOf(Checkpoint(
                    Checkpoint.Payload.OpaqueProviderState(buildJsonObject { put("encrypted_content", "opaque") }),
                    Checkpoint.Origin.GROMOZEKA_POLICY, coverage = Checkpoint.Coverage.ALL_PREVIOUS,
                    sourceMessageIds = request.messages.map { it.id }, providerScope = Checkpoint.ProviderScope("OPENAI_API"))))))
            }
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                generations++; summaryRequests += request
                return AiRuntimeResponse(listOf(AiAssistantMessage(listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText(generate()))))), usage = AiUsage(100, 10))
            }
            override fun stream(request: AiRuntimeRequest) = emptyFlow<AiRuntimeResponse>()
        }
        val providers = Mockito.mock(AiRuntimeProvider::class.java) { runtime }
        val configs = Mockito.mock(AiConfigurationProvider::class.java) { resolved }
        val conversations = Mockito.mock(ConversationRepository::class.java) { conversation }
        val users = Mockito.mock(UserDirectoryService::class.java) { user }
        val agents = Mockito.mock(AgentDomainService::class.java) { agent }
        val projects = Mockito.mock(ProjectAccessService::class.java)
        val workers = Mockito.mock(WorkerAccessService::class.java)
        val pins = Mockito.mock(ConversationPinnedPrompt::class.java) { listOf(pin).filter { it.isNotEmpty() } }
        val history = Mockito.mock(ThreadMessageRepository::class.java) { invocation -> when {
            invocation.method.name.startsWith("contains") -> messages.any { it.id.value == invocation.arguments[1] }
            invocation.method.name.startsWith("getMessagesByThread") -> messages
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        } }
        val artifacts = Mockito.mock(ConversationArtifactApplicationService::class.java) { invocation ->
            if (invocation.method.name.startsWith("materializeSnapshot")) invocation.arguments[1] else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        val committer = object : ContextCompactionCommitter {
            override suspend fun commit(expectedThreadId: Conversation.Thread.Id, message: Conversation.Message, preparedCompactionId: String?): Conversation {
                assertEquals(expectedThreadId, conversation.currentThread)
                val checkpoint = message.content.single() as Checkpoint
                val end = messages.indexOfLast { it.id in checkpoint.sourceMessageIds }
                messages = messages.take(end + 1) + message + messages.drop(end + 1)
                conversation = conversation.copy(currentThread = Conversation.Thread.Id("compacted-${++commits}"))
                inputTokens = 20_000
                return conversation
            }
        }
        val summaries = MessageSquashService(providers, configs, agents, artifacts, conversations, history, ToolCallPairingService(), pins, committer)
        val access = CompactionPreparationScope(conversations, agents, users, projects, workers, configs, pins)
        val service = ConversationCompactionService(configs, history, summaries, committer, coordinator,
            Mockito.mock(ConversationRuntimeEventBus::class.java), providers, jobs, access, ToolCallPairingService())
        fun message(id: String, text: String) = Conversation.Message(Conversation.Message.Id(id), conversation.id,
            role = Conversation.Message.Role.USER, content = listOf(ContentItem.UserMessage(text)), createdAt = now)
        fun request() = AiRuntimeRequest(prompts, messages, options = AiRuntimeOptions(toolContext = mapOf(
            "conversationId" to conversation.id.value, "threadId" to conversation.currentThread.value, "userId" to user.id.value), usagePurpose = "CONVERSATION"))
        suspend fun startTurn(id: String = "source") {
            val root = ConversationRuntimeTask(ConversationRuntimeTask.Id(id), conversation.id, actorUserId = user.id,
                payload = ConversationRuntimeTask.Payload.AgentResponse(messages.first().id, agent.id), placement = QueuedMessagePlacement.END_OF_TURN,
                idempotencyKey = id, requirements = ConversationRuntimeTaskRequirements(descriptor.capabilities, ConversationRuntimeTaskTarget.Server), createdAt = now)
            assertTrue(coordinator.submit(root))
            task = assertNotNull(coordinator.claimDeliveredTask(conversation.id, root.id, executor, descriptor.capabilities, emptySet()))
            assertTrue(coordinator.markActiveTaskStarted(conversation.id, root.id, executor, now))
        }
        suspend fun finishTurn() {
            assertTrue(coordinator.completeActiveTask(conversation.id, task!!.id, executor, ConversationRuntimeTaskOutcome.CompleteTurn))
            coordinator.finishIfIdle(conversation.id)
        }
        suspend fun startApplication() {
            val application = preparation().applicationTask()
            task = assertNotNull(coordinator.claimDeliveredTask(conversation.id, application.id, executor, descriptor.capabilities, emptySet()))
            assertTrue(coordinator.markActiveTaskStarted(conversation.id, application.id, executor, now))
        }
        suspend fun applyReady() = service.applyPreparedIfReady(task!!, conversation, agent, prompts) {
            check(coordinator.confirmActiveTaskOwner(conversation.id, task!!.id, executor))
        }
        suspend fun prepare() = service.prepareAfterResponse(task!!, executor, conversation, agent, request())
        suspend fun preparation() = assertNotNull(coordinator.proactiveCompaction(conversation.id))
        suspend fun apply() = service.compactIfNeeded(task!!, conversation, agent, runtime, request(), false) {
            check(coordinator.confirmActiveTaskOwner(conversation.id, task!!.id, executor))
        }
        fun restartedJobs() = ProactiveCompactionJobs(coordinator, sync,
            descriptor.copy(identity = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("restarted"))), scope)
        override fun close() { scope.cancel() }
    }
}
