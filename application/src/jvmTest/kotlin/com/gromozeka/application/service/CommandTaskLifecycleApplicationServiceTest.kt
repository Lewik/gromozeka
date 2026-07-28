package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.CommandTaskLifecycleEvent
import com.gromozeka.domain.service.CommandTaskLifecycleEventConsumer
import com.gromozeka.domain.service.CommandTaskLifecycleEventDelivery
import com.gromozeka.domain.service.CommandTaskLifecycleEventPublisher
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkPublisher
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
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CommandTaskLifecycleApplicationServiceTest {
    @Test
    fun `terminal command events schedule durable completion tasks exactly once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val lifecycleEvents = TestCommandTaskLifecycleEventBus()
        val dispatcher = ConversationRuntimeDispatcher(
            runtimeCoordinator = coordinator,
            runtimeEventBus = InMemoryConversationRuntimeEventBus(),
            runtimeWorkPublisher = NoOpRuntimeWorkPublisher,
            runtimeWorkerRegistry = InMemoryConversationRuntimeWorkerRegistry(),
            coroutineScope = scope,
        )
        val service = CommandTaskLifecycleApplicationService(
            eventConsumer = lifecycleEvents,
            runtimeCoordinator = coordinator,
            runtimeDispatcher = dispatcher,
            coroutineScope = scope,
        )
        val conversationId = Conversation.Id("conversation-1")
        val agentDefinitionId = AgentDefinition.Id("agent-1")
        val first = terminalCommand("command-1", conversationId, agentDefinitionId)
        val second = terminalCommand("command-2", conversationId, agentDefinitionId)

        try {
            coordinator.upsertCommandTask(first)
            coordinator.upsertCommandTask(second)
            service.start()
            lifecycleEvents.publish(first.lifecycleEvent())
            lifecycleEvents.publish(second.lifecycleEvent())

            awaitCondition {
                coordinator.listPending(conversationId).size == 2
            }

            val payloads = coordinator.listPending(conversationId)
                .map { assertIs<ConversationRuntimeTask.Payload.CommandTaskCompletion>(it.payload) }
            assertEquals(setOf(first.id, second.id), payloads.map { it.sourceTaskId }.toSet())

            lifecycleEvents.publish(first.lifecycleEvent())
            lifecycleEvents.publish(second.lifecycleEvent())
            delay(400)
            assertEquals(2, coordinator.listPending(conversationId).size)
        } finally {
            scope.cancel()
        }
    }

    private fun terminalCommand(
        id: String,
        conversationId: Conversation.Id,
        agentDefinitionId: AgentDefinition.Id,
    ): CommandTask {
        val now = Clock.System.now()
        return CommandTask(
            id = CommandTask.Id(id),
            conversationId = conversationId,
            workerId = ConversationRuntimeWorkerId("worker-1"),
            workspaceMountId = WorkspaceMount.Id("mount-1"),
            agentDefinitionId = agentDefinitionId,
            command = "echo $id",
            workingDirectory = "/tmp",
            status = CommandTask.Status.COMPLETED,
            processId = 1,
            processStartedAt = now,
            outputFile = "/tmp/$id.log",
            outputBytes = 0,
            exitCode = 0,
            createdAt = now,
            updatedAt = now,
            completedAt = now,
            completionNotificationRequestedAt = now,
            terminalOutputStartByte = 0,
            terminalOutput = "",
        )
    }

    private fun CommandTask.lifecycleEvent(): CommandTaskLifecycleEvent =
        CommandTaskLifecycleEvent(
            conversationId = conversationId,
            taskId = id,
            status = status,
            occurredAt = requireNotNull(completedAt),
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
        CommandTaskLifecycleEventConsumer {
        private val channel = Channel<CommandTaskLifecycleEventDelivery>(Channel.UNLIMITED)

        override val deliveries: Flow<CommandTaskLifecycleEventDelivery> = channel.receiveAsFlow()

        override suspend fun publish(event: CommandTaskLifecycleEvent) {
            channel.send(
                object : CommandTaskLifecycleEventDelivery {
                    override val event = event

                    override suspend fun acknowledge() = Unit

                    override suspend fun redeliver() = Unit

                    override suspend fun reject() = Unit
                }
            )
        }
    }

    private object NoOpRuntimeWorkPublisher : ConversationRuntimeWorkPublisher {
        override suspend fun submit(item: ConversationRuntimeWorkItem) = Unit
    }
}
