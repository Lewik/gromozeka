package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSecretMutation
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeExecutorDescriptor
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskIncident
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkDelivery
import com.gromozeka.domain.service.ConversationRuntimeWorkConsumer
import com.gromozeka.domain.service.ConversationRuntimeWorkItem
import com.gromozeka.domain.service.ConversationRuntimeWorkPublisher
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.ConversationRuntimeServerSessionId
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_EVENT_TIMEOUT_MS = 10_000L

class ConversationRuntimeDispatcherTest {
    private val conversationId = Conversation.Id("conversation-runtime-dispatcher-test")
    private val agentDefinitionId = AgentDefinition.Id("agent-1")

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
        val worker = registrationWorker(
            registry = registry,
            descriptor = ConversationRuntimeWorkerDescriptor(
                id = ConversationRuntimeWorkerId("lost-session-worker"),
                capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
                environmentProfile = testWorkerEnvironmentProfile(),
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
    fun `worker stays alive across transient heartbeat failures`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registryDelegate = InMemoryConversationRuntimeWorkerRegistry()
        val heartbeatAttempts = AtomicInteger()
        val registry = object : ConversationRuntimeWorkerRegistry by registryDelegate {
            override suspend fun heartbeat(
                identity: ConversationRuntimeWorkerIdentity,
                at: kotlinx.datetime.Instant,
            ): Boolean {
                if (heartbeatAttempts.incrementAndGet() <= 2) {
                    error("Control plane is unavailable")
                }
                return registryDelegate.heartbeat(identity, at)
            }
        }
        val worker = registrationWorker(
            registry = registry,
            descriptor = ConversationRuntimeWorkerDescriptor(
                id = ConversationRuntimeWorkerId("recovering-heartbeat-worker"),
                capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
                environmentProfile = testWorkerEnvironmentProfile(),
            ),
            scope = scope,
            heartbeatIntervalMillis = 10,
        )

        try {
            worker.start()
            withTimeout(1_000) {
                while (heartbeatAttempts.get() < 3) {
                    delay(10)
                }
            }

            assertTrue(worker.isRunning)
            assertNull(withTimeoutOrNull(100) { worker.awaitTermination() })
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
                    agentDefinitionId = agentDefinitionId,
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

            assertTrue(harness.dispatcher.submitMessage(conversationId, firstMessage, agentDefinitionId))
            assertEquals(firstMessage.id.value, harness.runner.awaitStarted().id.value)

            assertTrue(
                harness.dispatcher.enqueueMessage(
                    conversationId = conversationId,
                    userMessage = secondMessage,
                    agentDefinitionId = agentDefinitionId,
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

            assertTrue(harness.dispatcher.submitMessage(conversationId, firstMessage, agentDefinitionId))
            assertEquals(firstMessage.id.value, harness.runner.awaitStarted().id.value)
            assertTrue(
                harness.dispatcher.enqueueMessage(
                    conversationId = conversationId,
                    userMessage = secondMessage,
                    agentDefinitionId = agentDefinitionId,
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

            assertTrue(harness.dispatcher.submitMessage(conversationId, firstMessage, agentDefinitionId))
            assertEquals(firstMessage.id.value, harness.runner.awaitStarted().id.value)
            assertTrue(
                harness.dispatcher.enqueueMessage(
                    conversationId = conversationId,
                    userMessage = secondMessage,
                    agentDefinitionId = agentDefinitionId,
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
    fun `dispatcher requests cancellation for an active command monitor`() = runBlocking {
        val harness = dispatcherHarness()
        try {
            val now = Clock.System.now()
            val monitor = CommandMonitor(
                id = CommandMonitor.Id("monitor-1"),
                conversationId = conversationId,
                commandTaskId = CommandTask.Id("command-1"),
                workerId = ConversationRuntimeWorkerId("worker-1"),
                workspaceMountId = WorkspaceMount.Id("mount-1"),
                filterCommand = "grep --line-buffered READY",
                mode = CommandMonitor.Mode.CONTINUOUS,
                startFrom = CommandMonitor.StartFrom.NOW,
                status = CommandMonitor.Status.WORKING,
                sourceOutputCursor = 0,
                processId = 101,
                processStartedAt = now,
                outputFile = "/tmp/monitor-1.log",
                errorFile = "/tmp/monitor-1.err",
                outputBytes = 0,
                eventOutputCursor = 0,
                createdAt = now,
                updatedAt = now,
            )
            harness.coordinator.synchronizeCommandMonitor(monitor)

            assertTrue(harness.dispatcher.cancelCommandMonitor(conversationId, monitor.id))

            val stored = assertNotNull(
                harness.coordinator.findCommandMonitor(conversationId, monitor.id)
            )
            assertNotNull(stored.cancellationRequestedAt)
            assertEquals("Cancellation requested", stored.statusMessage)
            assertTrue(harness.dispatcher.cancelCommandMonitor(conversationId, monitor.id))
            assertFalse(
                harness.dispatcher.cancelCommandMonitor(
                    conversationId,
                    CommandMonitor.Id("missing-monitor"),
                )
            )
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

            assertTrue(harness.dispatcher.submitMessage(conversationId, firstMessage, agentDefinitionId))
            assertEquals(firstMessage.id.value, harness.runner.awaitStarted().id.value)
            assertTrue(
                harness.dispatcher.enqueueMessage(
                    conversationId = conversationId,
                    userMessage = secondMessage,
                    agentDefinitionId = agentDefinitionId,
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
            executorCapabilities = setOf(ConversationRuntimeCapability.CONVERSATION_TURN),
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
    fun `executor acknowledges Rabbit delivery before running a claimed task`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val workQueue = AcknowledgementTrackingRuntimeWorkQueue()
        val runner = ControllableTaskRunner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = runtimeExecutor(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            runner = runner,
            descriptor = serverExecutorDescriptor(
                sessionId = "ack-before-execution-server",
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
        )
        val task = runtimeUserTurnTask(userMessage("ack-before-execution-message"))

        try {
            executor.start()
            assertTrue(coordinator.submit(task))
            workQueue.submit(runtimeWorkItem(task))
            workQueue.awaitAcknowledged()
            assertNull(withTimeoutOrNull(250) { runner.awaitStarted() })
            workQueue.completeAcknowledgement()
            assertEquals(task.id, runner.awaitStarted().id)
            assertTrue(coordinator.confirmActiveTaskOwner(conversationId, task.id, executor.identity))
            runner.releaseCurrentTask()
            waitUntil { coordinator.find(conversationId) == null }
        } finally {
            executor.stop()
            scope.cancel()
        }
    }

    @Test
    fun `executor records delivery failure without running task when Rabbit acknowledgement fails`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val workQueue = FailingAcknowledgementRuntimeWorkQueue()
        val runner = ControllableTaskRunner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = runtimeExecutor(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            runner = runner,
            descriptor = serverExecutorDescriptor(
                sessionId = "failed-ack-server",
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
        )
        val task = runtimeUserTurnTask(userMessage("failed-ack-message"))

        try {
            executor.start()
            assertTrue(coordinator.submit(task))
            workQueue.submit(runtimeWorkItem(task))

            waitUntil { coordinator.snapshot(conversationId).incidents.isNotEmpty() }
            assertNull(withTimeoutOrNull(250) { runner.awaitStarted() })
            val snapshot = coordinator.snapshot(conversationId)
            assertEquals(ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED, snapshot.incidents.single().kind)
            assertNull(snapshot.activeTask)
        } finally {
            executor.stop()
            scope.cancel()
        }
    }

    @Test
    fun `executor records delivery failure without running task when AI configuration refresh fails`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val workQueue = AcknowledgementTrackingRuntimeWorkQueue()
        val runner = ControllableTaskRunner()
        val aiConfigurationService = TestAiConfigurationService(
            refreshFailure = IllegalStateException("AI catalog unavailable")
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = runtimeExecutor(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            runner = runner,
            descriptor = serverExecutorDescriptor(
                sessionId = "configuration-refresh-server",
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
            aiConfigurationService = aiConfigurationService,
        )
        val task = runtimeUserTurnTask(userMessage("configuration-refresh-message"))

        try {
            executor.start()
            assertTrue(coordinator.submit(task))
            workQueue.submit(runtimeWorkItem(task))

            aiConfigurationService.awaitRefresh()
            waitUntil { coordinator.snapshot(conversationId).incidents.isNotEmpty() }
            workQueue.awaitAcknowledged()
            workQueue.completeAcknowledgement()

            assertNull(withTimeoutOrNull(250) { runner.awaitStarted() })
            val snapshot = coordinator.snapshot(conversationId)
            assertEquals(ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED, snapshot.incidents.single().kind)
            assertNull(snapshot.activeTask)
        } finally {
            executor.stop()
            scope.cancel()
        }
    }

    @Test
    fun `worker refreshes advertised tools without restarting`() = runBlocking {
        val registry = InMemoryConversationRuntimeWorkerRegistry()
        val provider = MutableAiToolProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workerId = ConversationRuntimeWorkerId("dynamic-tools-worker")
        val worker = registrationWorker(
            registry = registry,
            descriptor = ConversationRuntimeWorkerDescriptor(
                id = workerId,
                capabilities = setOf(
                    ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                    ConversationRuntimeCapability.TOOL_EXECUTION,
                ),
                environmentProfile = testWorkerEnvironmentProfile(),
            ),
            scope = scope,
            heartbeatIntervalMillis = 20,
            aiToolProvider = provider,
        )

        try {
            worker.start()
            provider.enabled = true
            waitUntil {
                registry.find(workerId)?.tools?.map { it.definition.name } == listOf("dynamic_tool")
            }

            provider.enabled = false
            waitUntil {
                registry.find(workerId)?.tools?.isEmpty() == true
            }
        } finally {
            worker.stop()
            scope.cancel()
        }
    }

    @Test
    fun `executor stop during Rabbit acknowledgement records delivery failure without running task`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val workQueue = AcknowledgementTrackingRuntimeWorkQueue()
        val runner = ControllableTaskRunner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = runtimeExecutor(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            runner = runner,
            descriptor = serverExecutorDescriptor(
                sessionId = "stopped-during-ack-server",
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
        )
        val task = runtimeUserTurnTask(userMessage("stopped-during-ack-message"))

        try {
            executor.start()
            assertTrue(coordinator.submit(task))
            workQueue.submit(runtimeWorkItem(task))
            workQueue.awaitAcknowledged()

            executor.stop()

            assertNull(withTimeoutOrNull(250) { runner.awaitStarted() })
            val snapshot = coordinator.snapshot(conversationId)
            assertEquals(ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED, snapshot.incidents.single().kind)
            assertNull(snapshot.incidents.single().executionStartedAt)
        } finally {
            executor.stop()
            scope.cancel()
        }
    }

    @Test
    fun `executor cancellation records unknown outcome without redelivering claimed task`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val workQueue = AcknowledgementTrackingRuntimeWorkQueue()
        val runner = ControllableTaskRunner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = runtimeExecutor(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            runner = runner,
            descriptor = serverExecutorDescriptor(
                sessionId = "cancelled-server",
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
        )
        val task = runtimeUserTurnTask(userMessage("cancelled-worker-message"))

        try {
            executor.start()
            assertTrue(coordinator.submit(task))
            workQueue.submit(runtimeWorkItem(task))
            workQueue.awaitAcknowledged()
            workQueue.completeAcknowledgement()
            assertEquals(task.id, runner.awaitStarted().id)

            executor.stop()

            val snapshot = coordinator.snapshot(conversationId)
            assertNull(snapshot.activeTask)
            assertEquals(ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN, snapshot.incidents.single().kind)
            assertEquals(task.id, snapshot.incidents.single().task.id)
            assertEquals(
                ConversationRuntimeTask.Payload.ExecutionIncident(task.id),
                snapshot.pendingTasks.single().payload,
            )
        } finally {
            executor.stop()
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
        val assignedWorker = ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId("unavailable-worker"),
            sessionId = ConversationRuntimeWorkerSessionId("stopped-session"),
        )
        val task = runtimeToolTask(
            rootUserMessageId = Conversation.Message.Id("unavailable-worker-message"),
            iteration = 1,
            target = ConversationRuntimeTaskTarget.Worker(assignedWorker.workerId),
        )
        val assignedExecutor = ConversationRuntimeExecutorIdentity.Worker(assignedWorker)
        val stoppedAt = Clock.System.now()

        try {
            assertTrue(coordinator.submit(task))
            assertEquals(
                task,
                coordinator.claimDeliveredTask(
                    conversationId = conversationId,
                    taskId = task.id,
                    executor = assignedExecutor,
                    executorCapabilities = task.requirements.capabilities,
                    workerWorkspaceMountIds = emptySet(),
                )
            )
            assertTrue(
                workerRegistry.register(
                    ConversationRuntimeWorkerRegistration(
                        identity = assignedWorker,
                        capabilities = task.requirements.capabilities,
                        tools = emptyList(),
                        environmentProfile = testWorkerEnvironmentProfile(stoppedAt),
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

            assertTrue(harness.dispatcher.submitMessage(conversationId, message, agentDefinitionId))
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
    fun `runtime crosses Server and exact workspace Worker at the local tool boundary`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val workQueue = BroadcastRuntimeWorkQueue()
        val workerRegistry = InMemoryConversationRuntimeWorkerRegistry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workspaceId = Workspace.Id("workspace-1")
        val workspaceMountId = WorkspaceMount.Id("mount-1")
        val workspaceWorkerId = ConversationRuntimeWorkerId("workspace-worker")
        val toolTarget = ConversationRuntimeTaskTarget.Worker(
            workerId = workspaceWorkerId,
            workspaceMountId = workspaceMountId,
        )
        val firstMessage = userMessage("message-1")
        val llmTask1 = runtimeLlmTask(firstMessage.id, iteration = 1)
        val toolTask = runtimeToolTask(firstMessage.id, iteration = 1, target = toolTarget)
        val toolResultProcessingTask = runtimeToolResultProcessingTask(firstMessage.id, iteration = 1)
        val llmTask2 = runtimeLlmTask(firstMessage.id, iteration = 2)
        val executionOrder = Channel<Pair<ConversationRuntimeTask, ConversationRuntimeExecutorIdentity>>(Channel.UNLIMITED)
        val serverRunner = ChainedTaskRunner(executionOrder) { task ->
            when (task.id) {
                ConversationRuntimeTask.Id(firstMessage.id.value) -> assertTrue(coordinator.submit(llmTask1))
                llmTask1.id -> assertTrue(coordinator.submit(toolTask))
                toolResultProcessingTask.id -> assertTrue(coordinator.submit(llmTask2))
            }
        }
        val workspaceRunner = ChainedTaskRunner(executionOrder) { task ->
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
        val serverExecutor = runtimeExecutor(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            runner = serverRunner,
            descriptor = serverExecutorDescriptor(
                sessionId = "server-runtime",
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                    ConversationRuntimeCapability.TOOL_EXECUTION,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
        )
        val workspaceWorker = runtimeWorkerNode(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            registry = workerRegistry,
            runner = workspaceRunner,
            descriptor = ConversationRuntimeWorkerDescriptor(
                id = workspaceWorkerId,
                capabilities = setOf(
                    ConversationRuntimeCapability.TOOL_EXECUTION,
                    ConversationRuntimeCapability.LOCAL_AGENT_TOOL,
                ),
                environmentProfile = testWorkerEnvironmentProfile(),
            ),
            scope = scope,
            workspaceMounts = mapOf(workspaceMountId to workspaceId),
        )

        try {
            serverExecutor.start()
            workspaceWorker.start()
            assertTrue(dispatcher.submitMessage(conversationId, firstMessage, agentDefinitionId))

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
                listOf("server", "server", "workspace-worker", "server", "server"),
                executions.map { (_, executor) ->
                    when (executor) {
                        is ConversationRuntimeExecutorIdentity.Server -> "server"
                        is ConversationRuntimeExecutorIdentity.Worker -> executor.identity.workerId.value
                    }
                },
            )
            waitUntil { coordinator.find(conversationId) == null }
        } finally {
            serverExecutor.stop()
            workspaceWorker.stop()
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
            executorCapabilities = setOf(
                ConversationRuntimeCapability.CONVERSATION_TURN,
                ConversationRuntimeCapability.MEMORY_PIPELINE,
                ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
            ),
        )
        try {
            assertTrue(harness.dispatcher.submitMessage(conversationId, firstMessage, agentDefinitionId))
            assertEquals(firstMessage.id.value, harness.runner.awaitStarted().id.value)
            assertTrue(
                harness.dispatcher.enqueueMessage(
                    conversationId = conversationId,
                    userMessage = secondMessage,
                    agentDefinitionId = agentDefinitionId,
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
        executorCapabilities: Set<ConversationRuntimeCapability> = setOf(
            ConversationRuntimeCapability.CONVERSATION_TURN,
            ConversationRuntimeCapability.MEMORY_PIPELINE,
        ),
        aiConfigurationService: AiConfigurationService = TestAiConfigurationService(),
    ): DispatcherHarness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = ConversationRuntimeDispatcher(
            runtimeCoordinator = coordinator,
            runtimeEventBus = eventBus,
            runtimeWorkPublisher = workQueue,
            runtimeWorkerRegistry = workerRegistry,
            coroutineScope = scope,
        )
        val executor = runtimeExecutor(
            coordinator = coordinator,
            eventBus = eventBus,
            workQueue = workQueue,
            runner = runner,
            aiConfigurationService = aiConfigurationService,
            descriptor = serverExecutorDescriptor(
                sessionId = "dispatcher-test-server",
                capabilities = executorCapabilities,
            ),
            scope = scope,
        )
        executor.start()
        return DispatcherHarness(coordinator, dispatcher, executor, runner, scope)
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
                agentDefinitionId = agentDefinitionId,
            ),
            placement = placement,
            idempotencyKey = "conversation:${conversationId.value}:message:${userMessage.id.value}",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
                target = ConversationRuntimeTaskTarget.Server,
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
                agentDefinitionId = agentDefinitionId,
                iteration = iteration,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "conversation:${conversationId.value}:runtime:${rootUserMessageId.value}:llm:$iteration",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
                target = ConversationRuntimeTaskTarget.Server,
            ),
            createdAt = Clock.System.now(),
        )

    private fun runtimeToolTask(
        rootUserMessageId: Conversation.Message.Id,
        iteration: Int,
        target: ConversationRuntimeTaskTarget,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${rootUserMessageId.value}:tools:$iteration"),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.ToolExecution(
                rootUserMessageId = rootUserMessageId,
                agentDefinitionId = agentDefinitionId,
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
                returnDirect = false,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "conversation:${conversationId.value}:runtime:${rootUserMessageId.value}:tools:$iteration",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeCapability.TOOL_EXECUTION,
                    ConversationRuntimeCapability.LOCAL_AGENT_TOOL,
                ),
                target = target,
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
                agentDefinitionId = agentDefinitionId,
                iteration = iteration,
                returnDirect = false,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey =
                "conversation:${conversationId.value}:runtime:${rootUserMessageId.value}:tool-result-processing:$iteration",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
                target = ConversationRuntimeTaskTarget.Server,
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
        private val executor: TestRuntimeExecutor,
        val runner: ControllableTaskRunner,
        private val scope: CoroutineScope,
    ) {
        fun close() {
            executor.stop()
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
            executor: ConversationRuntimeExecutorIdentity,
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
        private val executionOrder: Channel<Pair<ConversationRuntimeTask, ConversationRuntimeExecutorIdentity>>,
        private val onStarted: suspend (ConversationRuntimeTask) -> Unit,
    ) : ConversationRuntimeTaskRunner {
        override fun runRuntimeTask(
            task: ConversationRuntimeTask,
            executor: ConversationRuntimeExecutorIdentity,
        ): Flow<Conversation.Message> = flow {
            executionOrder.send(task to executor)
            onStarted(task)
        }
    }

    private fun registrationWorker(
        registry: ConversationRuntimeWorkerRegistry,
        descriptor: ConversationRuntimeWorkerDescriptor,
        scope: CoroutineScope,
        workspaceMounts: Map<WorkspaceMount.Id, Workspace.Id> = emptyMap(),
        heartbeatIntervalMillis: Long = ConversationRuntimeTiming.workerHeartbeatIntervalMillis,
        aiConfigurationService: AiConfigurationService = TestAiConfigurationService(),
        aiToolProvider: AiToolProvider = EmptyAiToolProvider,
        identity: ConversationRuntimeWorkerIdentity = workerIdentity(descriptor.id),
    ): ConversationRuntimeWorker =
        ConversationRuntimeWorker(
            runtimeWorkerRegistry = registry,
            workspaceService = StaticWorkspaceDomainService(descriptor.id.value, workspaceMounts),
            aiConfigurationService = aiConfigurationService,
            aiToolProvider = aiToolProvider,
            runtimeWorkerDescriptor = descriptor,
            runtimeWorkerIdentity = identity,
            workerVersion = "test",
            heartbeatIntervalMillis = heartbeatIntervalMillis,
            parentScope = scope,
        )

    private fun runtimeExecutor(
        coordinator: InMemoryConversationRuntimeCoordinator,
        eventBus: InMemoryConversationRuntimeEventBus,
        workQueue: ConversationRuntimeWorkConsumer,
        runner: ConversationRuntimeTaskRunner,
        descriptor: ConversationRuntimeExecutorDescriptor,
        scope: CoroutineScope,
        workspaceMounts: Map<WorkspaceMount.Id, Workspace.Id> = emptyMap(),
        aiConfigurationService: AiConfigurationService = TestAiConfigurationService(),
    ): TestRuntimeExecutor {
        val workspaceWorkerId = when (val identity = descriptor.identity) {
            is ConversationRuntimeExecutorIdentity.Server -> "server"
            is ConversationRuntimeExecutorIdentity.Worker -> identity.identity.workerId.value
        }
        return TestRuntimeExecutor(
            delegate = ConversationRuntimeExecutor(
                runtimeCoordinator = coordinator,
                runtimeEventBus = eventBus,
                runtimeWorkConsumer = workQueue,
                workspaceService = StaticWorkspaceDomainService(workspaceWorkerId, workspaceMounts),
                aiConfigurationService = aiConfigurationService,
                taskRunnerProvider = objectProvider(runner),
                descriptor = descriptor,
                parentScope = scope,
            ),
            identity = descriptor.identity,
        )
    }

    private fun runtimeWorkerNode(
        coordinator: InMemoryConversationRuntimeCoordinator,
        eventBus: InMemoryConversationRuntimeEventBus,
        workQueue: ConversationRuntimeWorkConsumer,
        registry: ConversationRuntimeWorkerRegistry,
        runner: ConversationRuntimeTaskRunner,
        descriptor: ConversationRuntimeWorkerDescriptor,
        scope: CoroutineScope,
        workspaceMounts: Map<WorkspaceMount.Id, Workspace.Id> = emptyMap(),
    ): TestRuntimeWorkerNode {
        val identity = workerIdentity(descriptor.id)
        return TestRuntimeWorkerNode(
            worker = registrationWorker(
                registry = registry,
                descriptor = descriptor,
                scope = scope,
                workspaceMounts = workspaceMounts,
                identity = identity,
            ),
            executor = runtimeExecutor(
                coordinator = coordinator,
                eventBus = eventBus,
                workQueue = workQueue,
                runner = runner,
                descriptor = ConversationRuntimeExecutorDescriptor(
                    identity = ConversationRuntimeExecutorIdentity.Worker(identity),
                    capabilities = descriptor.capabilities,
                ),
                scope = scope,
                workspaceMounts = workspaceMounts,
            ),
        )
    }

    private fun serverExecutorDescriptor(
        sessionId: String,
        capabilities: Set<ConversationRuntimeCapability>,
    ): ConversationRuntimeExecutorDescriptor =
        ConversationRuntimeExecutorDescriptor(
            identity = ConversationRuntimeExecutorIdentity.Server(
                ConversationRuntimeServerSessionId(sessionId)
            ),
            capabilities = capabilities,
        )

    private fun workerIdentity(workerId: ConversationRuntimeWorkerId): ConversationRuntimeWorkerIdentity =
        ConversationRuntimeWorkerIdentity(
            workerId = workerId,
            sessionId = ConversationRuntimeWorkerSessionId("${workerId.value}-session"),
        )

    private class TestRuntimeExecutor(
        private val delegate: ConversationRuntimeExecutor,
        val identity: ConversationRuntimeExecutorIdentity,
    ) {
        fun start() = delegate.start()

        fun stop() = delegate.stop()
    }

    private class TestRuntimeWorkerNode(
        private val worker: ConversationRuntimeWorker,
        private val executor: TestRuntimeExecutor,
    ) {
        fun start() {
            worker.start()
            executor.start()
        }

        fun stop() {
            executor.stop()
            worker.stop()
        }
    }

    private class TestAiConfigurationService(
        private val refreshFailure: Throwable? = null,
    ) : AiConfigurationService {
        private val refreshStarted = CompletableDeferred<Unit>()

        override val snapshotFlow: StateFlow<AiCatalogSnapshot?> = MutableStateFlow(null)
        override val snapshot: AiCatalogSnapshot
            get() = error("AI catalog snapshot is outside this test")

        override suspend fun replaceCatalog(
            catalog: AiCatalog,
            expectedRevision: Long,
            secretMutations: List<AiCatalogSecretMutation>,
        ): AiCatalogSnapshot = error("AI catalog replacement is outside this test")

        override suspend fun reload(): AiCatalogSnapshot =
            error("AI catalog reload is outside this test")

        override suspend fun refreshIfChanged() {
            refreshStarted.complete(Unit)
            refreshFailure?.let { throw it }
        }

        override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime =
            error("AI runtime resolution is outside this test")

        suspend fun awaitRefresh() {
            withTimeout(TEST_EVENT_TIMEOUT_MS) {
                refreshStarted.await()
            }
        }
    }

    private class StaticWorkspaceDomainService(
        private val workerId: String,
        private val workspaceMounts: Map<WorkspaceMount.Id, Workspace.Id> = emptyMap(),
    ) : WorkspaceDomainService {
        private val now = Clock.System.now()

        override suspend fun createFilesystemWorkspace(
            projectId: Project.Id,
            name: String,
            id: Workspace.Id?,
        ): Workspace = error("Workspace creation is outside this test")

        override suspend fun createAndMountFilesystemWorkspace(
            projectId: Project.Id,
            name: String,
            workerId: String,
            rootPath: String,
            workspaceId: Workspace.Id?,
            mountId: WorkspaceMount.Id?,
        ): WorkspaceExecutionContext = error("Workspace creation is outside this test")

        override suspend fun attachFilesystem(
            workspaceId: Workspace.Id,
            workerId: String,
            rootPath: String,
            mountId: WorkspaceMount.Id?,
        ): WorkspaceExecutionContext = error("Workspace attachment is outside this test")

        override suspend fun findById(id: Workspace.Id): Workspace? = null

        override suspend fun findByProject(projectId: Project.Id): List<Workspace> = emptyList()

        override suspend fun findMount(id: WorkspaceMount.Id): WorkspaceMount? =
            mountsFor(workerId).singleOrNull { it.id == id }

        override suspend fun findMount(
            workspaceId: Workspace.Id,
            workerId: String,
        ): WorkspaceMount? =
            mountsFor(workerId).singleOrNull { it.workspaceId == workspaceId }

        override suspend fun findMounts(workspaceId: Workspace.Id): List<WorkspaceMount> =
            mountsFor(workerId).filter { it.workspaceId == workspaceId }

        override suspend fun findMountsByWorker(workerId: String): List<WorkspaceMount> =
            mountsFor(workerId)

        override suspend fun findByWorkerPath(
            projectId: Project.Id,
            workerId: String,
            rootPath: String,
        ): WorkspaceExecutionContext? = null

        override suspend fun resolveExecution(
            mountId: WorkspaceMount.Id,
        ): WorkspaceExecutionContext = error("Workspace resolution is outside this test")

        override suspend fun resolveRuntime(
            workspaceId: Workspace.Id,
            workerId: String,
        ): RuntimeEnvironmentContext.WorkspaceBound =
            error("Workspace runtime resolution is outside this test")

        private fun mountsFor(requestedWorkerId: String): List<WorkspaceMount> =
            if (requestedWorkerId == workerId) {
                workspaceMounts.map { (mountId, workspaceId) ->
                    WorkspaceMount(
                        id = mountId,
                        workspaceId = workspaceId,
                        workerId = workerId,
                        rootPath = "/workspace/${workspaceId.value}",
                        createdAt = now,
                        updatedAt = now,
                    )
                }
            } else {
                emptyList()
            }
    }

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

private object EmptyAiToolProvider : AiToolProvider {
    override fun getTools() = emptyList<AiToolCallback>()
}

private class MutableAiToolProvider : AiToolProvider {
    @Volatile
    var enabled = false

    override fun getTools(): List<AiToolCallback> =
        if (enabled) listOf(DynamicTool) else emptyList()

    private object DynamicTool : AiToolCallback {
        override val definition = AiToolDefinition(
            name = "dynamic_tool",
            description = "Dynamic test tool",
            inputSchema = """{"type":"object","properties":{}}""",
        )

        override fun call(
            toolInput: String,
            context: ToolExecutionContext?,
        ): String = "ok"
    }
}
