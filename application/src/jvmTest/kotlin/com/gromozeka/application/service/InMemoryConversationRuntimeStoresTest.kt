package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
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
            workerId = "publisher-1",
            now = Clock.System.now(),
            leaseUntil = Instant.fromEpochMilliseconds(10_000),
            limit = 10,
        )
        assertEquals(listOf(first.id), firstWork.map { it.item.taskId })
        assertTrue(coordinator.markWorkItemPublished(conversationId, firstWork.single().sequence, "publisher-1", Clock.System.now()))
        assertNull(coordinator.claimDeliveredTask(conversationId, second.id, "worker-1"))
        assertEquals(first, coordinator.claimDeliveredTask(conversationId, first.id, "worker-2"))
        assertTrue(coordinator.confirmActiveTaskOwner(conversationId, first.id, "worker-2"))
        assertFalse(coordinator.confirmActiveTaskOwner(conversationId, first.id, "worker-1"))

        assertTrue(coordinator.completeActiveTask(conversationId, first.id, "worker-2"))
        val secondWork = coordinator.claimUnpublishedWorkItems(
            workerId = "publisher-1",
            now = Instant.fromEpochMilliseconds(10_001),
            leaseUntil = Instant.fromEpochMilliseconds(20_000),
            limit = 10,
        )
        assertEquals(listOf(second.id), secondWork.map { it.item.taskId })
        assertEquals(second, coordinator.claimDeliveredTask(conversationId, second.id, "worker-3"))
    }

    @Test
    fun `coordinator releases published work while paused and republishes it after resume`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val task = task("message-1", QueuedMessagePlacement.END_OF_TURN)

        assertTrue(coordinator.submit(task))
        val work = coordinator.claimUnpublishedWorkItems(
            workerId = "publisher-1",
            now = Instant.fromEpochMilliseconds(1_000),
            leaseUntil = Instant.fromEpochMilliseconds(2_000),
            limit = 10,
        ).single()
        assertTrue(coordinator.markWorkItemPublished(conversationId, work.sequence, "publisher-1", Clock.System.now()))
        assertTrue(coordinator.releasePublishedWorkItem(conversationId, task.id))

        assertEquals(
            listOf(task.id),
            coordinator.claimUnpublishedWorkItems(
                workerId = "publisher-2",
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
        assertEquals(active, coordinator.claimDeliveredTask(conversationId, active.id, "worker-1"))
        assertTrue(coordinator.submit(steering))

        assertEquals(
            listOf(steering.id),
            coordinator.takeActiveInsertions(
                conversationId,
                active.id,
                "worker-1",
                QueuedMessagePlacement.AFTER_TOOL_RESULT,
            ).map { it.id },
        )
        assertNull(coordinator.claimDeliveredTask(conversationId, steering.id, "worker-2"))
    }

    @Test
    fun `coordinator promotes missed active insertions before ordinary queued turns`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val active = task("active-message", QueuedMessagePlacement.END_OF_TURN)
        val queued = task("queued-message", QueuedMessagePlacement.END_OF_TURN)
        val steering = task("steering-message", QueuedMessagePlacement.AFTER_TOOL_RESULT)

        assertTrue(coordinator.submit(active))
        assertEquals(active, coordinator.claimDeliveredTask(conversationId, active.id, "worker-1"))
        assertTrue(coordinator.submit(queued))
        assertTrue(coordinator.submit(steering))

        assertTrue(coordinator.completeActiveTask(conversationId, active.id, "worker-1"))

        val promoted = coordinator.claimDeliveredTask(conversationId, steering.id, "worker-2")
        assertEquals(steering.id, promoted?.id)
        assertEquals(QueuedMessagePlacement.END_OF_TURN, promoted?.placement)
        assertTrue(coordinator.completeActiveTask(conversationId, promoted!!.id, "worker-2"))

        assertEquals(queued.id, coordinator.claimDeliveredTask(conversationId, queued.id, "worker-3")?.id)
    }

    @Test
    fun `coordinator gates claim by worker requirements`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val llmTask = task("llm-message", QueuedMessagePlacement.END_OF_TURN).copy(
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(ConversationRuntimeWorkerCapability.LLM_RUNTIME),
            )
        )

        assertTrue(coordinator.submit(llmTask))
        assertNull(
            coordinator.claimDeliveredTask(
                conversationId = conversationId,
                taskId = llmTask.id,
                workerId = "turn-worker",
                workerCapabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
            )
        )
        assertEquals(
            llmTask,
            coordinator.claimDeliveredTask(
                conversationId = conversationId,
                taskId = llmTask.id,
                workerId = "llm-worker",
                workerCapabilities = setOf(ConversationRuntimeWorkerCapability.LLM_RUNTIME),
            )
        )
    }

    @Test
    fun `coordinator tracks control state`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val task = task("message-1", QueuedMessagePlacement.END_OF_TURN)

        assertTrue(coordinator.submit(task))
        assertEquals(task, coordinator.claimDeliveredTask(conversationId, task.id, "worker-1"))

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
            workerId = "worker-1",
            startedAt = Clock.System.now(),
        )

        assertTrue(coordinator.submit(task))
        assertEquals(task, coordinator.claimDeliveredTask(conversationId, task.id, "worker-1"))
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

        assertTrue(coordinator.upsertCommandTask(commandTask))
        val initialTraceSize = coordinator.snapshot(conversationId).trace.size
        val progressedTask = commandTask.copy(outputBytes = 64, updatedAt = Clock.System.now())
        assertTrue(coordinator.upsertCommandTask(progressedTask))
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
        assertTrue(coordinator.upsertCommandTask(workingTask))
        repeat(101) { index ->
            assertTrue(
                coordinator.upsertCommandTask(
                    commandTask("completed-$index", CommandTask.Status.COMPLETED, now)
                )
            )
        }

        val tasks = coordinator.snapshot(conversationId).commandTasks
        assertTrue(workingTask in tasks)
        assertEquals(100, tasks.count { it.status == CommandTask.Status.COMPLETED })
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
            workerId = "worker-1",
            now = now,
            leaseUntil = Instant.fromEpochMilliseconds(2_000),
            limit = 10,
        )
        assertEquals(listOf(entry.sequence), firstLease.map { it.sequence })
        assertEquals(
            emptyList(),
            coordinator.claimUnpublishedEventLogEntries(
                workerId = "worker-2",
                now = Instant.fromEpochMilliseconds(1_500),
                leaseUntil = Instant.fromEpochMilliseconds(2_500),
                limit = 10,
            )
        )
        assertEquals(
            listOf(entry.sequence),
            coordinator.claimUnpublishedEventLogEntries(
                workerId = "worker-2",
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
        delivery.ack()
    }

    private fun commandTask(
        id: String,
        status: CommandTask.Status,
        createdAt: Instant,
    ): CommandTask = CommandTask(
        id = CommandTask.Id(id),
        conversationId = conversationId,
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
            createdAt = Clock.System.now(),
        )
    }
}
