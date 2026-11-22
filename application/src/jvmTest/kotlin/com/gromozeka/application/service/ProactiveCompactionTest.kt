package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult as Checkpoint
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Clock

class ProactiveCompactionTest {
    @Test fun `native blocking and proactive compactions preserve the materialized active prefix and current pins`() = runBlocking {
        for (background in listOf(false, true)) Fixture(native = true).use { f ->
            val archive = List(15_997) { f.message("archived-$it", "archived text") }
            val checkpoint = f.message("earlier", "").copy(role = Conversation.Message.Role.ASSISTANT, content = listOf(Checkpoint(
                Checkpoint.Payload.OpaqueProviderState(buildJsonObject { put("encrypted_content", "previous") }),
                Checkpoint.Origin.PROVIDER_AUTO, coverage = Checkpoint.Coverage.ALL_PREVIOUS,
                providerScope = Checkpoint.ProviderScope("OPENAI_API"),
            )))
            val (old, tail) = f.messages
            val materializedOld = old.copy(instructions = old.instructions + listOf(
                Conversation.Message.Instruction.MessageTemporalRuntimeContext(MessageTemporalContext(f.now, 37)),
                Conversation.Message.Instruction.RuntimeObservation("<runtime-observation>exact captured state</runtime-observation>"),
            ))
            f.messages = archive + listOf(checkpoint, old, tail)
            val activeRequest = f.request().copy(messages = listOf(checkpoint, materializedOld, tail))
            f.startTurn()
            f.pin = "new pin"
            if (background) {
                f.service.prepareAfterResponse(f.task!!, f.executor, f.conversation, f.agent, activeRequest)
                assertEquals(ProactiveCompaction.Status.READY, f.preparation().status)
                f.messages += f.message("append", "arrived after capture")
                assertNotNull(f.applyReady(activeRequest.copy(messages = activeRequest.messages + f.messages.last())))
                assertEquals("append", f.messages.last().id.value)
            } else {
                f.inputTokens = 160_000
                assertNotNull(f.service.compactIfNeeded(f.task!!, f.conversation, f.agent, f.runtime,
                    activeRequest.copy(systemPrompts = f.prompts + "old pin"), false,
                    promptScope = f.prompts, unpinnedPrompts = f.prompts) {})
            }
            assertTrue(f.bodyReads.isEmpty(), "Native compaction must not replace the captured prefix with raw bodies")
            assertEquals(listOf(checkpoint, materializedOld), f.nativeRequest!!.messages)
            assertEquals(f.prompts + "new pin", f.nativeRequest!!.systemPrompts)
            val saved = f.messages[15_999].content.single() as Checkpoint
            assertTrue(saved.sourceMessageIds.isEmpty())
            assertEquals(old.id, saved.sourceBoundaryMessageId)
            assertEquals(tail, f.messages[16_000])
        }
    }

    @Test fun `external channel never starts a durable proactive preparation`() = runBlocking {
        Fixture(native = true).use { f ->
            f.conversation = f.conversation.copy(
                externalChannel = ExternalConversationChannel("telegram", "connection", "chat"),
            )
            f.startTurn()

            assertFalse(f.service.prepareAfterResponse(
                f.task!!, f.executor, f.conversation, f.agent, f.request(),
            ))

            assertNull(f.coordinator.proactiveCompaction(f.conversation.id))
            assertEquals(0, f.nativeCalls)
            assertNull(f.nativeRequest)
        }
    }

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

