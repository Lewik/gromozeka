package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSecretMutation
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeExecutorDescriptor
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskIncident
import com.gromozeka.domain.service.ConversationRuntimeTaskOutcome
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.ConversationRuntimeServerSessionId
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.WorkspaceDomainService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_EVENT_TIMEOUT_MS = 10_000L

class ConversationRuntimeDispatcherTest {
    private val conversationId = Conversation.Id("conversation-runtime-dispatcher-test")
    private val agentDefinitionId = AgentDefinition.Id("agent-1")

    @Test
    fun `dispatcher starts idle conversation for after tool result message`() = runBlocking {
        val harness = dispatcherHarness()
        try {
            val message = userMessage("message-1")
            val actorUserId = User.Id("user-1")

            assertTrue(
                harness.dispatcher.enqueueMessage(
                    conversationId = conversationId,
                    userMessage = message,
                    agentDefinitionId = agentDefinitionId,
                    placement = QueuedMessagePlacement.AFTER_TOOL_RESULT,
                    actorUserId = actorUserId,
                )
            )

            val startedTask = harness.runner.awaitStarted()
            assertEquals(message.id.value, startedTask.id.value)
            assertEquals(QueuedMessagePlacement.END_OF_TURN, startedTask.placement)
            assertEquals(actorUserId, startedTask.actorUserId)

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
    fun `executor drains durable ready work at startup`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val runner = ControllableTaskRunner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val task = runtimeUserTurnTask(userMessage("startup-message"))
        val executor = runtimeExecutor(
            coordinator = coordinator,
            eventBus = eventBus,
            runner = runner,
            descriptor = serverExecutorDescriptor(
                sessionId = "startup-server",
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
        )

        try {
            assertTrue(coordinator.submit(task))
            executor.start()
            assertEquals(task.id, runner.awaitStarted().id)
            runner.releaseCurrentTask()
            waitUntil { coordinator.find(conversationId) == null }
        } finally {
            executor.stop()
            scope.cancel()
        }
    }

    @Test
    fun `executor startup records delivery failure for old unstarted Server assignment`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val runner = ControllableTaskRunner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val oldExecutor = ConversationRuntimeExecutorIdentity.Server(
            ConversationRuntimeServerSessionId("old-server")
        )
        val task = runtimeUserTurnTask(userMessage("unstarted-message"))
        assertTrue(coordinator.submit(task))
        assertEquals(
            task,
            coordinator.claimDeliveredTask(
                conversationId = conversationId,
                taskId = task.id,
                executor = oldExecutor,
                executorCapabilities = task.requirements.capabilities,
                workerWorkspaceMountIds = emptySet(),
            ),
        )
        val executor = runtimeExecutor(
            coordinator = coordinator,
            eventBus = eventBus,
            runner = runner,
            descriptor = serverExecutorDescriptor(
                sessionId = "new-server",
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
        )

        try {
            executor.start()
            waitUntil { coordinator.findTaskIncident(conversationId, task.id) != null }
            assertEquals(
                ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED,
                coordinator.findTaskIncident(conversationId, task.id)?.kind,
            )
        } finally {
            executor.stop()
            scope.cancel()
        }
    }

    @Test
    fun `executor startup records unknown outcome for old started Server assignment`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val runner = ControllableTaskRunner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val oldExecutor = ConversationRuntimeExecutorIdentity.Server(
            ConversationRuntimeServerSessionId("old-server")
        )
        val task = runtimeUserTurnTask(userMessage("started-message"))
        assertTrue(coordinator.submit(task))
        assertNotNull(
            coordinator.claimDeliveredTask(
                conversationId = conversationId,
                taskId = task.id,
                executor = oldExecutor,
                executorCapabilities = task.requirements.capabilities,
                workerWorkspaceMountIds = emptySet(),
            ),
        )
        assertTrue(
            coordinator.markActiveTaskStarted(
                conversationId = conversationId,
                taskId = task.id,
                executor = oldExecutor,
                startedAt = Clock.System.now(),
            )
        )
        val executor = runtimeExecutor(
            coordinator = coordinator,
            eventBus = eventBus,
            runner = runner,
            descriptor = serverExecutorDescriptor(
                sessionId = "new-server",
                capabilities = setOf(
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
            ),
            scope = scope,
        )

        try {
            executor.start()
            waitUntil { coordinator.findTaskIncident(conversationId, task.id) != null }
            assertEquals(
                ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN,
                coordinator.findTaskIncident(conversationId, task.id)?.kind,
            )
        } finally {
            executor.stop()
            scope.cancel()
        }
    }

    @Test
    fun `executor records delivery failure without running task when AI configuration refresh fails`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val runner = ControllableTaskRunner()
        val aiConfigurationService = TestAiConfigurationService(
            refreshFailure = IllegalStateException("AI catalog unavailable")
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = runtimeExecutor(
            coordinator = coordinator,
            eventBus = eventBus,
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

            aiConfigurationService.awaitRefresh()
            waitUntil { coordinator.snapshot(conversationId).incidents.isNotEmpty() }

            assertNull(withTimeoutOrNull(250) { runner.awaitStarted() })
            val snapshot = coordinator.snapshot(conversationId)
            assertEquals(
                ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED,
                snapshot.incidents.single { it.task.id == task.id }.kind,
            )
            assertNull(snapshot.activeTask)
        } finally {
            executor.stop()
            scope.cancel()
        }
    }

    @Test
    fun `executor cancellation records unknown outcome without retrying claimed task`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val runner = ControllableTaskRunner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executor = runtimeExecutor(
            coordinator = coordinator,
            eventBus = eventBus,
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
    fun `runtime stays Server-owned while preserving exact workspace execution target`() = runBlocking {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val eventBus = InMemoryConversationRuntimeEventBus()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workspaceId = Workspace.Id("workspace-1")
        val workspaceMountId = WorkspaceMount.Id("mount-1")
        val workspaceWorkerId = ConversationRuntimeWorkerId("workspace-worker")
        val toolTarget = ConversationRuntimeTaskTarget.Worker(
            workerId = workspaceWorkerId,
            workspaceMountId = workspaceMountId,
        )
        val firstMessage = userMessage("message-1")
        val rootTask = runtimeUserTurnTask(firstMessage)
        val llmTask1 = runtimeLlmTask(rootTask, firstMessage.id, iteration = 1)
        val toolTask = runtimeToolTask(llmTask1, firstMessage.id, iteration = 1, target = toolTarget)
        val toolResultProcessingTask = runtimeToolResultProcessingTask(toolTask, firstMessage.id, iteration = 1)
        val llmTask2 = runtimeLlmTask(toolResultProcessingTask, firstMessage.id, iteration = 2)
        val executionOrder = Channel<Pair<ConversationRuntimeTask, ConversationRuntimeExecutorIdentity>>(Channel.UNLIMITED)
        val serverRunner = ChainedTaskRunner(executionOrder) { task ->
            when (task.id) {
                ConversationRuntimeTask.Id(firstMessage.id.value) -> ConversationRuntimeTaskOutcome.Continue(llmTask1)
                llmTask1.id -> ConversationRuntimeTaskOutcome.Continue(toolTask)
                toolTask.id -> ConversationRuntimeTaskOutcome.Continue(toolResultProcessingTask)
                toolResultProcessingTask.id -> ConversationRuntimeTaskOutcome.Continue(llmTask2)
                else -> ConversationRuntimeTaskOutcome.CompleteTurn
            }
        }
        val dispatcher = ConversationRuntimeDispatcher(
            runtimeCoordinator = coordinator,
            runtimeEventBus = eventBus,
        )
        val serverExecutor = runtimeExecutor(
            coordinator = coordinator,
            eventBus = eventBus,
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

        try {
            serverExecutor.start()
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
                listOf("server", "server", "server", "server", "server"),
                executions.map { (_, executor) ->
                    when (executor) {
                        is ConversationRuntimeExecutorIdentity.Server -> "server"
                        is ConversationRuntimeExecutorIdentity.Worker -> executor.identity.workerId.value
                    }
                },
            )
            assertEquals(
                toolTarget,
                (executions[2].first.payload as ConversationRuntimeTask.Payload.ToolExecution).executionTarget,
            )
            waitUntil { coordinator.find(conversationId) == null }
        } finally {
            serverExecutor.stop()
            scope.cancel()
        }
    }

    @Test
    fun `dispatcher runs internal continuation before queued user turn`() = runBlocking {
        val firstMessage = userMessage("message-1")
        val secondMessage = userMessage("message-2")
        val rootTask = runtimeUserTurnTask(firstMessage)
        val llmTask = runtimeLlmTask(rootTask, firstMessage.id, iteration = 1)
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val runner = ControllableTaskRunner { startedTask ->
            if (startedTask.id == ConversationRuntimeTask.Id(firstMessage.id.value)) {
                ConversationRuntimeTaskOutcome.Continue(llmTask)
            } else {
                ConversationRuntimeTaskOutcome.CompleteTurn
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

    @Test
    fun `dispatcher cannot let asynchronous memory completion overtake active turn continuation`() = runBlocking {
        val userMessage = userMessage("message-with-asynchronous-memory")
        val rootTask = runtimeUserTurnTask(userMessage)
        val llmTask = runtimeLlmTask(rootTask, userMessage.id, iteration = 1)
        val runner = ControllableTaskRunner { startedTask ->
            if (startedTask.id == rootTask.id) {
                ConversationRuntimeTaskOutcome.Continue(llmTask)
            } else {
                ConversationRuntimeTaskOutcome.CompleteTurn
            }
        }
        val harness = dispatcherHarness(
            runner = runner,
            executorCapabilities = setOf(
                ConversationRuntimeCapability.CONVERSATION_TURN,
                ConversationRuntimeCapability.MEMORY_PIPELINE,
                ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
            ),
        )
        try {
            assertTrue(harness.dispatcher.submitMessage(conversationId, userMessage, agentDefinitionId))
            assertEquals(rootTask.id, harness.runner.awaitStarted().id)
            assertTrue(
                harness.dispatcher.submitMemoryRunCompletion(
                    conversationId = conversationId,
                    runId = MemoryRun.Id("memory-run-1"),
                    agentDefinitionId = agentDefinitionId,
                    statusToolName = "memory_run_status",
                )
            )

            harness.runner.releaseCurrentTask()

            assertEquals(llmTask.id, harness.runner.awaitStarted().id)
            harness.runner.releaseCurrentTask()

            assertEquals(
                ConversationRuntimeTask.Id("memory-run-1:conversation-delivery"),
                harness.runner.awaitStarted().id,
            )
            harness.runner.releaseCurrentTask()
            waitUntil { harness.coordinator.find(conversationId) == null }
        } finally {
            harness.close()
        }
    }

    private fun dispatcherHarness(
        coordinator: InMemoryConversationRuntimeCoordinator = InMemoryConversationRuntimeCoordinator(),
        eventBus: InMemoryConversationRuntimeEventBus = InMemoryConversationRuntimeEventBus(),
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
        )
        val executor = runtimeExecutor(
            coordinator = coordinator,
            eventBus = eventBus,
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
        parentTask: ConversationRuntimeTask,
        rootUserMessageId: Conversation.Message.Id,
        iteration: Int,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${rootUserMessageId.value}:llm:$iteration"),
            conversationId = conversationId,
            turnId = parentTask.turnId,
            parentTaskId = parentTask.id,
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
        parentTask: ConversationRuntimeTask,
        rootUserMessageId: Conversation.Message.Id,
        iteration: Int,
        target: ConversationRuntimeTaskTarget,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${rootUserMessageId.value}:tools:$iteration"),
            conversationId = conversationId,
            turnId = parentTask.turnId,
            parentTaskId = parentTask.id,
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
                executionTarget = target,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "conversation:${conversationId.value}:runtime:${rootUserMessageId.value}:tools:$iteration",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
                target = ConversationRuntimeTaskTarget.Server,
            ),
            createdAt = Clock.System.now(),
        )

    private fun runtimeToolResultProcessingTask(
        parentTask: ConversationRuntimeTask,
        rootUserMessageId: Conversation.Message.Id,
        iteration: Int,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${rootUserMessageId.value}:tool-result-processing:$iteration"),
            conversationId = conversationId,
            turnId = parentTask.turnId,
            parentTaskId = parentTask.id,
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
        private val outcomeFor: suspend (ConversationRuntimeTask) -> ConversationRuntimeTaskOutcome = {
            ConversationRuntimeTaskOutcome.CompleteTurn
        },
    ) : ConversationRuntimeTaskRunner {
        private val startedTasks = Channel<ConversationRuntimeTask>(Channel.UNLIMITED)
        private val releases = Channel<Unit>(Channel.UNLIMITED)

        override suspend fun runRuntimeTask(
            task: ConversationRuntimeTask,
            executor: ConversationRuntimeExecutorIdentity,
            emitMessage: suspend (Conversation.Message) -> Unit,
        ): ConversationRuntimeTaskOutcome {
            startedTasks.send(task)
            releases.receive()
            emitMessage(
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
            return outcomeFor(task)
        }

        suspend fun awaitStarted(): ConversationRuntimeTask =
            withTimeout(TEST_EVENT_TIMEOUT_MS) { startedTasks.receive() }

        suspend fun releaseCurrentTask() {
            releases.send(Unit)
        }
    }

    private class ChainedTaskRunner(
        private val executionOrder: Channel<Pair<ConversationRuntimeTask, ConversationRuntimeExecutorIdentity>>,
        private val outcomeFor: suspend (ConversationRuntimeTask) -> ConversationRuntimeTaskOutcome,
    ) : ConversationRuntimeTaskRunner {
        override suspend fun runRuntimeTask(
            task: ConversationRuntimeTask,
            executor: ConversationRuntimeExecutorIdentity,
            emitMessage: suspend (Conversation.Message) -> Unit,
        ): ConversationRuntimeTaskOutcome {
            executionOrder.send(task to executor)
            return outcomeFor(task)
        }
    }

    private fun runtimeExecutor(
        coordinator: InMemoryConversationRuntimeCoordinator,
        eventBus: InMemoryConversationRuntimeEventBus,
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
                workspaceService = StaticWorkspaceDomainService(workspaceWorkerId, workspaceMounts),
                aiConfigurationService = aiConfigurationService,
                taskRunnerProvider = objectProvider(runner),
                descriptor = descriptor,
                parentScope = scope,
            ),
            identity = descriptor.identity,
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

}
