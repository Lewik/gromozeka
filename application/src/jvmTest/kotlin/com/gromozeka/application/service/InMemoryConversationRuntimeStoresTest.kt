package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeMemoryOperation
import com.gromozeka.domain.service.ConversationRuntimeSchedulingSignal
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskIncident
import com.gromozeka.domain.service.ConversationRuntimeTaskOutcome
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.ConversationRuntimeServerSessionId
import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryConversationRuntimeStoresTest {
    private val conversationId = Conversation.Id("conversation-1")
    private val agentDefinitionId = AgentDefinition.Id("agent-1")

    @Test
    fun `coordinator indexes only the head runnable task`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val first = task("message-1", QueuedMessagePlacement.END_OF_TURN)
        val second = task("message-2", QueuedMessagePlacement.END_OF_TURN)

        assertTrue(coordinator.submit(first))
        assertTrue(coordinator.submit(second))

        assertEquals(
            ConversationRuntimeSchedulingSignal.Changed(conversationId),
            withTimeout(1_000) {
                coordinator.schedulingSignals.first { it is ConversationRuntimeSchedulingSignal.Changed }
            },
        )
        assertEquals(listOf(first.id), coordinator.listReadyWorkItems(10).map { it.taskId })
        assertNull(coordinator.claimAsEligibleWorker(second, worker("worker-1")))
        assertEquals(first, coordinator.claimAsEligibleWorker(first, worker("worker-2")))
        assertTrue(coordinator.listReadyWorkItems(10).isEmpty())
        assertTrue(coordinator.confirmActiveTaskOwner(conversationId, first.id, executor(worker("worker-2"))))
        assertFalse(coordinator.confirmActiveTaskOwner(conversationId, first.id, executor(worker("worker-1"))))

        assertTrue(
            coordinator.markActiveTaskStarted(
                conversationId,
                first.id,
                executor(worker("worker-2")),
                Clock.System.now(),
            )
        )
        assertTrue(
            coordinator.completeActiveTask(
                conversationId,
                first.id,
                executor(worker("worker-2")),
                ConversationRuntimeTaskOutcome.CompleteTurn,
            )
        )
        assertEquals(listOf(second.id), coordinator.listReadyWorkItems(10).map { it.taskId })
        assertEquals(second, coordinator.claimAsEligibleWorker(second, worker("worker-3")))
    }

    @Test
    fun `coordinator removes paused work from the ready index and restores it after resume`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val activeTask = task("message-1", QueuedMessagePlacement.END_OF_TURN)
        val pendingTask = task("message-2", QueuedMessagePlacement.END_OF_TURN)
        val worker = worker("worker-1")

        assertTrue(coordinator.submit(activeTask))
        assertTrue(coordinator.submit(pendingTask))
        assertEquals(activeTask, coordinator.claimAsEligibleWorker(activeTask, worker))
        assertTrue(
            coordinator.markActiveTaskStarted(
                conversationId,
                activeTask.id,
                executor(worker),
                Clock.System.now(),
            )
        )
        assertTrue(coordinator.requestPause(conversationId))
        assertTrue(coordinator.listReadyWorkItems(10).isEmpty())
        assertTrue(
            coordinator.completeActiveTask(
                conversationId,
                activeTask.id,
                executor(worker),
                ConversationRuntimeTaskOutcome.CompleteTurn,
            )
        )
        assertTrue(coordinator.listReadyWorkItems(10).isEmpty())
        assertTrue(coordinator.requestResume(conversationId))
        assertEquals(listOf(pendingTask.id), coordinator.listReadyWorkItems(10).map { it.taskId })
    }

    @Test
    fun `coordinator exposes active insertions without letting another task run`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val active = task("active-message", QueuedMessagePlacement.END_OF_TURN)
        val steering = task("steering-message", QueuedMessagePlacement.AFTER_TOOL_RESULT)

        assertTrue(coordinator.submit(active))
        assertEquals(active, coordinator.claimAsEligibleWorker(active, worker("worker-1")))
        assertTrue(coordinator.submit(steering))

        assertEquals(
            listOf(steering.id),
            coordinator.claimActiveInsertions(
                conversationId,
                active.id,
                executor(worker("worker-1")),
                QueuedMessagePlacement.AFTER_TOOL_RESULT,
            ).map { it.id },
        )
        assertNull(coordinator.claimAsEligibleWorker(steering, worker("worker-2")))
    }

    @Test
    fun `coordinator promotes missed active insertions before ordinary queued turns`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val active = task("active-message", QueuedMessagePlacement.END_OF_TURN)
        val queued = task("queued-message", QueuedMessagePlacement.END_OF_TURN)
        val steering = task("steering-message", QueuedMessagePlacement.AFTER_TOOL_RESULT)

        assertTrue(coordinator.submit(active))
        assertEquals(active, coordinator.claimAsEligibleWorker(active, worker("worker-1")))
        assertTrue(coordinator.submit(queued))
        assertTrue(coordinator.submit(steering))

        assertTrue(
            coordinator.markActiveTaskStarted(
                conversationId,
                active.id,
                executor(worker("worker-1")),
                Clock.System.now(),
            )
        )
        assertTrue(
            coordinator.completeActiveTask(
                conversationId,
                active.id,
                executor(worker("worker-1")),
                ConversationRuntimeTaskOutcome.CompleteTurn,
            )
        )

        val promoted = coordinator.claimAsEligibleWorker(steering, worker("worker-2"))
        assertEquals(steering.id, promoted?.id)
        assertEquals(QueuedMessagePlacement.END_OF_TURN, promoted?.placement)
        assertTrue(
            coordinator.markActiveTaskStarted(
                conversationId,
                promoted!!.id,
                executor(worker("worker-2")),
                Clock.System.now(),
            )
        )
        assertTrue(
            coordinator.completeActiveTask(
                conversationId,
                promoted.id,
                executor(worker("worker-2")),
                ConversationRuntimeTaskOutcome.CompleteTurn,
            )
        )

        assertEquals(
            queued.id,
            coordinator.claimAsEligibleWorker(queued, worker("worker-3"))?.id,
        )
    }

    @Test
    fun `continuation stays ahead of an async root input`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val root = task("root-message", QueuedMessagePlacement.END_OF_TURN)
        val continuation = llmTask("root-message-llm", root)
        val memoryCompletion = memoryCompletionTask("memory-run-1")
        val worker = worker("worker-1")

        assertTrue(coordinator.submit(root))
        assertEquals(root, coordinator.claimAsEligibleWorker(root, worker))
        assertTrue(coordinator.markActiveTaskStarted(conversationId, root.id, executor(worker), Clock.System.now()))
        assertTrue(coordinator.submit(memoryCompletion))
        assertTrue(
            coordinator.completeActiveTask(
                conversationId,
                root.id,
                executor(worker),
                ConversationRuntimeTaskOutcome.Continue(continuation),
            )
        )

        assertEquals(continuation.id, coordinator.listReadyWorkItems(10).single().taskId)
        assertEquals(continuation, coordinator.claimAsEligibleWorker(continuation, worker("worker-2")))
    }

    @Test
    fun `one active task can install only one continuation`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val root = task("root-message", QueuedMessagePlacement.END_OF_TURN)
        val firstContinuation = llmTask("continuation-1", root)
        val secondContinuation = llmTask("continuation-2", root)
        val worker = worker("worker-1")

        assertTrue(coordinator.submit(root))
        assertEquals(root, coordinator.claimAsEligibleWorker(root, worker))
        assertTrue(coordinator.markActiveTaskStarted(conversationId, root.id, executor(worker), Clock.System.now()))

        val completions = listOf(firstContinuation, secondContinuation).map { continuation ->
            async {
                coordinator.completeActiveTask(
                    conversationId,
                    root.id,
                    executor(worker),
                    ConversationRuntimeTaskOutcome.Continue(continuation),
                )
            }
        }.awaitAll()

        assertEquals(1, completions.count { it })
        assertEquals(1, coordinator.listReadyWorkItems(10).size)
        assertEquals(1, listOfNotNull(coordinator.snapshot(conversationId).continuationTask).size)
    }

    @Test
    fun `consumed active insertion cannot be submitted again`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val active = task("active-message", QueuedMessagePlacement.END_OF_TURN)
        val steering = task("steering-message", QueuedMessagePlacement.AFTER_TOOL_RESULT)
        val worker = worker("worker-1")

        assertTrue(coordinator.submit(active))
        assertEquals(active, coordinator.claimAsEligibleWorker(active, worker))
        assertTrue(coordinator.submit(steering))
        assertEquals(
            listOf(steering),
            coordinator.claimActiveInsertions(
                conversationId,
                active.id,
                executor(worker),
                QueuedMessagePlacement.AFTER_TOOL_RESULT,
            ),
        )

        assertFalse(coordinator.submit(steering))
    }

    @Test
    fun `active insertions return to the queue when task outcome is unknown`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val active = task("active-message", QueuedMessagePlacement.END_OF_TURN)
        val steering = task("steering-message", QueuedMessagePlacement.AFTER_TOOL_RESULT)
        val worker = worker("worker-1")

        assertTrue(coordinator.submit(active))
        assertEquals(active, coordinator.claimAsEligibleWorker(active, worker))
        assertTrue(coordinator.submit(steering))
        assertEquals(
            listOf(steering),
            coordinator.claimActiveInsertions(
                conversationId,
                active.id,
                executor(worker),
                QueuedMessagePlacement.AFTER_TOOL_RESULT,
            ),
        )
        assertTrue(
            coordinator.markActiveTaskStarted(
                conversationId,
                active.id,
                executor(worker),
                Clock.System.now(),
            )
        )

        assertNotNull(
            coordinator.markActiveTaskInDoubt(
                conversationId = conversationId,
                taskId = active.id,
                executor = executor(worker),
                message = "Worker connection was lost",
            )
        )

        val snapshot = coordinator.snapshot(conversationId)
        assertTrue(snapshot.activeInsertions.isEmpty())
        assertEquals(
            listOf(
                ConversationRuntimeTask.Payload.ExecutionIncident(active.id),
                steering.payload,
            ),
            snapshot.pendingTasks.map { it.payload },
        )
        assertEquals(
            QueuedMessagePlacement.END_OF_TURN,
            snapshot.pendingTasks.last().placement,
        )
    }

    @Test
    fun `started task cannot be claimed for execution again`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val active = task("active-message", QueuedMessagePlacement.END_OF_TURN)
        val worker = worker("worker-1")

        assertTrue(coordinator.submit(active))
        assertEquals(active, coordinator.claimAsEligibleWorker(active, worker))
        assertTrue(
            coordinator.markActiveTaskStarted(
                conversationId,
                active.id,
                executor(worker),
                Clock.System.now(),
            )
        )

        assertNull(coordinator.claimAsEligibleWorker(active, worker))
    }

    @Test
    fun `delivery failure closes a continuation instead of leaving it ready`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val root = task("root-message", QueuedMessagePlacement.END_OF_TURN)
        val continuation = llmTask("continuation-message", root)
        val worker = worker("worker-1")

        assertTrue(coordinator.submit(root))
        assertEquals(root, coordinator.claimAsEligibleWorker(root, worker))
        assertTrue(coordinator.markActiveTaskStarted(conversationId, root.id, executor(worker), Clock.System.now()))
        assertTrue(
            coordinator.completeActiveTask(
                conversationId,
                root.id,
                executor(worker),
                ConversationRuntimeTaskOutcome.Continue(continuation),
            )
        )

        assertNotNull(
            coordinator.recordPendingTaskDeliveryFailure(
                conversationId = conversationId,
                taskId = continuation.id,
                executor = executor(worker("worker-2")),
                message = "Continuation could not reach its executor",
            )
        )

        val snapshot = coordinator.snapshot(conversationId)
        assertNull(snapshot.continuationTask)
        assertEquals(
            ConversationRuntimeTask.Payload.ExecutionIncident(continuation.id),
            snapshot.pendingTasks.single().payload,
        )
    }

    @Test
    fun `coordinator gates claim by worker requirements`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val rootTask = task("root-message", QueuedMessagePlacement.END_OF_TURN)
        val rootWorker = worker("root-worker")
        val llmTask = llmTask("llm-message", rootTask)

        assertTrue(coordinator.submit(rootTask))
        assertEquals(rootTask, coordinator.claimAsEligibleWorker(rootTask, rootWorker))
        assertTrue(
            coordinator.markActiveTaskStarted(
                conversationId,
                rootTask.id,
                executor(rootWorker),
                Clock.System.now(),
            )
        )
        assertTrue(
            coordinator.completeActiveTask(
                conversationId,
                rootTask.id,
                executor(rootWorker),
                ConversationRuntimeTaskOutcome.Continue(llmTask),
            )
        )
        assertNull(
            coordinator.claimDeliveredTask(
                conversationId = conversationId,
                taskId = llmTask.id,
                executor = executor(worker("turn-worker")),
                executorCapabilities = setOf(ConversationRuntimeCapability.CONVERSATION_TURN),
                workerWorkspaceMountIds = emptySet(),
            )
        )
        assertEquals(
            llmTask,
            coordinator.claimDeliveredTask(
                conversationId = conversationId,
                taskId = llmTask.id,
                executor = executor(worker("llm-worker")),
                executorCapabilities = setOf(
                    ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
                workerWorkspaceMountIds = emptySet(),
            )
        )
    }

    @Test
    fun `worker registry rejects a second live session for the same worker`() = runBlocking {
        val registry = InMemoryConversationRuntimeWorkerRegistry()
        val first = worker("shared-worker", "session-1")
        val second = worker("shared-worker", "session-2")
        val firstHeartbeat = Instant.fromEpochMilliseconds(10_000)

        assertTrue(
            registry.register(
                registration(first, firstHeartbeat),
                staleBefore = Instant.fromEpochMilliseconds(0),
            )
        )
        assertFalse(
            registry.register(
                registration(second, Instant.fromEpochMilliseconds(20_000)),
                staleBefore = Instant.fromEpochMilliseconds(5_000),
            )
        )
        assertEquals(first, registry.find(first.workerId)?.identity)
    }

    @Test
    fun `worker registry fences the old session after stale takeover`() = runBlocking {
        val registry = InMemoryConversationRuntimeWorkerRegistry()
        val first = worker("shared-worker", "session-1")
        val second = worker("shared-worker", "session-2")

        assertTrue(
            registry.register(
                registration(first, Instant.fromEpochMilliseconds(10_000)),
                staleBefore = Instant.fromEpochMilliseconds(0),
            )
        )
        assertTrue(
            registry.register(
                registration(second, Instant.fromEpochMilliseconds(40_000)),
                staleBefore = Instant.fromEpochMilliseconds(20_000),
            )
        )

        assertFalse(registry.heartbeat(first, Instant.fromEpochMilliseconds(41_000)))
        assertFalse(registry.unregister(first, Instant.fromEpochMilliseconds(41_000)))
        assertTrue(registry.heartbeat(second, Instant.fromEpochMilliseconds(42_000)))
        assertEquals(second, registry.find(second.workerId)?.identity)
        assertEquals(Instant.fromEpochMilliseconds(42_000), registry.find(second.workerId)?.lastHeartbeatAt)
    }

    @Test
    fun `coordinator never transfers a claimed task to another worker session`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val task = task("claimed-message", QueuedMessagePlacement.END_OF_TURN)
        val queued = task("queued-message", QueuedMessagePlacement.END_OF_TURN)
        val first = worker("shared-worker", "session-1")
        val second = worker("shared-worker", "session-2")

        assertTrue(coordinator.submit(task))
        assertEquals(task, coordinator.claimAsEligibleWorker(task, first))
        assertTrue(coordinator.submit(queued))

        assertNull(coordinator.claimAsEligibleWorker(task, second))
        assertTrue(coordinator.confirmActiveTaskOwner(conversationId, task.id, executor(first)))
        assertFalse(coordinator.confirmActiveTaskOwner(conversationId, task.id, executor(second)))
        assertEquals(executor(first), coordinator.listActiveTaskAssignments().single().executor)

        assertTrue(coordinator.markActiveTaskStarted(conversationId, task.id, executor(first), Clock.System.now()))
        val incident = coordinator.markActiveTaskInDoubt(
            conversationId = conversationId,
            taskId = task.id,
            executor = executor(first),
            message = "Worker session disappeared",
            errorType = "WorkerUnavailable",
        )
        assertEquals(task.id, incident?.task?.id)
        assertFalse(
            coordinator.completeActiveTask(
                conversationId,
                task.id,
                executor(first),
                ConversationRuntimeTaskOutcome.CompleteTurn,
            )
        )
        assertNull(coordinator.claimAsEligibleWorker(task, second))

        val snapshot = coordinator.snapshot(conversationId)
        assertEquals(ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN, snapshot.incidents.single().kind)
        assertTrue(snapshot.incidents.single().executionStartedAt != null)
        assertEquals(
            listOf(
                ConversationRuntimeTask.Payload.ExecutionIncident(task.id),
                queued.payload,
            ),
            snapshot.pendingTasks.map { it.payload },
        )
    }

    @Test
    fun `coordinator distinguishes delivery failure from unknown execution outcome`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val task = task("delivery-failed-message", QueuedMessagePlacement.END_OF_TURN)
        val worker = worker("worker-1")

        assertTrue(coordinator.submit(task))
        assertEquals(task, coordinator.claimAsEligibleWorker(task, worker))
        assertNull(coordinator.listActiveTaskAssignments().single().startedAt)
        assertFalse(
            coordinator.completeActiveTask(
                conversationId,
                task.id,
                executor(worker),
                ConversationRuntimeTaskOutcome.CompleteTurn,
            )
        )
        assertFailsWith<IllegalStateException> {
            coordinator.markActiveTaskInDoubt(
                conversationId = conversationId,
                taskId = task.id,
                executor = executor(worker),
                message = "Must not be unknown before execution starts",
            )
        }

        val incident = coordinator.recordClaimedTaskDeliveryFailure(
            conversationId = conversationId,
            taskId = task.id,
            executor = executor(worker),
            message = "Worker disappeared before execution",
        )

        assertEquals(ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED, incident?.kind)
        assertNull(incident?.executionStartedAt)
        assertNull(coordinator.snapshot(conversationId).activeTask)
        assertEquals(
            ConversationRuntimeTask.Payload.ExecutionIncident(task.id),
            coordinator.snapshot(conversationId).pendingTasks.single().payload,
        )
    }

    @Test
    fun `coordinator tracks control state`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val task = task("message-1", QueuedMessagePlacement.END_OF_TURN)

        assertTrue(coordinator.submit(task))
        assertEquals(task, coordinator.claimAsEligibleWorker(task, worker("worker-1")))

        assertTrue(coordinator.requestPause(conversationId))
        assertEquals(ConversationExecutionState.ControlState.PAUSE_REQUESTED, coordinator.find(conversationId)?.controlState)
        assertFalse(coordinator.markPaused(conversationId))
        assertTrue(
            coordinator.markActiveTaskStarted(
                conversationId = conversationId,
                taskId = task.id,
                executor = executor(worker("worker-1")),
                startedAt = Clock.System.now(),
            )
        )
        assertTrue(
            coordinator.completeActiveTask(
                conversationId = conversationId,
                taskId = task.id,
                executor = executor(worker("worker-1")),
                outcome = ConversationRuntimeTaskOutcome.CompleteTurn,
            )
        )
        assertEquals(ConversationExecutionState.ControlState.PAUSED, coordinator.find(conversationId)?.controlState)
        assertTrue(coordinator.requestResume(conversationId))
        assertEquals(ConversationExecutionState.ControlState.RUNNING, coordinator.find(conversationId)?.controlState)
        assertTrue(coordinator.requestStop(conversationId))
        assertNull(coordinator.find(conversationId))
    }

    @Test
    fun `coordinator attaches a stopped turn marker to the next user message once`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val first = task("message-1", QueuedMessagePlacement.END_OF_TURN)
        val firstWorker = worker("worker-1")

        assertTrue(coordinator.submit(first))
        assertEquals(first, coordinator.claimAsEligibleWorker(first, firstWorker))
        assertTrue(
            coordinator.markActiveTaskStarted(
                conversationId,
                first.id,
                executor(firstWorker),
                Clock.System.now(),
            )
        )
        assertTrue(coordinator.requestStop(conversationId))
        assertTrue(coordinator.requestStop(conversationId))
        assertTrue(
            coordinator.completeActiveTask(
                conversationId,
                first.id,
                executor(firstWorker),
                ConversationRuntimeTaskOutcome.CompleteTurn,
            )
        )
        assertTrue(coordinator.finishIfIdle(conversationId))

        val second = task("message-2", QueuedMessagePlacement.END_OF_TURN)
        assertTrue(coordinator.submit(second))
        val secondWorker = worker("worker-2")
        val claimedSecond = assertNotNull(coordinator.claimAsEligibleWorker(second, secondWorker))
        val stopInstruction = claimedSecond.requireUserTurn().userMessage.instructions
            .filterIsInstance<Conversation.Message.Instruction.PreviousTurnTerminated>()
            .single()

        assertEquals(first.turnId.value, stopInstruction.turnId)
        assertEquals(Conversation.TurnTerminationReason.STOPPED, stopInstruction.reason)
        assertTrue(stopInstruction.toXmlLine().contains("<turn_aborted reason=\"user_stopped\">"))

        assertTrue(
            coordinator.markActiveTaskStarted(
                conversationId,
                claimedSecond.id,
                executor(secondWorker),
                Clock.System.now(),
            )
        )
        assertTrue(
            coordinator.completeActiveTask(
                conversationId,
                claimedSecond.id,
                executor(secondWorker),
                ConversationRuntimeTaskOutcome.CompleteTurn,
            )
        )
        assertTrue(coordinator.finishIfIdle(conversationId))

        val third = task("message-3", QueuedMessagePlacement.END_OF_TURN)
        assertTrue(coordinator.submit(third))
        val claimedThird = assertNotNull(coordinator.claimAsEligibleWorker(third, worker("worker-3")))
        assertTrue(
            claimedThird.requireUserTurn().userMessage.instructions
                .none { it is Conversation.Message.Instruction.PreviousTurnTerminated }
        )
    }

    @Test
    fun `cancelling the next user message preserves its stopped turn marker`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val first = task("message-1", QueuedMessagePlacement.END_OF_TURN)
        val firstWorker = worker("worker-1")

        assertTrue(coordinator.submit(first))
        assertEquals(first, coordinator.claimAsEligibleWorker(first, firstWorker))
        assertTrue(
            coordinator.markActiveTaskStarted(
                conversationId,
                first.id,
                executor(firstWorker),
                Clock.System.now(),
            )
        )
        assertTrue(coordinator.requestStop(conversationId))
        assertTrue(
            coordinator.completeActiveTask(
                conversationId,
                first.id,
                executor(firstWorker),
                ConversationRuntimeTaskOutcome.CompleteTurn,
            )
        )
        assertTrue(coordinator.finishIfIdle(conversationId))

        val cancelled = task("message-2", QueuedMessagePlacement.END_OF_TURN)
        assertTrue(coordinator.submit(cancelled))
        assertTrue(coordinator.cancelByMessageId(conversationId, cancelled.requireUserTurn().userMessage.id))

        val replacement = task("message-3", QueuedMessagePlacement.END_OF_TURN)
        assertTrue(coordinator.submit(replacement))
        val claimedReplacement = assertNotNull(
            coordinator.claimAsEligibleWorker(replacement, worker("worker-2"))
        )
        val stopInstruction = claimedReplacement.requireUserTurn().userMessage.instructions
            .filterIsInstance<Conversation.Message.Instruction.PreviousTurnTerminated>()
            .single()

        assertEquals(first.turnId.value, stopInstruction.turnId)
        assertEquals(Conversation.TurnTerminationReason.STOPPED, stopInstruction.reason)
    }

    @Test
    fun `coordinator records tool execution and snapshots`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val task = task("message-1", QueuedMessagePlacement.END_OF_TURN)
        val execution = ConversationRuntimeToolExecution(
            toolCallId = Conversation.Message.ContentItem.ToolCall.Id("tool-call-1"),
            toolName = "read_file",
            status = ConversationRuntimeToolExecution.Status.RUNNING,
            runtimeTaskId = task.id,
            executor = executor(worker("worker-1")),
            startedAt = Clock.System.now(),
        )

        assertTrue(coordinator.submit(task))
        assertEquals(task, coordinator.claimAsEligibleWorker(task, worker("worker-1")))
        assertTrue(coordinator.upsertToolExecution(conversationId, execution))

        val snapshot = coordinator.snapshot(conversationId)
        assertEquals(task.id, snapshot.state?.activeTaskId)
        assertEquals(listOf(execution), snapshot.toolExecutions)
        assertTrue(snapshot.trace.isNotEmpty())
    }

    @Test
    fun `coordinator retains memory operation after conversation runtime is cleared`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val now = Clock.System.now()
        val operation = ConversationRuntimeMemoryOperation(
            runId = MemoryRun.Id("memory-run-1"),
            operation = "answer_question",
            status = MemoryRun.Status.RUNNING,
            summary = "Memory question answering running",
            updatedAt = now,
        )

        assertTrue(coordinator.upsertMemoryOperation(conversationId, operation))
        assertTrue(coordinator.submit(task("message-1", QueuedMessagePlacement.END_OF_TURN)))
        coordinator.abort(conversationId)

        assertEquals(listOf(operation), coordinator.snapshot(conversationId).memoryOperations)
    }

    @Test
    fun `coordinator retains command task after conversation runtime is cleared`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val now = Clock.System.now()
        val commandTask = CommandTask(
            id = CommandTask.Id("command-task-1"),
            conversationId = conversationId,
            workerId = ConversationRuntimeWorkerId("worker-1"),
            workspaceMountId = WorkspaceMount.Id("mount-1"),
            command = "sleep 30",
            workingDirectory = "/tmp",
            status = CommandTask.Status.WORKING,
            processId = 123,
            processStartedAt = now,
            outputFile = "/tmp/command-task-1.log",
            outputBytes = 0,
            createdAt = now,
            updatedAt = now,
        )

        assertTrue(coordinator.upsertCommandTask(commandTask).evictedTasks.isEmpty())
        val initialTraceSize = coordinator.snapshot(conversationId).trace.size
        val progressedTask = commandTask.copy(outputBytes = 64, updatedAt = Clock.System.now())
        assertTrue(coordinator.upsertCommandTask(progressedTask).evictedTasks.isEmpty())
        coordinator.abort(conversationId)

        val snapshot = coordinator.snapshot(conversationId)
        assertEquals(progressedTask, coordinator.findCommandTask(conversationId, commandTask.id))
        assertEquals(listOf(progressedTask), snapshot.commandTasks)
        assertEquals(initialTraceSize, snapshot.trace.count { it.kind == ConversationRuntimeTraceEntry.Kind.COMMAND_TASK })
    }

    @Test
    fun `command task retention never evicts working tasks`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val now = Clock.System.now()
        val workingTask = commandTask("working", CommandTask.Status.WORKING, now)
        assertTrue(coordinator.upsertCommandTask(workingTask).evictedTasks.isEmpty())
        var evictedTasks = emptyList<CommandTask>()
        repeat(101) { index ->
            evictedTasks = coordinator.upsertCommandTask(
                commandTask("completed-$index", CommandTask.Status.COMPLETED, now)
            ).evictedTasks
            if (index < 100) {
                assertTrue(evictedTasks.isEmpty())
            }
        }

        val tasks = coordinator.snapshot(conversationId).commandTasks
        assertTrue(workingTask in tasks)
        assertEquals(100, tasks.count { it.status == CommandTask.Status.COMPLETED })
        assertEquals(listOf(CommandTask.Id("completed-0")), evictedTasks.map { it.id })
        assertEquals(tasks, coordinator.findCommandTasks())
    }

    @Test
    fun `command task retention keeps terminal source of active monitor`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val source = commandTask(
            id = "source",
            status = CommandTask.Status.COMPLETED,
            createdAt = Instant.fromEpochMilliseconds(0),
        )
        coordinator.upsertCommandTask(source)
        coordinator.synchronizeCommandMonitor(
            commandMonitor(Instant.fromEpochMilliseconds(1)).copy(commandTaskId = source.id),
            emptyList(),
        )

        var evictedTasks = emptyList<CommandTask>()
        repeat(101) { index ->
            evictedTasks = coordinator.upsertCommandTask(
                commandTask(
                    id = "completed-$index",
                    status = CommandTask.Status.COMPLETED,
                    createdAt = Instant.fromEpochMilliseconds(index + 2L),
                )
            ).evictedTasks
        }

        assertNotNull(coordinator.findCommandTask(conversationId, source.id))
        assertEquals(listOf(CommandTask.Id("completed-0")), evictedTasks.map { it.id })
    }

    @Test
    fun `coordinator synchronizes command monitor events idempotently`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val now = Clock.System.now()
        val monitor = commandMonitor(now)
        val event = CommandMonitorEvent(
            id = CommandMonitorEvent.Id("${monitor.id.value}:12"),
            conversationId = conversationId,
            monitorId = monitor.id,
            outputStartByte = 0,
            outputEndByte = 12,
            output = "matched line",
            outputTruncatedBefore = false,
            occurredAt = now,
            deliveryRequested = true,
        )
        val progressed = monitor.copy(
            outputBytes = 12,
            eventOutputCursor = 12,
            eventCount = 1,
            lastEventAt = now,
            lastEventPreview = event.output,
        )

        assertTrue(coordinator.synchronizeCommandMonitor(progressed, listOf(event)).evictedMonitors.isEmpty())
        assertTrue(coordinator.synchronizeCommandMonitor(progressed, listOf(event)).evictedMonitors.isEmpty())

        assertEquals(listOf(progressed), coordinator.findCommandMonitors())
        assertEquals(listOf(progressed), coordinator.snapshot(conversationId).commandMonitors)
        assertEquals(listOf(event), coordinator.findCommandMonitorEvents(conversationId, progressed.id))
        assertTrue(coordinator.requestCommandMonitorCancellation(conversationId, progressed.id, now))
        assertTrue(coordinator.findCommandMonitor(conversationId, progressed.id)?.cancellationRequestedAt != null)
        assertTrue(
            coordinator.markCommandMonitorEventsDelivered(
                conversationId = conversationId,
                eventIds = setOf(event.id),
                deliveredAt = now,
            )
        )
        assertEquals(now, coordinator.findCommandMonitorEvents(conversationId, progressed.id).single().deliveredAt)
    }

    @Test
    fun `coordinator records sequenced event log entries`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val firstEvent = ConversationRuntimeEvent.ExecutionCompleted(conversationId)
        val secondEvent = ConversationRuntimeEvent.ExecutionFailed(
            conversationId = conversationId,
            message = "boom",
            failureType = "TestFailure",
        )

        val firstEntry = coordinator.recordEvent(firstEvent)
        val secondEntry = coordinator.recordEvent(secondEvent)

        assertEquals(1, firstEntry.sequence)
        assertEquals(2, secondEntry.sequence)
        assertEquals(listOf(secondEntry), coordinator.listEventLogEntries(conversationId, afterSequence = 1, limit = 10))
        assertEquals(2, coordinator.snapshot(conversationId).lastEventSequence)
    }

    private fun commandTask(
        id: String,
        status: CommandTask.Status,
        createdAt: Instant,
    ): CommandTask = CommandTask(
        id = CommandTask.Id(id),
        conversationId = conversationId,
        workerId = ConversationRuntimeWorkerId("worker-1"),
        workspaceMountId = WorkspaceMount.Id("mount-1"),
        command = id,
        workingDirectory = "/tmp",
        status = status,
        processId = null,
        processStartedAt = null,
        outputFile = "/tmp/$id.log",
        outputBytes = 0,
        createdAt = createdAt,
        updatedAt = createdAt,
        completedAt = createdAt.takeIf { status != CommandTask.Status.WORKING },
    )

    private fun commandMonitor(createdAt: Instant): CommandMonitor =
        CommandMonitor(
            id = CommandMonitor.Id("monitor-1"),
            conversationId = conversationId,
            commandTaskId = CommandTask.Id("command-task-1"),
            workerId = ConversationRuntimeWorkerId("worker-1"),
            workspaceMountId = WorkspaceMount.Id("mount-1"),
            filterCommand = "grep match",
            mode = CommandMonitor.Mode.CONTINUOUS,
            startFrom = CommandMonitor.StartFrom.BEGINNING,
            status = CommandMonitor.Status.WORKING,
            sourceOutputCursor = 0,
            processId = 456,
            processStartedAt = createdAt,
            outputFile = "/tmp/monitor-1.log",
            errorFile = "/tmp/monitor-1.stderr.log",
            outputBytes = 0,
            eventOutputCursor = 0,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

    private fun worker(
        id: String,
        sessionId: String = "$id-session",
    ): ConversationRuntimeWorkerIdentity =
        ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId(id),
            sessionId = ConversationRuntimeWorkerSessionId(sessionId),
        )

    private fun registration(
        identity: ConversationRuntimeWorkerIdentity,
        at: Instant,
    ): ConversationRuntimeWorkerRegistration =
        ConversationRuntimeWorkerRegistration(
            identity = identity,
            capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
            tools = emptyList(),
            environmentProfile = testWorkerEnvironmentProfile(at),
            version = "test",
            startedAt = at,
            lastHeartbeatAt = at,
        )

    private suspend fun InMemoryConversationRuntimeCoordinator.claimAsEligibleWorker(
        task: ConversationRuntimeTask,
        worker: ConversationRuntimeWorkerIdentity,
    ): ConversationRuntimeTask? =
        claimDeliveredTask(
            conversationId = task.conversationId,
            taskId = task.id,
            executor = executor(worker),
            executorCapabilities = task.requirements.capabilities,
            workerWorkspaceMountIds = (task.requirements.target as? ConversationRuntimeTaskTarget.Worker)
                ?.workspaceMountId
                ?.let(::setOf)
                .orEmpty(),
        )

    private fun executor(
        worker: ConversationRuntimeWorkerIdentity,
    ): ConversationRuntimeExecutorIdentity.Server =
        ConversationRuntimeExecutorIdentity.Server(
            ConversationRuntimeServerSessionId(worker.sessionId.value)
        )

    private fun task(
        messageId: String,
        placement: QueuedMessagePlacement,
    ): ConversationRuntimeTask {
        val message = Conversation.Message(
            id = Conversation.Message.Id(messageId),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Text $messageId")),
            createdAt = Clock.System.now(),
        )

        return ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id(messageId),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.UserTurn(
                userMessage = message,
                agentDefinitionId = agentDefinitionId,
            ),
            placement = placement,
            idempotencyKey = "test:$messageId",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
                target = ConversationRuntimeTaskTarget.Server,
            ),
            createdAt = Clock.System.now(),
        )
    }

    private fun llmTask(
        messageId: String,
        parentTask: ConversationRuntimeTask,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id(messageId),
            conversationId = conversationId,
            turnId = parentTask.turnId,
            parentTaskId = parentTask.id,
            payload = ConversationRuntimeTask.Payload.LlmCall(
                rootUserMessageId = Conversation.Message.Id("root-message"),
                agentDefinitionId = agentDefinitionId,
                iteration = 1,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "test:$messageId",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
                target = ConversationRuntimeTaskTarget.Server,
            ),
            createdAt = Clock.System.now(),
        )

    private fun memoryCompletionTask(runId: String): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("$runId:delivery"),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.MemoryRunCompletion(
                runId = MemoryRun.Id(runId),
                agentDefinitionId = agentDefinitionId,
                statusToolName = "memory_run_status",
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "test:$runId:delivery",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
                target = ConversationRuntimeTaskTarget.Server,
            ),
            createdAt = Clock.System.now(),
        )
}