    @Test fun `ready preparation is discarded when the enriched transport prefix changes`() = runBlocking {
        Fixture().use { f ->
            fun tool(name: String) = object : AiToolCallback {
                override val definition = AiToolDefinition(name, "Synthetic $name", """{"type":"object"}""")
                override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.SERVER)
                override fun call(toolInput: String, context: ToolExecutionContext?) = error("Not executed")
            }
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.generate = { started.complete(Unit); release.await(); "handoff" }
            val initial = f.request().copy(
                systemPrompts = f.prompts + "Development slots: no active lease",
                tools = listOf(tool("inspect_state")),
            )
            f.startTurn()
            assertTrue(f.service.prepareAfterResponse(
                f.task!!, f.executor, f.conversation, f.agent, initial, promptScope = f.prompts,
            ))
            started.await()
            val changed = initial.copy(
                systemPrompts = f.prompts + "Development slots: active lease lease-7",
                tools = initial.tools + tool("grz_slot_release"),
            )
            release.complete(Unit)
            yield()
            assertEquals(ProactiveCompaction.Status.READY, f.preparation().status)

            assertNull(f.applyReady(changed))
            assertEquals(ProactiveCompaction.Status.DISCARDED, f.preparation().status)
            assertEquals(0, f.commits)
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

    @Test fun `history mutation batch cancels stale preparation and allows one fresh preparation at its end`() = runBlocking {
        Fixture().use { f ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.generate = {
                if (f.generations == 1) withContext(NonCancellable) {
                    started.complete(Unit); release.await(); "stale"
                } else "fresh"
            }
            f.startTurn()
            assertTrue(f.prepare())
            started.await()

            val first = f.historyMutation("history-1")
            val second = f.historyMutation("history-2")
            assertTrue(f.coordinator.submit(first))
            assertTrue(f.coordinator.submit(second))
            f.finishTurn()
            f.task = assertNotNull(f.coordinator.claimDeliveredTask(
                f.conversation.id, first.id, f.executor, f.descriptor.capabilities, emptySet(),
            ))
            assertTrue(f.coordinator.markActiveTaskStarted(f.conversation.id, first.id, f.executor, f.now))

            assertNotNull(f.service.reconcileHistoryMutations(f.conversation.id))
            val cancelled = f.preparation()
            assertEquals(ProactiveCompaction.Status.CANCELLED, cancelled.status)
            assertTrue(cancelled.reprepareAfterHistoryMutation)
            assertFalse(f.prepare(), "The remaining mutation keeps the batch open while the old provider call is still unwinding")

            f.conversation = f.conversation.copy(currentThread = Conversation.Thread.Id("after-history-1"))
            assertTrue(f.coordinator.completeActiveTask(
                f.conversation.id,
                first.id,
                f.executor,
                ConversationRuntimeTaskOutcome.HistoryChanged(ConversationHistoryMutationKind.DELETE),
            ))
            f.task = assertNotNull(f.coordinator.claimDeliveredTask(
                f.conversation.id, second.id, f.executor, f.descriptor.capabilities, emptySet(),
            ))
            assertTrue(f.coordinator.markActiveTaskStarted(f.conversation.id, second.id, f.executor, f.now))

            assertTrue(f.prepare(), "The last mutation may recheck the new history")
            val fresh = f.preparation()
            if (fresh.status == ProactiveCompaction.Status.RUNNING) f.jobs.await(fresh)
            assertEquals(ProactiveCompaction.Status.READY, f.preparation().status)
            assertFalse(f.preparation().reprepareAfterHistoryMutation)
            assertEquals(2, f.generations)
            release.complete(Unit)
            yield()
            assertEquals(fresh.id, f.preparation().id, "The cancelled preparation cannot replace its successor")
        }
    }

    @Test fun `history mutation reprepare marker clears only with no other mutation in the same transition`() = runBlocking {
        Fixture().use { f ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.generate = { withContext(NonCancellable) { started.complete(Unit); release.await(); "stale" } }
            f.startTurn()
            assertTrue(f.prepare())
            started.await()
            val first = f.historyMutation("history-1")
            val second = f.historyMutation("history-2")
            assertTrue(f.coordinator.submit(first))
            assertTrue(f.coordinator.submit(second))
            f.finishTurn()
            f.task = assertNotNull(f.coordinator.claimDeliveredTask(
                f.conversation.id, first.id, f.executor, f.descriptor.capabilities, emptySet(),
            ))
            assertTrue(f.coordinator.markActiveTaskStarted(f.conversation.id, first.id, f.executor, f.now))
            val cancelled = assertNotNull(f.service.reconcileHistoryMutations(f.conversation.id))

            assertFalse(f.coordinator.finishHistoryMutationReprepare(cancelled, first.id))
            assertTrue(f.preparation().reprepareAfterHistoryMutation)
            assertTrue(f.coordinator.completeActiveTask(
                f.conversation.id, first.id, f.executor,
                ConversationRuntimeTaskOutcome.HistoryChanged(ConversationHistoryMutationKind.DELETE),
            ))
            f.task = assertNotNull(f.coordinator.claimDeliveredTask(
                f.conversation.id, second.id, f.executor, f.descriptor.capabilities, emptySet(),
            ))
            assertTrue(f.coordinator.markActiveTaskStarted(f.conversation.id, second.id, f.executor, f.now))

            assertTrue(f.coordinator.finishHistoryMutationReprepare(cancelled, second.id))
            assertFalse(f.preparation().reprepareAfterHistoryMutation)
            release.complete(Unit)
            Unit
        }
    }

    @Test fun `late ready result is atomically rejected when a history mutation is queued`() = runBlocking {
        Fixture().use { f ->
            f.startTurn()
            val source = assertNotNull(f.task)
            val preparation = ProactiveCompaction(
                id = "late-ready",
                conversationId = f.conversation.id,
                sourceTaskId = source.id,
                sourceTurnId = source.turnId,
                executor = f.executor,
                agentId = f.agent.id,
                actorUserId = f.user.id,
                threadId = f.conversation.currentThread,
                capturedMessageCount = f.messages.size,
                historyFingerprint = "a".repeat(64),
                scopeFingerprint = "b".repeat(64),
                inputTokens = f.inputTokens,
                sourceCount = 1,
                retainedCount = f.messages.size - 1,
                createdAt = f.now,
            )
            assertTrue(f.coordinator.beginProactiveCompaction(preparation))
            val mutation = source.copy(
                id = ConversationRuntimeTask.Id("queued-history"),
                turnId = ConversationRuntimeTurnId("queued-history"),
                payload = ConversationRuntimeTask.Payload.HistoryMutation(
                    ConversationHistoryMutation.Delete(listOf(f.messages.first().id)),
                    f.conversation.currentThread,
                ),
                idempotencyKey = "queued-history",
            )
            assertTrue(f.coordinator.submit(mutation))
            val ready = preparation.copy(
                status = ProactiveCompaction.Status.READY,
                checkpoint = f.message(preparation.id, "late result").copy(role = Conversation.Message.Role.ASSISTANT),
                updatedAt = Clock.System.now(),
            )

            assertFalse(f.coordinator.updateProactiveCompaction(preparation, ready))
            val stored = f.preparation()
            assertEquals(ProactiveCompaction.Status.CANCELLED, stored.status)
            assertTrue(stored.reprepareAfterHistoryMutation)
            assertEquals(listOf(mutation.id), f.coordinator.listPending(f.conversation.id).map { it.id })
        }
    }

    @Test fun `queued history mutation defers preparation without starting a model call`() = runBlocking {
        Fixture().use { f ->
            f.startTurn()
            val source = assertNotNull(f.task)
            val mutation = source.copy(
                id = ConversationRuntimeTask.Id("queued-before-preparation"),
                turnId = ConversationRuntimeTurnId("queued-before-preparation"),
                payload = ConversationRuntimeTask.Payload.HistoryMutation(
                    ConversationHistoryMutation.Delete(listOf(f.messages.first().id)),
                    f.conversation.currentThread,
                ),
                idempotencyKey = "queued-before-preparation",
            )
            assertTrue(f.coordinator.submit(mutation))

            assertFalse(f.prepare())
            assertEquals(0, f.generations)
            assertEquals(ProactiveCompaction.Status.CANCELLED, f.preparation().status)
            assertTrue(f.preparation().reprepareAfterHistoryMutation)

            f.finishTurn()
            f.task = assertNotNull(f.coordinator.claimDeliveredTask(
                f.conversation.id, mutation.id, f.executor, f.descriptor.capabilities, emptySet(),
            ))
            assertTrue(f.coordinator.markActiveTaskStarted(f.conversation.id, mutation.id, f.executor, f.now))
            assertTrue(f.prepare())
            assertEquals(1, f.generations)
            assertEquals(ProactiveCompaction.Status.READY, f.preparation().status)
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
        for (change in listOf("history", "boundary", "author", "prompt", "config", "agent", "pin", "thread")) Fixture().use { f ->
            f.startTurn(); f.prepare()
            when (change) {
                "history" -> {
                    f.messages = listOf(f.message("edited", "new version")) + f.messages.drop(1)
                    f.conversation = f.conversation.copy(currentThread = Conversation.Thread.Id("edited-thread"))
                }
                "boundary" -> f.messages = f.messages.dropLast(1)
                "author" -> f.messages = listOf(f.messages.first().copy(author = Conversation.Message.Author.User(f.user.id, "renamed"))) + f.messages.drop(1)
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
            val checkpoint = f.messages[1].content.single() as Checkpoint
            assertTrue(checkpoint.sourceMessageIds.isEmpty())
            assertEquals("old", checkpoint.sourceBoundaryMessageId?.value)
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
                    sourceBoundaryMessageId = request.messages.last().id, providerScope = Checkpoint.ProviderScope("OPENAI_API"))))))
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
        val bodyReads = mutableListOf<Conversation.Message.Id>()
        val history = Mockito.mock(ThreadMessageRepository::class.java) { invocation -> when {
            invocation.method.name.startsWith("contains") -> messages.any { it.id.value == invocation.arguments[1] }
            invocation.method.name.startsWith("compactionHeaders") -> messages.map(CompactionMessageHeader::from)
            invocation.method.name.startsWith("compactionBodies") -> {
                val versions = messages.associateBy { it.id }
                (invocation.arguments[1] as List<*>).map { versions.getValue(it as Conversation.Message.Id).also { msg -> bodyReads += msg.id } }
            }
            invocation.method.name.startsWith("getContextMessagesByThread") -> messages
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        } }
        val artifacts = Mockito.mock(ConversationArtifactApplicationService::class.java) { invocation ->
            if (invocation.method.name.startsWith("materializeSnapshot")) invocation.arguments[1] else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        val committer = object : ContextCompactionCommitter {
            override suspend fun commit(expectedThreadId: Conversation.Thread.Id, message: Conversation.Message, preparedCompactionId: String?): Conversation {
                assertEquals(expectedThreadId, conversation.currentThread)
                val checkpoint = message.content.single() as Checkpoint
                val end = if (checkpoint.coversAllPrevious) {
                    messages.indexOfFirst { it.id == checkpoint.sourceBoundaryMessageId }
                } else messages.indexOfLast { it.id in checkpoint.sourceMessageIds }
                check(end >= 0)
                messages = messages.take(end + 1) + message + messages.drop(end + 1)
                conversation = conversation.copy(currentThread = Conversation.Thread.Id("compacted-${++commits}"))
                inputTokens = 20_000
                return conversation
            }
        }
        val summaries = CompactionTextService(providers, configs, artifacts, pins)
        val access = CompactionPreparationScope(conversations, agents, users, projects, workers, configs, pins)
        val service = ConversationCompactionService(configs, history, summaries, committer, coordinator,
            Mockito.mock(ConversationRuntimeEventBus::class.java), providers, jobs, access, ToolCallPairingService())
        fun message(id: String, text: String) = Conversation.Message(Conversation.Message.Id(id), conversation.id,
            role = Conversation.Message.Role.USER, content = listOf(ContentItem.UserMessage(text)), createdAt = now)
        fun historyMutation(id: String) = ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id(id), conversationId = conversation.id, actorUserId = user.id,
            payload = ConversationRuntimeTask.Payload.HistoryMutation(
                mutation = ConversationHistoryMutation.Delete(listOf(messages.first().id)),
                expectedThreadId = conversation.currentThread,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = id,
            requirements = ConversationRuntimeTaskRequirements(descriptor.capabilities, ConversationRuntimeTaskTarget.Server),
            createdAt = now,
        )
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
        suspend fun applyReady(request: AiRuntimeRequest = request()) =
            service.applyPreparedIfReady(task!!, conversation, agent, prompts, request) {
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
