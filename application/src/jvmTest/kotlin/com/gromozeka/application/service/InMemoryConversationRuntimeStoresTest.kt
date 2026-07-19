package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskIncident
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryConversationRuntimeStoresTest {
    private val conversationId = Conversation.Id("conversation-1")
    private val agent = AgentDefinition(
        id = AgentDefinition.Id("agent-1"),
        name = "Test Agent",
        prompts = listOf(Prompt.Id("prompt-1")),
        runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model-1")),
        type = AgentDefinition.Type.Inline,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    @Test
    fun `coordinator claims only head delivered task pointer`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val first = task("message-1", QueuedMessagePlacement.END_OF_TURN)
        val second = task("message-2", QueuedMessagePlacement.END_OF_TURN)

        assertTrue(coordinator.submit(first))
        assertTrue(coordinator.submit(second))

        val firstWork = coordinator.claimUnpublishedWorkItems(
            leaseOwnerId = "publisher-1",
            now = Clock.System.now(),
            leaseUntil = Instant.fromEpochMilliseconds(10_000),
            limit = 10,
        )
        assertEquals(listOf(first.id), firstWork.map { it.item.taskId })
        assertTrue(coordinator.markWorkItemPublished(conversationId, firstWork.single().sequence, "publisher-1", Clock.System.now()))
        assertNull(coordinator.claimAsEligibleWorker(second, worker("worker-1")))
        assertEquals(first, coordinator.claimAsEligibleWorker(first, worker("worker-2")))
        assertTrue(coordinator.confirmActiveTaskOwner(conversationId, first.id, worker("worker-2")))
        assertFalse(coordinator.confirmActiveTaskOwner(conversationId, first.id, worker("worker-1")))

        assertTrue(
            coordinator.markActiveTaskStarted(
                conversationId,
                first.id,
                worker("worker-2"),
                Clock.System.now(),
            )
        )
        assertTrue(coordinator.completeActiveTask(conversationId, first.id, worker("worker-2")))
        val secondWork = coordinator.claimUnpublishedWorkItems(
            leaseOwnerId = "publisher-1",
            now = Instant.fromEpochMilliseconds(10_001),
            leaseUntil = Instant.fromEpochMilliseconds(20_000),
            limit = 10,
        )
        assertEquals(listOf(second.id), secondWork.map { it.item.taskId })
        assertEquals(second, coordinator.claimAsEligibleWorker(second, worker("worker-3")))
    }

    @Test
    fun `coordinator releases published work while paused and republishes it after resume`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val task = task("message-1", QueuedMessagePlacement.END_OF_TURN)

        assertTrue(coordinator.submit(task))
        val work = coordinator.claimUnpublishedWorkItems(
            leaseOwnerId = "publisher-1",
            now = Instant.fromEpochMilliseconds(1_000),
            leaseUntil = Instant.fromEpochMilliseconds(2_000),
            limit = 10,
        ).single()
        assertTrue(coordinator.markWorkItemPublished(conversationId, work.sequence, "publisher-1", Clock.System.now()))
        assertTrue(coordinator.releasePublishedWorkItem(conversationId, task.id))

        assertEquals(
            listOf(task.id),
            coordinator.claimUnpublishedWorkItems(
                leaseOwnerId = "publisher-2",
                now = Instant.fromEpochMilliseconds(2_001),
                leaseUntil = Instant.fromEpochMilliseconds(3_000),
                limit = 10,
            ).map { it.item.taskId },
        )
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
            coordinator.takeActiveInsertions(
                conversationId,
                active.id,
                worker("worker-1"),
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
                worker("worker-1"),
                Clock.System.now(),
            )
        )
        assertTrue(coordinator.completeActiveTask(conversationId, active.id, worker("worker-1")))

        val promoted = coordinator.claimAsEligibleWorker(steering, worker("worker-2"))
        assertEquals(steering.id, promoted?.id)
        assertEquals(QueuedMessagePlacement.END_OF_TURN, promoted?.placement)
        assertTrue(
            coordinator.markActiveTaskStarted(
                conversationId,
                promoted!!.id,
                worker("worker-2"),
                Clock.System.now(),
            )
        )
        assertTrue(coordinator.completeActiveTask(conversationId, promoted.id, worker("worker-2")))

        assertEquals(
            queued.id,
            coordinator.claimAsEligibleWorker(queued, worker("worker-3"))?.id,
        )
    }

    @Test
    fun `coordinator gates claim by worker requirements`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val llmTask = llmTask("llm-message")

        assertTrue(coordinator.submit(llmTask))
        assertNull(
            coordinator.claimDeliveredTask(
                conversationId = conversationId,
                taskId = llmTask.id,
                worker = worker("turn-worker"),
                workerCapabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
                workerWorkspaceIds = emptySet(),
            )
        )
        assertEquals(
            llmTask,
            coordinator.claimDeliveredTask(
                conversationId = conversationId,
                taskId = llmTask.id,
                worker = worker("llm-worker"),
                workerCapabilities = setOf(
                    ConversationRuntimeWorkerCapability.LLM_RUNTIME,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
                workerWorkspaceIds = emptySet(),
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
        assertTrue(coordinator.confirmActiveTaskOwner(conversationId, task.id, first))
        assertFalse(coordinator.confirmActiveTaskOwner(conversationId, task.id, second))
        assertEquals(first, coordinator.listActiveTaskAssignments().single().worker)

        assertTrue(coordinator.markActiveTaskStarted(conversationId, task.id, first, Clock.System.now()))
        val incident = coordinator.markActiveTaskInDoubt(
            conversationId = conversationId,
            taskId = task.id,
            worker = first,
            message = "Worker session disappeared",
            errorType = "WorkerUnavailable",
        )
        assertEquals(task.id, incident?.task?.id)
        assertFalse(coordinator.completeActiveTask(conversationId, task.id, first))
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
        assertFalse(coordinator.completeActiveTask(conversationId, task.id, worker))
        assertFailsWith<IllegalStateException> {
            coordinator.markActiveTaskInDoubt(
                conversationId = conversationId,
                taskId = task.id,
                worker = worker,
                message = "Must not be unknown before execution starts",
            )
        }

        val incident = coordinator.recordClaimedTaskDeliveryFailure(
            conversationId = conversationId,
            taskId = task.id,
            worker = worker,
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
        assertTrue(coordinator.markPaused(conversationId))
        assertEquals(ConversationExecutionState.ControlState.PAUSED, coordinator.find(conversationId)?.controlState)
        assertTrue(coordinator.requestResume(conversationId))
        assertEquals(ConversationExecutionState.ControlState.RUNNING, coordinator.find(conversationId)?.controlState)
        assertTrue(coordinator.requestStop(conversationId))
        assertEquals(ConversationExecutionState.ControlState.STOPPING, coordinator.find(conversationId)?.controlState)
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
            worker = worker("worker-1"),
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
    fun `coordinator retains command task after conversation runtime is cleared`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val now = Clock.System.now()
        val commandTask = CommandTask(
            id = CommandTask.Id("command-task-1"),
            conversationId = conversationId,
            workerId = ConversationRuntimeWorkerId("worker-1"),
            workspaceId = Workspace.Id("workspace-1"),
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

    @Test
    fun `coordinator leases unpublished event log entries until they are marked published`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val event = ConversationRuntimeEvent.ExecutionCompleted(conversationId)
        val entry = coordinator.recordEvent(event)
        val now = Instant.fromEpochMilliseconds(1_000)

        val firstLease = coordinator.claimUnpublishedEventLogEntries(
            leaseOwnerId = "worker-1",
            now = now,
            leaseUntil = Instant.fromEpochMilliseconds(2_000),
            limit = 10,
        )
        assertEquals(listOf(entry.sequence), firstLease.map { it.sequence })
        assertEquals(
            emptyList(),
            coordinator.claimUnpublishedEventLogEntries(
                leaseOwnerId = "worker-2",
                now = Instant.fromEpochMilliseconds(1_500),
                leaseUntil = Instant.fromEpochMilliseconds(2_500),
                limit = 10,
            )
        )
        assertEquals(
            listOf(entry.sequence),
            coordinator.claimUnpublishedEventLogEntries(
                leaseOwnerId = "worker-2",
                now = Instant.fromEpochMilliseconds(2_001),
                leaseUntil = Instant.fromEpochMilliseconds(3_000),
                limit = 10,
            ).map { it.sequence },
        )
        assertFalse(coordinator.markEventLogEntryPublished(conversationId, entry.sequence, "worker-1", Clock.System.now()))
        assertTrue(coordinator.markEventLogEntryPublished(conversationId, entry.sequence, "worker-2", Clock.System.now()))
    }

    @Test
    fun `work queue delivers runtime work items`() = runBlocking {
        val queue = InMemoryConversationRuntimeWorkQueue()
        val item = ConversationRuntimeWorkItem(
            conversationId = conversationId,
            reason = ConversationRuntimeWorkItem.Reason.TASK_SUBMITTED,
            taskId = ConversationRuntimeTask.Id("task-1"),
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
            ),
            createdAt = Clock.System.now(),
        )

        queue.submit(item)

        val delivery = withTimeout(1_000) { queue.deliveries.first() }
        assertEquals(item, delivery.item)
        delivery.acknowledge()
    }

    private fun commandTask(
        id: String,
        status: CommandTask.Status,
        createdAt: Instant,
    ): CommandTask = CommandTask(
        id = CommandTask.Id(id),
        conversationId = conversationId,
        workerId = ConversationRuntimeWorkerId("worker-1"),
        workspaceId = Workspace.Id("workspace-1"),
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
            capabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
            tools = emptyList(),
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
            worker = worker,
            workerCapabilities = task.requirements.capabilities,
            workerWorkspaceIds = task.requirements.target?.workspaceId?.let(::setOf).orEmpty(),
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
                agent = agent,
            ),
            placement = placement,
            idempotencyKey = "test:$messageId",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
            ),
            createdAt = Clock.System.now(),
        )
    }

    private fun llmTask(messageId: String): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id(messageId),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.LlmCall(
                rootUserMessageId = Conversation.Message.Id("root-message"),
                agent = agent,
                iteration = 1,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "test:$messageId",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.LLM_RUNTIME,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
            ),
            createdAt = Clock.System.now(),
        )
}
