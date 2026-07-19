package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskIncident
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeWorkDelivery
import com.gromozeka.domain.service.ConversationRuntimeWorkConsumer
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkPublisher
import com.gromozeka.domain.service.ConversationRuntimeWorkerAffinity
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
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
import kotlinx.serialization.json.JsonObject
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_EVENT_TIMEOUT_MS = 10_000L

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
    fun `worker terminates when its registry session is lost`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registryDelegate = InMemoryConversationRuntimeWorkerRegistry()
        val registry = object : ConversationRuntimeWorkerRegistry by registryDelegate {
            override suspend fun heartbeat(
                identity: ConversationRuntimeWorkerIdentity,
                at: kotlinx.datetime.Instant,
            ): Boolean = false
        }
        val worker = runtimeWorker(
            coordinator = InMemoryConversationRuntimeCoordinator(),
            eventBus = InMemoryConversationRuntimeEventBus(),
            workQueue = InMemoryConversationRuntimeWorkQueue(),
            registry = registry,
            runner = ControllableTaskRunner(),
            descriptor = ConversationRuntimeWorkerDescriptor(
                id = ConversationRuntimeWorkerId("lost-session-worker"),
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
            heartbeatIntervalMillis = 10,
        )

        try {
            worker.start()
            val failure = withTimeout(1_000) { worker.awaitTermination() }
            assertTrue(failure?.message?.contains("lost registration") == true)
            assertFalse(worker.isRunning)
        } finally {
            worker.stop()
            scope.cancel()
        }
    }

    @Test
    fun `dispatcher starts idle conversation for after tool result message`() = runBlocking {
        val harness = dispatcherHarness()
        try {
            val message = userMessage("message-1")

            assertTrue(
                harness.dispatcher.enqueueMessage(
                    conversationId = conversationId,
                    userMessage = message,
                    agent = agent,
                    placement = QueuedMessagePlacement.AFTER_TOOL_RESULT,
                )
            )

            val startedTask = harness.runner.awaitStarted()
            assertEquals(message.id.value, startedTask.id.value)
            assertEquals(QueuedMessagePlacement.END_OF_TURN, startedTask.placement)

            harness.runner.releaseCurrentTask()
            waitUntil { harness.coordinator.find(conversationId) == null }
        } finally {
            harness.close()
        }
    }

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
    fun `dispatcher records final delivery incident before rejecting dead letter`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val workQueue = FinalRedeliveryRuntimeWorkQueue()
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
            assertEquals(1, snapshot.incidents.size)
            assertEquals(llmTask.id, snapshot.incidents.single().task.id)
            assertEquals(ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED, snapshot.incidents.single().kind)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `worker acknowledges Rabbit delivery before running a claimed task`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val workQueue = AcknowledgementTrackingRuntimeWorkQueue()
        val runner = ControllableTaskRunner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val worker = runtimeWorker(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            registry = InMemoryConversationRuntimeWorkerRegistry(),
            runner = runner,
            descriptor = ConversationRuntimeWorkerDescriptor(
                id = ConversationRuntimeWorkerId("ack-before-execution-worker"),
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
        )
        val task = runtimeUserTurnTask(userMessage("ack-before-execution-message"))

        try {
            worker.start()
            assertTrue(coordinator.submit(task))
            workQueue.submit(runtimeWorkItem(task))
            workQueue.awaitAcknowledged()
            assertNull(withTimeoutOrNull(250) { runner.awaitStarted() })
            workQueue.completeAcknowledgement()
            assertEquals(task.id, runner.awaitStarted().id)
            assertTrue(coordinator.confirmActiveTaskOwner(conversationId, task.id, worker.identity))
            runner.releaseCurrentTask()
            waitUntil { coordinator.find(conversationId) == null }
        } finally {
            worker.stop()
            scope.cancel()
        }
    }

    @Test
    fun `worker records delivery failure without running task when Rabbit acknowledgement fails`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val workQueue = FailingAcknowledgementRuntimeWorkQueue()
        val runner = ControllableTaskRunner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val worker = runtimeWorker(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            registry = InMemoryConversationRuntimeWorkerRegistry(),
            runner = runner,
            descriptor = ConversationRuntimeWorkerDescriptor(
                id = ConversationRuntimeWorkerId("failed-ack-worker"),
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
        )
        val task = runtimeUserTurnTask(userMessage("failed-ack-message"))

        try {
            worker.start()
            assertTrue(coordinator.submit(task))
            workQueue.submit(runtimeWorkItem(task))

            waitUntil { coordinator.snapshot(conversationId).incidents.isNotEmpty() }
            assertNull(withTimeoutOrNull(250) { runner.awaitStarted() })
            val snapshot = coordinator.snapshot(conversationId)
            assertEquals(ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED, snapshot.incidents.single().kind)
            assertNull(snapshot.activeTask)
        } finally {
            worker.stop()
            scope.cancel()
        }
    }

    @Test
    fun `worker stop during Rabbit acknowledgement records delivery failure without running task`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val workQueue = AcknowledgementTrackingRuntimeWorkQueue()
        val runner = ControllableTaskRunner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val worker = runtimeWorker(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            registry = InMemoryConversationRuntimeWorkerRegistry(),
            runner = runner,
            descriptor = ConversationRuntimeWorkerDescriptor(
                id = ConversationRuntimeWorkerId("stopped-during-ack-worker"),
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
        )
        val task = runtimeUserTurnTask(userMessage("stopped-during-ack-message"))

        try {
            worker.start()
            assertTrue(coordinator.submit(task))
            workQueue.submit(runtimeWorkItem(task))
            workQueue.awaitAcknowledged()

            worker.stop()

            assertNull(withTimeoutOrNull(250) { runner.awaitStarted() })
            val snapshot = coordinator.snapshot(conversationId)
            assertEquals(ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED, snapshot.incidents.single().kind)
            assertNull(snapshot.incidents.single().executionStartedAt)
        } finally {
            worker.stop()
            scope.cancel()
        }
    }

    @Test
    fun `worker cancellation records unknown outcome without redelivering claimed task`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val workQueue = AcknowledgementTrackingRuntimeWorkQueue()
        val runner = ControllableTaskRunner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val worker = runtimeWorker(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            registry = InMemoryConversationRuntimeWorkerRegistry(),
            runner = runner,
            descriptor = ConversationRuntimeWorkerDescriptor(
                id = ConversationRuntimeWorkerId("cancelled-worker"),
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
        )
        val task = runtimeUserTurnTask(userMessage("cancelled-worker-message"))

        try {
            worker.start()
            assertTrue(coordinator.submit(task))
            workQueue.submit(runtimeWorkItem(task))
            workQueue.awaitAcknowledged()
            workQueue.completeAcknowledgement()
            assertEquals(task.id, runner.awaitStarted().id)

            worker.stop()

            val snapshot = coordinator.snapshot(conversationId)
            assertNull(snapshot.activeTask)
            assertEquals(ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN, snapshot.incidents.single().kind)
            assertEquals(task.id, snapshot.incidents.single().task.id)
            assertEquals(
                ConversationRuntimeTask.Payload.ExecutionIncident(task.id),
                snapshot.pendingTasks.single().payload,
            )
        } finally {
            worker.stop()
            scope.cancel()
        }
    }

    @Test
    fun `dispatcher records delivery failure when assigned worker stops before execution starts`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val workQueue = InMemoryTestRuntimeWorkBroker()
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val task = runtimeUserTurnTask(userMessage("unavailable-worker-message"))
        val assignedWorker = ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId("unavailable-worker"),
            sessionId = ConversationRuntimeWorkerSessionId("stopped-session"),
        )
        val stoppedAt = Clock.System.now()

        try {
            assertTrue(coordinator.submit(task))
            assertEquals(
                task,
                coordinator.claimDeliveredTask(
                    conversationId = conversationId,
                    taskId = task.id,
                    worker = assignedWorker,
                    workerCapabilities = task.requirements.capabilities,
                    workerAffinities = emptySet(),
                )
            )
            assertTrue(
                workerRegistry.register(
                    ConversationRuntimeWorkerRegistration(
                        identity = assignedWorker,
                        capabilities = task.requirements.capabilities,
                        affinities = emptySet(),
                        version = "test",
                        startedAt = stoppedAt,
                        lastHeartbeatAt = stoppedAt,
                        stoppedAt = stoppedAt,
                    ),
                    staleBefore = stoppedAt,
                )
            )

            ConversationRuntimeDispatcher(
                runtimeCoordinator = coordinator,
                runtimeEventBus = eventBus,
                runtimeWorkPublisher = workQueue,
                runtimeWorkerRegistry = workerRegistry,
                coroutineScope = scope,
            )

            waitUntil { coordinator.snapshot(conversationId).incidents.isNotEmpty() }
            val snapshot = coordinator.snapshot(conversationId)
            assertEquals(ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED, snapshot.incidents.single().kind)
            assertNull(snapshot.activeTask)
            assertEquals(
                ConversationRuntimeTask.Payload.ExecutionIncident(task.id),
                snapshot.pendingTasks.single().payload,
            )
        } finally {
            scope.cancel()
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

            val replayedEvents = withTimeout(TEST_EVENT_TIMEOUT_MS) {
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
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
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
            workerRegistry = workerRegistry,
            runner = turnRunner,
            workerId = "turn-worker",
            workerCapabilities = setOf(
                ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
            ),
        )
        val llmHarness = dispatcherHarness(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            workerRegistry = workerRegistry,
            runner = llmRunner,
            workerId = "llm-worker",
            workerCapabilities = setOf(
                ConversationRuntimeWorkerCapability.LLM_RUNTIME,
                ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
            ),
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
    fun `runtime crosses cloud and project workers at the local tool boundary`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val workQueue = BroadcastRuntimeWorkQueue()
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val projectAffinity = ConversationRuntimeWorkerAffinity(
            kind = ConversationRuntimeWorkerAffinity.Kind.PROJECT,
            value = "project-1",
        )
        val firstMessage = userMessage("message-1")
        val llmTask1 = runtimeLlmTask(firstMessage.id, iteration = 1)
        val toolTask = runtimeToolTask(firstMessage.id, iteration = 1, projectAffinity = projectAffinity)
        val toolResultProcessingTask = runtimeToolResultProcessingTask(firstMessage.id, iteration = 1)
        val llmTask2 = runtimeLlmTask(firstMessage.id, iteration = 2)
        val executionOrder = Channel<Pair<ConversationRuntimeTask, ConversationRuntimeWorkerIdentity>>(Channel.UNLIMITED)
        val cloudRunner = ChainedTaskRunner(executionOrder) { task ->
            when (task.id) {
                ConversationRuntimeTask.Id(firstMessage.id.value) -> assertTrue(coordinator.submit(llmTask1))
                llmTask1.id -> assertTrue(coordinator.submit(toolTask))
                toolResultProcessingTask.id -> assertTrue(coordinator.submit(llmTask2))
            }
        }
        val projectRunner = ChainedTaskRunner(executionOrder) { task ->
            if (task.id == toolTask.id) {
                assertTrue(coordinator.submit(toolResultProcessingTask))
            }
        }
        val dispatcher = ConversationRuntimeDispatcher(
            runtimeCoordinator = coordinator,
            runtimeEventBus = eventBus,
            runtimeWorkPublisher = workQueue,
            runtimeWorkerRegistry = workerRegistry,
            coroutineScope = scope,
        )
        val cloudWorker = runtimeWorker(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            registry = workerRegistry,
            runner = cloudRunner,
            descriptor = ConversationRuntimeWorkerDescriptor(
                id = ConversationRuntimeWorkerId("cloud-worker"),
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                    ConversationRuntimeWorkerCapability.LLM_RUNTIME,
                    ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
        )
        val projectWorker = runtimeWorker(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            registry = workerRegistry,
            runner = projectRunner,
            descriptor = ConversationRuntimeWorkerDescriptor(
                id = ConversationRuntimeWorkerId("project-worker"),
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                    ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
                ),
                affinities = setOf(projectAffinity),
            ),
            scope = scope,
        )

        try {
            cloudWorker.start()
            projectWorker.start()
            assertTrue(dispatcher.submitMessage(conversationId, firstMessage, agent))

            val executions = withTimeout(TEST_EVENT_TIMEOUT_MS) {
                List(5) { executionOrder.receive() }
            }
            assertEquals(
                listOf(
                    firstMessage.id.value,
                    llmTask1.id.value,
                    toolTask.id.value,
                    toolResultProcessingTask.id.value,
                    llmTask2.id.value,
                ),
                executions.map { it.first.id.value },
            )
            assertEquals(
                listOf("cloud-worker", "cloud-worker", "project-worker", "cloud-worker", "cloud-worker"),
                executions.map { it.second.workerId.value },
            )
            waitUntil { coordinator.find(conversationId) == null }
        } finally {
            cloudWorker.stop()
            projectWorker.stop()
            scope.cancel()
        }
    }

    @Test
    fun `dispatcher runs internal continuation before queued user turn`() = runBlocking {
        val firstMessage = userMessage("message-1")
        val secondMessage = userMessage("message-2")
        val llmTask = runtimeLlmTask(firstMessage.id, iteration = 1)
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val runner = ControllableTaskRunner { startedTask ->
            if (startedTask.id == ConversationRuntimeTask.Id(firstMessage.id.value)) {
                assertTrue(coordinator.submit(llmTask))
            }
        }
        val harness = dispatcherHarness(
            coordinator = coordinator,
            runner = runner,
            workerCapabilities = setOf(
                ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ConversationRuntimeWorkerCapability.LLM_RUNTIME,
            ),
        )
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
        workQueue: TestRuntimeWorkBroker = InMemoryTestRuntimeWorkBroker(),
        workerRegistry: InMemoryConversationRuntimeWorkerRegistry = InMemoryConversationRuntimeWorkerRegistry(),
        runner: ControllableTaskRunner = ControllableTaskRunner(),
        workerId: String = "dispatcher-test-worker",
        workerCapabilities: Set<ConversationRuntimeWorkerCapability> = setOf(
            ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
            ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
        ),
    ): DispatcherHarness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workerDescriptor = ConversationRuntimeWorkerDescriptor(
            id = ConversationRuntimeWorkerId(workerId),
            capabilities = workerCapabilities,
        )
        val dispatcher = ConversationRuntimeDispatcher(
            runtimeCoordinator = coordinator,
            runtimeEventBus = eventBus,
            runtimeWorkPublisher = workQueue,
            runtimeWorkerRegistry = workerRegistry,
            coroutineScope = scope,
        )
        val worker = ConversationRuntimeWorker(
            runtimeCoordinator = coordinator,
            runtimeEventBus = eventBus,
            runtimeWorkConsumer = workQueue,
            runtimeWorkerRegistry = workerRegistry,
            taskRunnerProvider = objectProvider(runner),
            runtimeWorkerDescriptor = workerDescriptor,
            workerVersion = "test",
            parentScope = scope,
        )
        worker.start()
        return DispatcherHarness(coordinator, dispatcher, worker, runner, scope)
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
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
            ),
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
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.LLM_RUNTIME,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
            ),
            createdAt = Clock.System.now(),
        )

    private fun runtimeToolTask(
        rootUserMessageId: Conversation.Message.Id,
        iteration: Int,
        projectAffinity: ConversationRuntimeWorkerAffinity,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${rootUserMessageId.value}:tools:$iteration"),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.ToolExecution(
                rootUserMessageId = rootUserMessageId,
                agent = agent,
                iteration = iteration,
                toolCalls = listOf(
                    Conversation.Message.ContentItem.ToolCall(
                        id = Conversation.Message.ContentItem.ToolCall.Id("tool-call-$iteration"),
                        call = Conversation.Message.ContentItem.ToolCall.Data(
                            name = "grz_read_file",
                            input = JsonObject(emptyMap()),
                        ),
                    )
                ),
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "conversation:${conversationId.value}:runtime:${rootUserMessageId.value}:tools:$iteration",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                    ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
                ),
                affinity = projectAffinity,
            ),
            createdAt = Clock.System.now(),
        )

    private fun runtimeToolResultProcessingTask(
        rootUserMessageId: Conversation.Message.Id,
        iteration: Int,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${rootUserMessageId.value}:tool-result-processing:$iteration"),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.ToolResultProcessing(
                rootUserMessageId = rootUserMessageId,
                toolResultMessageId = Conversation.Message.Id("tool-result-$iteration"),
                agent = agent,
                iteration = iteration,
                returnDirect = false,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey =
                "conversation:${conversationId.value}:runtime:${rootUserMessageId.value}:tool-result-processing:$iteration",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
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
        withTimeout(TEST_EVENT_TIMEOUT_MS) {
            while (!predicate()) {
                delay(20)
            }
        }
    }

    private class DispatcherHarness(
        val coordinator: InMemoryConversationRuntimeCoordinator,
        val dispatcher: ConversationRuntimeDispatcher,
        private val worker: ConversationRuntimeWorker,
        val runner: ControllableTaskRunner,
        private val scope: CoroutineScope,
    ) {
        fun close() {
            worker.stop()
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
            worker: ConversationRuntimeWorkerIdentity,
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
            withTimeout(TEST_EVENT_TIMEOUT_MS) { startedTasks.receive() }

        suspend fun releaseCurrentTask() {
            releases.send(Unit)
        }
    }

    private class ChainedTaskRunner(
        private val executionOrder: Channel<Pair<ConversationRuntimeTask, ConversationRuntimeWorkerIdentity>>,
        private val onStarted: suspend (ConversationRuntimeTask) -> Unit,
    ) : ConversationRuntimeTaskRunner {
        override fun runRuntimeTask(
            task: ConversationRuntimeTask,
            worker: ConversationRuntimeWorkerIdentity,
        ): Flow<Conversation.Message> = flow {
            executionOrder.send(task to worker)
            onStarted(task)
        }
    }

    private fun runtimeWorker(
        coordinator: InMemoryConversationRuntimeCoordinator,
        eventBus: InMemoryConversationRuntimeEventBus,
        workQueue: ConversationRuntimeWorkConsumer,
        registry: ConversationRuntimeWorkerRegistry,
        runner: ConversationRuntimeTaskRunner,
        descriptor: ConversationRuntimeWorkerDescriptor,
        scope: CoroutineScope,
        heartbeatIntervalMillis: Long = ConversationRuntimeTiming.workerHeartbeatIntervalMillis,
    ): ConversationRuntimeWorker =
        ConversationRuntimeWorker(
            runtimeCoordinator = coordinator,
            runtimeEventBus = eventBus,
            runtimeWorkConsumer = workQueue,
            runtimeWorkerRegistry = registry,
            taskRunnerProvider = objectProvider(runner),
            runtimeWorkerDescriptor = descriptor,
            workerVersion = "test",
            heartbeatIntervalMillis = heartbeatIntervalMillis,
            parentScope = scope,
        )

    private fun <T : Any> objectProvider(value: T): ObjectProvider<T> =
        object : ObjectProvider<T> {
            override fun getObject(): T = value
        }

    private interface TestRuntimeWorkBroker : ConversationRuntimeWorkPublisher, ConversationRuntimeWorkConsumer

    private class InMemoryTestRuntimeWorkBroker : TestRuntimeWorkBroker {
        private val delegate = InMemoryConversationRuntimeWorkQueue()

        override val deliveries: Flow<ConversationRuntimeWorkDelivery> = delegate.deliveries

        override suspend fun submit(item: ConversationRuntimeWorkItem) {
            delegate.submit(item)
        }
    }

    private class BroadcastRuntimeWorkQueue : TestRuntimeWorkBroker {
        private val items = MutableSharedFlow<ConversationRuntimeWorkItem>(replay = 64, extraBufferCapacity = 64)

        override val deliveries: Flow<ConversationRuntimeWorkDelivery> =
            items.map { item ->
                object : ConversationRuntimeWorkDelivery {
                    override val item: ConversationRuntimeWorkItem = item
                    override val redeliveryCount: Int = 0
                    override val isFinalRedelivery: Boolean = false
                    override suspend fun acknowledge() = Unit
                    override suspend fun redeliver() = Unit
                    override suspend fun reject() = Unit
                }
            }

        override suspend fun submit(item: ConversationRuntimeWorkItem) {
            items.emit(item)
        }
    }

    private class FinalRedeliveryRuntimeWorkQueue : TestRuntimeWorkBroker {
        private val channel = Channel<ConversationRuntimeWorkDelivery>(Channel.UNLIMITED)
        private val failed = CompletableDeferred<Unit>()

        override val deliveries: Flow<ConversationRuntimeWorkDelivery> = channel.receiveAsFlow()

        override suspend fun submit(item: ConversationRuntimeWorkItem) {
            channel.send(
                object : ConversationRuntimeWorkDelivery {
                    override val item: ConversationRuntimeWorkItem = item
                    override val redeliveryCount: Int = 8
                    override val isFinalRedelivery: Boolean = true

                    override suspend fun acknowledge() = Unit

                    override suspend fun redeliver() =
                        error("Final redelivery must not be redelivered")

                    override suspend fun reject() {
                        failed.complete(Unit)
                    }
                }
            )
        }

        suspend fun awaitFailed() {
            withTimeout(TEST_EVENT_TIMEOUT_MS) {
                failed.await()
            }
        }
    }

    private class AcknowledgementTrackingRuntimeWorkQueue : TestRuntimeWorkBroker {
        private val channel = Channel<ConversationRuntimeWorkDelivery>(Channel.UNLIMITED)
        private val acknowledged = CompletableDeferred<Unit>()
        private val acknowledgementCompleted = CompletableDeferred<Unit>()

        override val deliveries: Flow<ConversationRuntimeWorkDelivery> = channel.receiveAsFlow()

        override suspend fun submit(item: ConversationRuntimeWorkItem) {
            channel.send(
                object : ConversationRuntimeWorkDelivery {
                    override val item: ConversationRuntimeWorkItem = item
                    override val redeliveryCount: Int = 0
                    override val isFinalRedelivery: Boolean = false

                    override suspend fun acknowledge() {
                        acknowledged.complete(Unit)
                        acknowledgementCompleted.await()
                    }

                    override suspend fun redeliver() = error("Claimed task must not be redelivered")

                    override suspend fun reject() = error("Claimed task must not be rejected")
                }
            )
        }

        suspend fun awaitAcknowledged() {
            withTimeout(TEST_EVENT_TIMEOUT_MS) {
                acknowledged.await()
            }
        }

        fun completeAcknowledgement() {
            acknowledgementCompleted.complete(Unit)
        }
    }

    private class FailingAcknowledgementRuntimeWorkQueue : TestRuntimeWorkBroker {
        private val channel = Channel<ConversationRuntimeWorkDelivery>(Channel.UNLIMITED)

        override val deliveries: Flow<ConversationRuntimeWorkDelivery> = channel.receiveAsFlow()

        override suspend fun submit(item: ConversationRuntimeWorkItem) {
            channel.send(
                object : ConversationRuntimeWorkDelivery {
                    override val item: ConversationRuntimeWorkItem = item
                    override val redeliveryCount: Int = 0
                    override val isFinalRedelivery: Boolean = false

                    override suspend fun acknowledge(): Nothing =
                        error("Rabbit acknowledgement failed")

                    override suspend fun redeliver() = error("Claimed task must not be redelivered")

                    override suspend fun reject() = error("Claimed task must not be rejected")
                }
            )
        }
    }

}
