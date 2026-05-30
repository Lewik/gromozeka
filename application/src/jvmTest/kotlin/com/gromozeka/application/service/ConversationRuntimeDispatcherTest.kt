package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskFailure
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeWorkDelivery
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkQueue
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.QueuedMessagePlacement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationRuntimeDispatcherTest {
    private val conversationId = Conversation.Id("conversation-runtime-dispatcher-test")
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
    fun `dispatcher stop clears queued turns and finishes after active task completes`() = runBlocking {
        val harness = dispatcherHarness()
        try {
            val firstMessage = userMessage("message-1")
            val secondMessage = userMessage("message-2")

            assertTrue(harness.dispatcher.submitMessage(conversationId, firstMessage, agent))
            assertEquals(firstMessage.id.value, harness.runner.awaitStarted().id.value)

            assertTrue(
                harness.dispatcher.enqueueMessage(
                    conversationId = conversationId,
                    userMessage = secondMessage,
                    agent = agent,
                    placement = QueuedMessagePlacement.END_OF_TURN,
                )
            )
            assertEquals(listOf(secondMessage.id.value), harness.coordinator.listPending(conversationId).map { it.id.value })

            assertTrue(harness.dispatcher.controlExecution(conversationId, ConversationRuntimeControlAction.STOP))
            assertEquals(emptyList(), harness.coordinator.listPending(conversationId))

            harness.runner.releaseCurrentTask()
            waitUntil { harness.coordinator.find(conversationId) == null }

            assertNull(harness.coordinator.find(conversationId))
            assertEquals(emptyList(), harness.coordinator.listPending(conversationId))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `dispatcher pause waits before claiming the next queued turn until resume`() = runBlocking {
        val harness = dispatcherHarness()
        try {
            val firstMessage = userMessage("message-1")
            val secondMessage = userMessage("message-2")

            assertTrue(harness.dispatcher.submitMessage(conversationId, firstMessage, agent))
            assertEquals(firstMessage.id.value, harness.runner.awaitStarted().id.value)
            assertTrue(
                harness.dispatcher.enqueueMessage(
                    conversationId = conversationId,
                    userMessage = secondMessage,
                    agent = agent,
                    placement = QueuedMessagePlacement.END_OF_TURN,
                )
            )
            assertTrue(harness.dispatcher.controlExecution(conversationId, ConversationRuntimeControlAction.PAUSE))

            harness.runner.releaseCurrentTask()
            waitUntil { harness.coordinator.find(conversationId)?.controlState?.name == "PAUSED" }
            assertNull(withTimeoutOrNull(350) { harness.runner.awaitStarted() })

            assertTrue(harness.dispatcher.controlExecution(conversationId, ConversationRuntimeControlAction.RESUME))
            assertEquals(secondMessage.id.value, harness.runner.awaitStarted().id.value)

            harness.runner.releaseCurrentTask()
            waitUntil { harness.coordinator.find(conversationId) == null }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `dispatcher interrupt cancels active task and drops queued turns`() = runBlocking {
        val harness = dispatcherHarness()
        try {
            val firstMessage = userMessage("message-1")
            val secondMessage = userMessage("message-2")

            assertTrue(harness.dispatcher.submitMessage(conversationId, firstMessage, agent))
            assertEquals(firstMessage.id.value, harness.runner.awaitStarted().id.value)
            assertTrue(
                harness.dispatcher.enqueueMessage(
                    conversationId = conversationId,
                    userMessage = secondMessage,
                    agent = agent,
                    placement = QueuedMessagePlacement.END_OF_TURN,
                )
            )

            assertTrue(harness.dispatcher.controlExecution(conversationId, ConversationRuntimeControlAction.INTERRUPT))

            waitUntil { harness.coordinator.find(conversationId) == null }
            assertEquals(emptyList(), harness.coordinator.listPending(conversationId))
            assertNull(withTimeoutOrNull(350) { harness.runner.awaitStarted() })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `dispatcher observes interrupt requested by another runtime node`() = runBlocking {
        val harness = dispatcherHarness()
        try {
            val firstMessage = userMessage("message-1")
            val secondMessage = userMessage("message-2")

            assertTrue(harness.dispatcher.submitMessage(conversationId, firstMessage, agent))
            assertEquals(firstMessage.id.value, harness.runner.awaitStarted().id.value)
            assertTrue(
                harness.dispatcher.enqueueMessage(
                    conversationId = conversationId,
                    userMessage = secondMessage,
                    agent = agent,
                    placement = QueuedMessagePlacement.END_OF_TURN,
                )
            )

            assertTrue(harness.coordinator.requestInterrupt(conversationId))

            waitUntil { harness.coordinator.find(conversationId) == null }
            assertEquals(emptyList(), harness.coordinator.listPending(conversationId))
            assertNull(withTimeoutOrNull(350) { harness.runner.awaitStarted() })
        } finally {
            harness.close()
        }
    }

    @Test
    fun `dispatcher records final delivery failure before acknowledging dead letter`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val workQueue = FinalRetryRuntimeWorkQueue()
        val harness = dispatcherHarness(
            coordinator = coordinator,
            workQueue = workQueue,
            workerCapabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
        )
        try {
            val message = userMessage("message-1")
            val llmTask = runtimeLlmTask(message.id, iteration = 1)

            assertTrue(coordinator.submit(llmTask))
            workQueue.submit(runtimeWorkItem(llmTask))
            workQueue.awaitFailed()

            val snapshot = coordinator.snapshot(conversationId)
            assertEquals(1, snapshot.failedTasks.size)
            assertEquals(llmTask.id, snapshot.failedTasks.single().task.id)
            assertEquals(ConversationRuntimeTaskFailure.Reason.DELIVERY_FAILED, snapshot.failedTasks.single().reason)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `dispatcher replays durable runtime events for reconnecting clients`() = runBlocking {
        val harness = dispatcherHarness()
        try {
            val message = userMessage("message-1")

            assertTrue(harness.dispatcher.submitMessage(conversationId, message, agent))
            assertEquals(message.id.value, harness.runner.awaitStarted().id.value)
            harness.runner.releaseCurrentTask()
            waitUntil { harness.coordinator.find(conversationId) == null }

            val replayedEvents = withTimeout(2_000) {
                harness.dispatcher.observeConversation(conversationId, afterEventSequence = null)
                    .take(3)
                    .toList()
            }

            assertTrue(replayedEvents[0] is ConversationRuntimeEvent.MessageEmitted)
            assertTrue(replayedEvents[1] is ConversationRuntimeEvent.ExecutionCompleted)
            assertTrue(replayedEvents[2] is ConversationRuntimeEvent.SnapshotUpdated)
            assertEquals(message.id.value, (replayedEvents[0] as ConversationRuntimeEvent.MessageEmitted).taskId?.value)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `split workers wake compatible continuation after active task completes`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val workQueue = BroadcastRuntimeWorkQueue()
        val message = userMessage("message-1")
        val userTask = runtimeUserTurnTask(message)
        val llmTask = runtimeLlmTask(message.id, iteration = 1)
        val turnRunner = ControllableTaskRunner { startedTask ->
            if (startedTask.id == userTask.id) {
                assertTrue(coordinator.submit(llmTask))
                workQueue.submit(runtimeWorkItem(llmTask))
            }
        }
        val llmRunner = ControllableTaskRunner()
        val turnHarness = dispatcherHarness(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            runner = turnRunner,
            workerId = "turn-worker",
            workerCapabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
        )
        val llmHarness = dispatcherHarness(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            runner = llmRunner,
            workerId = "llm-worker",
            workerCapabilities = setOf(ConversationRuntimeWorkerCapability.LLM_RUNTIME),
        )
        try {
            assertTrue(coordinator.submit(userTask))
            workQueue.submit(runtimeWorkItem(userTask))

            assertEquals(userTask.id.value, turnRunner.awaitStarted().id.value)
            assertNull(withTimeoutOrNull(350) { llmRunner.awaitStarted() })

            turnRunner.releaseCurrentTask()

            assertEquals(llmTask.id.value, llmRunner.awaitStarted().id.value)
            llmRunner.releaseCurrentTask()
            waitUntil { coordinator.find(conversationId) == null }
        } finally {
            turnHarness.close()
            llmHarness.close()
        }
    }

    @Test
    fun `dispatcher runs internal continuation before queued user turn`() = runBlocking {
        lateinit var dispatcher: ConversationRuntimeDispatcher
        val firstMessage = userMessage("message-1")
        val secondMessage = userMessage("message-2")
        val llmTask = runtimeLlmTask(firstMessage.id, iteration = 1)
        val runner = ControllableTaskRunner { startedTask ->
            if (startedTask.id == ConversationRuntimeTask.Id(firstMessage.id.value)) {
                dispatcher.submitContinuationTask(llmTask)
            }
        }
        val harness = dispatcherHarness(
            runner = runner,
            workerCapabilities = setOf(
                ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ConversationRuntimeWorkerCapability.LLM_RUNTIME,
            ),
        )
        dispatcher = harness.dispatcher
        try {
            assertTrue(harness.dispatcher.submitMessage(conversationId, firstMessage, agent))
            assertEquals(firstMessage.id.value, harness.runner.awaitStarted().id.value)
            assertTrue(
                harness.dispatcher.enqueueMessage(
                    conversationId = conversationId,
                    userMessage = secondMessage,
                    agent = agent,
                    placement = QueuedMessagePlacement.END_OF_TURN,
                )
            )

            harness.runner.releaseCurrentTask()

            assertEquals(llmTask.id.value, harness.runner.awaitStarted().id.value)
            harness.runner.releaseCurrentTask()

            assertEquals(secondMessage.id.value, harness.runner.awaitStarted().id.value)
            harness.runner.releaseCurrentTask()
            waitUntil { harness.coordinator.find(conversationId) == null }
        } finally {
            harness.close()
        }
    }

    private fun dispatcherHarness(
        coordinator: InMemoryConversationRuntimeCoordinator = InMemoryConversationRuntimeCoordinator(),
        eventBus: InMemoryConversationRuntimeEventBus = InMemoryConversationRuntimeEventBus(),
        workQueue: ConversationRuntimeWorkQueue = InMemoryConversationRuntimeWorkQueue(),
        runner: ControllableTaskRunner = ControllableTaskRunner(),
        workerId: String = "dispatcher-test-worker",
        workerCapabilities: Set<ConversationRuntimeWorkerCapability> = setOf(
            ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
            ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
        ),
    ): DispatcherHarness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workerDescriptor = ConversationRuntimeWorkerDescriptor(
            id = workerId,
            capabilities = workerCapabilities,
        )
        val dispatcher = ConversationRuntimeDispatcher(
            runtimeCoordinator = coordinator,
            runtimeEventBus = eventBus,
            runtimeWorkQueue = workQueue,
            taskRunnerProvider = objectProvider(runner),
            runtimeWorkerDescriptor = workerDescriptor,
            coroutineScope = scope,
        )
        return DispatcherHarness(coordinator, dispatcher, runner, scope)
    }

    private fun userMessage(id: String): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id(id),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Text $id")),
            createdAt = Clock.System.now(),
        )

    private fun runtimeUserTurnTask(
        userMessage: Conversation.Message,
        placement: QueuedMessagePlacement = QueuedMessagePlacement.END_OF_TURN,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id(userMessage.id.value),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.UserTurn(
                userMessage = userMessage,
                agent = agent,
            ),
            placement = placement,
            idempotencyKey = "conversation:${conversationId.value}:message:${userMessage.id.value}",
            createdAt = Clock.System.now(),
        )

    private fun runtimeLlmTask(
        rootUserMessageId: Conversation.Message.Id,
        iteration: Int,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${rootUserMessageId.value}:llm:$iteration"),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.LlmCall(
                rootUserMessageId = rootUserMessageId,
                agent = agent,
                iteration = iteration,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "conversation:${conversationId.value}:runtime:${rootUserMessageId.value}:llm:$iteration",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(ConversationRuntimeWorkerCapability.LLM_RUNTIME),
            ),
            createdAt = Clock.System.now(),
        )

    private fun runtimeWorkItem(task: ConversationRuntimeTask): ConversationRuntimeWorkItem =
        ConversationRuntimeWorkItem(
            conversationId = task.conversationId,
            reason = ConversationRuntimeWorkItem.Reason.TASK_SUBMITTED,
            taskId = task.id,
            requirements = task.requirements,
            createdAt = Clock.System.now(),
        )

    private suspend fun waitUntil(predicate: suspend () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) {
                delay(20)
            }
        }
    }

    private class DispatcherHarness(
        val coordinator: InMemoryConversationRuntimeCoordinator,
        val dispatcher: ConversationRuntimeDispatcher,
        val runner: ControllableTaskRunner,
        private val scope: CoroutineScope,
    ) {
        fun close() {
            scope.cancel()
        }
    }

    private class ControllableTaskRunner(
        private val onStarted: suspend (ConversationRuntimeTask) -> Unit = {},
    ) : ConversationRuntimeTaskRunner {
        private val startedTasks = Channel<ConversationRuntimeTask>(Channel.UNLIMITED)
        private val releases = Channel<Unit>(Channel.UNLIMITED)

        override fun runRuntimeTask(
            task: ConversationRuntimeTask,
            workerId: String,
        ): Flow<Conversation.Message> = flow {
            startedTasks.send(task)
            onStarted(task)
            releases.receive()
            emit(
                Conversation.Message(
                    id = Conversation.Message.Id("${task.id.value}:result"),
                    conversationId = task.conversationId,
                    role = Conversation.Message.Role.ASSISTANT,
                    content = listOf(
                        Conversation.Message.ContentItem.AssistantMessage(
                            Conversation.Message.StructuredText("ok")
                        )
                    ),
                    createdAt = Clock.System.now(),
                )
            )
        }

        suspend fun awaitStarted(): ConversationRuntimeTask =
            withTimeout(2_000) { startedTasks.receive() }

        suspend fun releaseCurrentTask() {
            releases.send(Unit)
        }
    }

    private fun <T : Any> objectProvider(value: T): ObjectProvider<T> =
        object : ObjectProvider<T> {
            override fun getObject(): T = value
        }

    private class BroadcastRuntimeWorkQueue : ConversationRuntimeWorkQueue {
        private val items = MutableSharedFlow<ConversationRuntimeWorkItem>(extraBufferCapacity = 64)

        override val deliveries: Flow<ConversationRuntimeWorkDelivery> =
            items.map { item ->
                object : ConversationRuntimeWorkDelivery {
                    override val item: ConversationRuntimeWorkItem = item
                    override val retryCount: Int = 0
                    override val isFinalRetry: Boolean = false
                    override suspend fun ack() = Unit
                    override suspend fun retry() = Unit
                    override suspend fun fail() = Unit
                }
            }

        override suspend fun submit(item: ConversationRuntimeWorkItem) {
            items.emit(item)
        }
    }

    private class FinalRetryRuntimeWorkQueue : ConversationRuntimeWorkQueue {
        private val channel = Channel<ConversationRuntimeWorkDelivery>(Channel.UNLIMITED)
        private val failed = CompletableDeferred<Unit>()

        override val deliveries: Flow<ConversationRuntimeWorkDelivery> = channel.receiveAsFlow()

        override suspend fun submit(item: ConversationRuntimeWorkItem) {
            channel.send(
                object : ConversationRuntimeWorkDelivery {
                    override val item: ConversationRuntimeWorkItem = item
                    override val retryCount: Int = 8
                    override val isFinalRetry: Boolean = true

                    override suspend fun ack() = Unit

                    override suspend fun retry() = error("Final retry delivery must not be retried")

                    override suspend fun fail() {
                        failed.complete(Unit)
                    }
                }
            )
        }

        suspend fun awaitFailed() {
            withTimeout(2_000) {
                failed.await()
            }
        }
    }
}
