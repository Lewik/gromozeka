package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandMonitorEvent
import com.gromozeka.domain.service.CommandMonitorLifecycleEvent
import com.gromozeka.domain.service.CommandMonitorLifecycleEventPublisher
import com.gromozeka.domain.service.CommandMonitorLifecycleEventStream
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskLifecycleEvent
import com.gromozeka.domain.service.CommandTaskLifecycleEventPublisher
import com.gromozeka.domain.service.CommandTaskLifecycleEventStream
import com.gromozeka.domain.service.ArtifactReferenceValidator
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackgroundActivityLifecycleApplicationServiceTest {
    private val conversationId = Conversation.Id("conversation-1")
    private val agentDefinitionId = AgentDefinition.Id("agent-1")

    @Test
    fun `command and monitor events coalesce into one durable delivery task`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val commandEvents = TestCommandTaskLifecycleEventBus()
        val monitorEvents = TestCommandMonitorLifecycleEventBus()
        val service = service(scope, coordinator, commandEvents, monitorEvents)
        val command = terminalCommand("command-1", Instant.fromEpochMilliseconds(1_000))
        val monitor = commandMonitor("monitor-1", agentDefinitionId, Instant.fromEpochMilliseconds(2_000))
        val event = monitorEvent(monitor, deliveryRequested = true)

        try {
            coordinator.upsertCommandTask(command)
            coordinator.synchronizeCommandMonitor(monitor, listOf(event))
            service.start()
            commandEvents.publish(command.lifecycleEvent())
            monitorEvents.publish(monitor.lifecycleEvent(CommandMonitorLifecycleEvent.Kind.EVENTS_AVAILABLE))

            awaitCondition {
                coordinator.listPending(conversationId).size == 1
            }
            val payload = assertIs<ConversationRuntimeTask.Payload.BackgroundActivityCompletion>(
                coordinator.listPending(conversationId).single().payload
            )
            assertEquals("command-task:${command.id.value}", payload.sourceKey)

            commandEvents.publish(command.lifecycleEvent())
            monitorEvents.publish(monitor.lifecycleEvent(CommandMonitorLifecycleEvent.Kind.EVENTS_AVAILABLE))
            delay(400)
            assertEquals(1, coordinator.listPending(conversationId).size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `startup reconciliation recovers monitor delivery without lifecycle event`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val service = service(
            scope,
            coordinator,
            TestCommandTaskLifecycleEventBus(),
            TestCommandMonitorLifecycleEventBus(),
        )
        val monitor = commandMonitor("monitor-1", agentDefinitionId, Instant.fromEpochMilliseconds(1_000))
        val event = monitorEvent(monitor, deliveryRequested = true)
        try {
            coordinator.synchronizeCommandMonitor(monitor, listOf(event))

            service.reconcileAll()

            val payload = assertIs<ConversationRuntimeTask.Payload.BackgroundActivityCompletion>(
                coordinator.listPending(conversationId).single().payload
            )
            assertEquals("command-monitor-event:${event.id.value}", payload.sourceKey)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `external monitor events do not schedule conversation delivery`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val service = service(
            scope,
            coordinator,
            TestCommandTaskLifecycleEventBus(),
            TestCommandMonitorLifecycleEventBus(),
        )
        val monitor = commandMonitor("monitor-1", null, Instant.fromEpochMilliseconds(1_000))
        try {
            coordinator.synchronizeCommandMonitor(
                monitor,
                listOf(monitorEvent(monitor, deliveryRequested = false)),
            )

            service.reconcileAll()

            assertTrue(coordinator.listPending(conversationId).isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private fun service(
        scope: CoroutineScope,
        coordinator: InMemoryConversationRuntimeCoordinator,
        commandEvents: TestCommandTaskLifecycleEventBus,
        monitorEvents: TestCommandMonitorLifecycleEventBus,
    ): BackgroundActivityLifecycleApplicationService {
        val dispatcher = ConversationRuntimeDispatcher(
            runtimeCoordinator = coordinator,
            runtimeEventBus = InMemoryConversationRuntimeEventBus(),
            artifactReferenceValidator = ArtifactReferenceValidator { _, _ -> },
        )
        return BackgroundActivityLifecycleApplicationService(
            commandEventStream = commandEvents,
            monitorEventStream = monitorEvents,
            runtimeCoordinator = coordinator,
            runtimeDispatcher = dispatcher,
            coroutineScope = scope,
        )
    }

    private fun terminalCommand(
        id: String,
        at: Instant,
    ): CommandTask =
        CommandTask(
            id = CommandTask.Id(id),
            conversationId = conversationId,
            workerId = ConversationRuntimeWorkerId("worker-1"),
            workspaceMountId = WorkspaceMount.Id("mount-1"),
            agentDefinitionId = agentDefinitionId,
            command = "echo $id",
            workingDirectory = "/tmp",
            status = CommandTask.Status.COMPLETED,
            processId = 1,
            processStartedAt = at,
            outputFile = "/tmp/$id.log",
            outputBytes = 0,
            exitCode = 0,
            createdAt = at,
            updatedAt = at,
            completedAt = at,
            completionNotificationRequestedAt = at,
            terminalOutputStartByte = 0,
            terminalOutput = "",
        )

    private fun commandMonitor(
        id: String,
        agentId: AgentDefinition.Id?,
        at: Instant,
    ): CommandMonitor =
        CommandMonitor(
            id = CommandMonitor.Id(id),
            conversationId = conversationId,
            commandTaskId = CommandTask.Id("source-$id"),
            workerId = ConversationRuntimeWorkerId("worker-1"),
            workspaceMountId = WorkspaceMount.Id("mount-1"),
            agentDefinitionId = agentId,
            filterCommand = "grep match",
            mode = CommandMonitor.Mode.CONTINUOUS,
            startFrom = CommandMonitor.StartFrom.NOW,
            status = CommandMonitor.Status.WORKING,
            sourceOutputCursor = 0,
            processId = 2,
            processStartedAt = at,
            outputFile = "/tmp/$id.log",
            errorFile = "/tmp/$id.err",
            outputBytes = 6,
            eventOutputCursor = 6,
            eventCount = 1,
            createdAt = at,
            updatedAt = at,
            terminalNotificationRequestedAt = at.takeIf { agentId != null },
        )

    private fun monitorEvent(
        monitor: CommandMonitor,
        deliveryRequested: Boolean,
    ): CommandMonitorEvent =
        CommandMonitorEvent(
            id = CommandMonitorEvent.Id("${monitor.id.value}:6"),
            conversationId = conversationId,
            monitorId = monitor.id,
            outputStartByte = 0,
            outputEndByte = 6,
            output = "match",
            outputTruncatedBefore = false,
            occurredAt = monitor.createdAt,
            deliveryRequested = deliveryRequested,
        )

    private fun CommandTask.lifecycleEvent(): CommandTaskLifecycleEvent =
        CommandTaskLifecycleEvent(
            conversationId = conversationId,
            taskId = id,
            status = status,
            occurredAt = requireNotNull(completedAt),
        )

    private fun CommandMonitor.lifecycleEvent(
        kind: CommandMonitorLifecycleEvent.Kind,
    ): CommandMonitorLifecycleEvent =
        CommandMonitorLifecycleEvent(
            conversationId = conversationId,
            monitorId = id,
            kind = kind,
            occurredAt = updatedAt,
        )

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) {
                delay(10)
            }
        }
    }

    private class TestCommandTaskLifecycleEventBus :
        CommandTaskLifecycleEventPublisher,
        CommandTaskLifecycleEventStream {
        private val channel = Channel<CommandTaskLifecycleEvent>(Channel.UNLIMITED)

        override val events: Flow<CommandTaskLifecycleEvent> = channel.receiveAsFlow()

        override suspend fun publish(event: CommandTaskLifecycleEvent) {
            channel.send(event)
        }
    }

    private class TestCommandMonitorLifecycleEventBus :
        CommandMonitorLifecycleEventPublisher,
        CommandMonitorLifecycleEventStream {
        private val channel = Channel<CommandMonitorLifecycleEvent>(Channel.UNLIMITED)

        override val events: Flow<CommandMonitorLifecycleEvent> = channel.receiveAsFlow()

        override suspend fun publish(event: CommandMonitorLifecycleEvent) {
            channel.send(event)
        }
    }
}
