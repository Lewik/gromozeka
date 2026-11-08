package com.gromozeka.server

import com.gromozeka.application.service.*
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class BackgroundActivityOriginRecorderTest {
    private val now = Instant.fromEpochMilliseconds(1_000)
    private val alice = User.Id("alice")
    private val bob = User.Id("bob")
    private val agent = AgentDefinition.Id("agent")
    private val worker = ConversationRuntimeWorkerId("worker")
    private val mount = WorkspaceMount.Id("mount")
    private val json = Json { encodeDefaults = true }
    private val conversation = Conversation(Conversation.Id("conversation"), Project.Id("project"),
        setOf(Conversation.Participant.User(alice), Conversation.Participant.User(bob), Conversation.Participant.Agent(agent)),
        currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now)

    @Test
    fun `initial admission preserves creating user before final response and through continuation`(): Unit = runBlocking {
        val f = fixture()
        val task = command("command")
        val call = call("create", "grz_execute_command", buildJsonObject {
            put("command", "gh api user")
            put("secret_env", buildJsonObject { put("GH_TOKEN", "secret://token") })
        })
        val requestId = f.register(alice, call, task)
        assertFailsWith<WorkerRequestPendingException> { f.requests.await(requestId, 1) }
        val batch = f.completions.prepareBatch(conversation.id, "command-task:${task.id.value}")
        assertEquals(alice, batch.actorUserId)
        val restarted = WorkerRequestService(f.requestsRepository, WorkerRequestAuthorization {})
        restarted.accept(worker, response(requestId, call, task.id.value))
        val outcome = assertIs<ConversationRuntimeTaskOutcome.Continue>(runBackground(f, batch))
        assertEquals(alice, outcome.nextTask.actorUserId)
        val secrets = mock<NamedSecretRepository>()
        Mockito.`when`(secrets.find(alice, "token")).thenReturn(StoredNamedSecret(
            NamedSecret(NamedSecret.Id("secret-a"), alice, "token", "test", now, now), "alice-test-value"))
        Mockito.`when`(secrets.find(bob, "token")).thenReturn(StoredNamedSecret(
            NamedSecret(NamedSecret.Id("secret-b"), bob, "token", "test", now, now), "bob-test-value"))
        val resolved = ToolSecretResolutionService(NamedSecretApplicationService(secrets, mock<SecurityAuditRecorder>()))
            .resolve(outcome.nextTask.actorUserId, listOf(call))
        assertEquals("alice-test-value", resolved.getValue(call.id.value).getValue("token"))
        Mockito.verify(secrets, Mockito.never()).find(bob, "token")
    }

    @Test
    fun `monitor uses its own creator and batches never mix users of the same agent`(): Unit = runBlocking {
        val f = fixture()
        val task = command("command")
        val create = call("create", "grz_execute_command", buildJsonObject { put("command", "echo output") })
        f.requests.accept(worker, response(f.register(alice, create, task), create, task.id.value))
        val observe = call("observe", "grz_monitor_command", buildJsonObject { put("task_id", task.id.value); put("filter_command", "cat") })
        val requestId = f.submit(bob, observe)
        val monitor = monitor(task.id).copy(launchReference = BackgroundActivityLaunchReference(requestId, observe.id.value))
        f.recorder.admit(monitor)
        f.coordinator.synchronizeCommandMonitor(monitor, listOf(CommandMonitorEvent(CommandMonitorEvent.Id("event"),
            conversation.id, monitor.id, 0, 5, BinaryContent.fromText("event"), false, now, true)))
        assertNull(f.requestsRepository.find(requestId)?.response)

        val first = f.completions.prepareBatch(conversation.id, "command-task:${task.id.value}")
        assertEquals(alice, first.actorUserId)
        assertEquals(listOf(task.id), first.commandTasks.map { it.id })
        assertTrue(first.monitorDeliveries.isEmpty())
        f.completions.markDelivered(first, now)
        val second = f.completions.prepareBatch(conversation.id, "command-monitor-event:event")
        assertEquals(bob, second.actorUserId)
        assertEquals(listOf(monitor.id), second.monitorDeliveries.map { it.monitor.id })
        assertEquals(bob, assertIs<ConversationRuntimeTaskOutcome.Continue>(runBackground(f, second)).nextTask.actorUserId)
    }

    @Test
    fun `queued monitor source is not replaced by an older activity of another creator`(): Unit = runBlocking {
        val f = fixture()
        val task = command("older-command")
        val create = call("create", "grz_execute_command", buildJsonObject { put("command", "echo output") })
        f.requests.accept(worker, response(f.register(alice, create, task), create, task.id.value))
        val observe = call("observe", "grz_monitor_command", buildJsonObject { put("task_id", task.id.value); put("filter_command", "cat") })
        val requestId = f.submit(bob, observe)
        val monitor = monitor(task.id).copy(launchReference = BackgroundActivityLaunchReference(requestId, observe.id.value))
        f.recorder.admit(monitor)
        val event = CommandMonitorEvent(CommandMonitorEvent.Id("queued-event"), conversation.id, monitor.id,
            0, 5, BinaryContent.fromText("event"), false, Instant.fromEpochSeconds(2), true)
        f.coordinator.synchronizeCommandMonitor(monitor, listOf(event))

        val batch = f.completions.prepareBatch(conversation.id, "command-monitor-event:${event.id.value}")
        val outcome = assertIs<ConversationRuntimeTaskOutcome.Continue>(runBackground(f, batch))

        assertEquals(bob, outcome.nextTask.actorUserId)
        assertTrue(batch.commandTasks.isEmpty())
        assertNull(f.coordinator.findCommandTask(conversation.id, task.id)?.completionNotificationDeliveredAt)
        assertNotNull(f.coordinator.findCommandMonitorEvents(conversation.id, monitor.id).single().deliveredAt)
    }

    @Test
    fun `missing provenance never borrows a participant or resumes anonymously`(): Unit = runBlocking {
        val f = fixture()
        f.coordinator.upsertCommandTask(command("legacy"))
        val batch = f.completions.prepareBatch(conversation.id, "command-task:legacy")
        assertNull(batch.actorUserId)
        assertFalse(f.completions.canContinue(batch, conversation))
        assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(runBackground(f, batch))
    }

    @Test
    fun `revoked continuation permission delivers output but does not call the model`(): Unit = runBlocking {
        val f = fixture()
        val task = command("command")
        val create = call("create", "grz_execute_command", buildJsonObject { put("command", "echo output") })
        f.requests.accept(worker, response(f.register(alice, create, task), create, task.id.value))
        f.allowed = false
        val batch = f.completions.prepareBatch(conversation.id, "command-task:${task.id.value}")
        assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(runBackground(f, batch))
        assertNotNull(f.coordinator.findCommandTask(conversation.id, task.id)?.completionNotificationDeliveredAt)
    }

    @Test
    fun `current account AI permission membership and project write access are all required`(): Unit = runBlocking {
        val users = mock<UserDirectoryService>()
        val projects = mock<ProjectAccessService>()
        val user = User(alice, displayName = "Alice", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
        Mockito.`when`(users.findActiveById(alice)).thenReturn(user)
        Mockito.`when`(projects.can(alice, conversation.projectId, ProjectPermission.WRITE)).thenReturn(true)
        val policy = DefaultBackgroundActivityContinuationPolicy(users, projects)
        assertTrue(policy.allowed(alice, conversation))
        Mockito.`when`(users.findActiveById(alice)).thenReturn(user.copy(aiAllowed = false))
        assertFalse(policy.allowed(alice, conversation))
        Mockito.`when`(users.findActiveById(alice)).thenReturn(null)
        assertFalse(policy.allowed(alice, conversation))
        Mockito.`when`(users.findActiveById(alice)).thenReturn(user)
        Mockito.`when`(projects.can(alice, conversation.projectId, ProjectPermission.WRITE)).thenReturn(false)
        assertFalse(policy.allowed(alice, conversation))
        Mockito.`when`(projects.can(alice, conversation.projectId, ProjectPermission.WRITE)).thenReturn(true)
        assertFalse(policy.allowed(alice, conversation.copy(participants = conversation.participants - Conversation.Participant.User(alice))))
    }

    @Test
    fun `duplicate response cannot change binding and another request cannot steal an activity`(): Unit = runBlocking {
        val f = fixture()
        val task = command("command")
        val create = call("create", "grz_execute_command", buildJsonObject { put("command", "echo output") })
        val id = f.register(alice, create, task)
        f.requests.accept(worker, response(id, create, task.id.value))
        // Even a different duplicate body must be ignored in favor of the saved response.
        f.requests.accept(worker, response(id, create, "forged"))
        assertEquals(1, f.origins.records.size)
        val other = f.submit(bob, create.copy(id = ContentItem.ToolCall.Id("other-call")))
        assertFailsWith<IllegalArgumentException> {
            f.recorder.admit(task.copy(launchReference = BackgroundActivityLaunchReference(other, "other-call")))
        }
        assertEquals(alice, f.origins.records.values.single().actorUserId)
    }

    @Test
    fun `worker result cannot bind a command outside request execution scope`(): Unit = runBlocking {
        val f = fixture()
        val task = command("foreign").copy(workspaceMountId = WorkspaceMount.Id("another-mount"))
        val create = call("create", "grz_execute_command", buildJsonObject { put("command", "echo output") })
        assertFailsWith<IllegalArgumentException> {
            f.recorder.admit(task.copy(command = "echo output",
                launchReference = BackgroundActivityLaunchReference(f.submit(alice, create), create.id.value)))
        }
        assertTrue(f.origins.records.isEmpty())
    }

    @Test
    fun `admitted origin survives request deletion but a completed new launch is rejected`(): Unit = runBlocking {
        val f = fixture()
        val create = call("create", "grz_execute_command", buildJsonObject { put("command", "echo output") })
        val id = f.register(alice, create, command("command"))
        val task = requireNotNull(f.coordinator.findCommandTask(conversation.id, CommandTask.Id("command")))
        f.requestsRepository.records.remove(id)
        f.recorder.admit(task)
        assertEquals(alice, f.completions.prepareBatch(conversation.id, "command-task:${task.id.value}").actorUserId)
        val late = f.submit(bob, create)
        f.requests.accept(worker, response(late, create, "late"))
        assertFailsWith<IllegalArgumentException> {
            f.recorder.admit(command("late").copy(command = "echo output", launchReference = BackgroundActivityLaunchReference(late, create.id.value)))
        }
        assertEquals(1, f.origins.records.size)
    }

    private suspend fun fixture(): Fixture = Fixture().also {
        Mockito.`when`(it.conversations.findById(conversation.id)).thenReturn(conversation)
    }

    private inner class Fixture {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val conversations = mock<ConversationRepository>()
        val requestsRepository = TestWorkerRequestRepository()
        val requests = WorkerRequestService(requestsRepository, WorkerRequestAuthorization {})
        val origins = Origins(requestsRepository)
        val recorder = BackgroundActivityOriginRecorder(origins, requests, conversations)
        var allowed = true
        val completions = BackgroundActivityCompletionApplicationService(coordinator, origins,
            BackgroundActivityContinuationPolicy { _, _ -> allowed })

        suspend fun register(actor: User.Id, call: ContentItem.ToolCall, task: CommandTask): String {
            val id = submit(actor, call)
            val registered = task.copy(
                command = call.call.input.jsonObject.getValue("command").jsonPrimitive.content,
                launchReference = BackgroundActivityLaunchReference(id, call.id.value),
            )
            recorder.admit(registered)
            coordinator.upsertCommandTask(registered)
            return id
        }

        suspend fun submit(actor: User.Id, call: ContentItem.ToolCall): String {
            val payload = WorkerToolExecutionRequest(ConversationRuntimeTaskTarget.Worker(worker, mount), listOf(call), mapOf(
                TOOL_CONTEXT_USER_ID to actor.value, TOOL_CONTEXT_PROJECT_ID to conversation.projectId.value,
                TOOL_CONTEXT_CONVERSATION_ID to conversation.id.value, TOOL_CONTEXT_AGENT_DEFINITION_ID to agent.value,
            ))
            val id = requests.submit(worker, WorkerGatewayOperation.TOOL_EXECUTION, json.encodeToString(payload).encodeToByteArray(),
                WorkerRequestPolicy(), actor, conversation.projectId)
            requestsRepository.markDispatched(id, now)
            return id
        }
    }

    private suspend fun runBackground(f: Fixture, batch: BackgroundActivityCompletionApplicationService.Batch): ConversationRuntimeTaskOutcome {
        val sourceKey = batch.commandTasks.firstOrNull()?.let { "command-task:${it.id.value}" }
            ?: batch.monitorDeliveries.first().let { delivery ->
                delivery.events.firstOrNull()?.let { "command-monitor-event:${it.id.value}" }
                    ?: "command-monitor-terminal:${delivery.monitor.id.value}:${delivery.monitor.completedAt}"
            }
        val task = ConversationRuntimeTask(ConversationRuntimeTask.Id("background"), conversation.id,
            payload = ConversationRuntimeTask.Payload.BackgroundActivityCompletion(sourceKey),
            placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = "background",
            requirements = ConversationRuntimeTaskRequirements(capabilities = setOf(ConversationRuntimeCapability.CONVERSATION_TURN), target = ConversationRuntimeTaskTarget.Server),
            createdAt = now)
        val executor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server"))
        val coordinator = mock<ConversationRuntimeCoordinator>()
        Mockito.`when`(coordinator.confirmActiveTaskOwner(conversation.id, task.id, executor)).thenReturn(true)
        val conversations = mock<ConversationDomainService>()
        Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation)
        val history = mock<ConversationHistoryReadService>()
        val artifacts = mock<ConversationArtifactApplicationService>()
        batch.messages.forEach { message ->
            Mockito.`when`(history.containsMessage(conversation.id, message.id)).thenReturn(false)
            val results = message.content.filterIsInstance<ContentItem.ToolResult>()
            Mockito.`when`(artifacts.persistAndCommitToolResults(conversation, batch.actorUserId, results)).thenReturn(results)
        }
        val engine = ConversationEngineService(
            aiRuntimeProvider = mock(), aiToolProvider = mock(), agentDomainService = mock(), agentPromptAssemblyService = mock(),
            toolApprovalService = mock(), toolExecutionTaskService = mock(), conversationService = conversations, conversationHistory = history,
            conversationMessageAppender = mock(), historyMutationExecutor = mock(), memoryApplicationService = mock(),
            memoryToolApplicationService = mock(), backgroundActivityCompletionApplicationService = f.completions,
            memoryMessageRoutingApplicationService = mock(), toolCallSequenceFixerService = mock(), settingsProvider = mock(),
            aiConfigurationProvider = mock(), runtimeCoordinator = coordinator, runtimeStateSyncService = mock(),
            distributedToolCatalog = mock(), agentSkillRuntimeCatalogService = mock(), aiToolRuntimeCatalogService = mock(),
            aiToolCapabilityCatalogService = mock(), toolRoutingService = mock(), artifactService = artifacts,
            activeGenerationPublisher = mock(), messageTemporalContextService = mock(), stickyMessageInstructionService = mock(),
            pendingSecretRevealService = mock(), suggestedRepliesGenerationService = mock(),
            conversationPins = mock(),
        )
        val outcome = engine.runRuntimeTask(task, executor) {}
        Mockito.verify(conversations, Mockito.never()).loadCurrentMessages(conversation.id)
        return outcome
    }

    private fun call(id: String, name: String, input: JsonObject) = ContentItem.ToolCall(
        ContentItem.ToolCall.Id(id), ContentItem.ToolCall.Data(name, input), state = Conversation.Message.BlockState.COMPLETE)

    private fun response(requestId: String, call: ContentItem.ToolCall, activityId: String): WorkerGatewayMessage.Response {
        val field = if (call.call.name == "grz_execute_command") "task_id" else "monitor_id"
        val result = ContentItem.ToolResult(toolUseId = call.id, toolName = call.call.name,
            result = listOf(ContentItem.ToolResult.Data.Text(buildJsonObject { put(field, activityId) }.toString())), isError = false)
        return WorkerGatewayMessage.Response(requestId, WorkerGatewayMessage.Response.Status.SUCCEEDED,
            json.encodeToString(WorkerToolExecutionResponse(listOf(result), false)).encodeToByteArray())
    }

    private fun command(id: String) = CommandTask(CommandTask.Id(id), conversation.id, worker, mount, agent,
        command = "curl secret://token", workingDirectory = "/tmp", status = CommandTask.Status.COMPLETED,
        processId = 1, processStartedAt = now, outputFile = "/tmp/$id.log", outputBytes = 2,
        createdAt = now, updatedAt = now, completedAt = now, completionNotificationRequestedAt = now,
        terminalOutputStartByte = 0, terminalOutputContent = BinaryContent.fromText("ok"))

    private fun monitor(source: CommandTask.Id) = CommandMonitor(CommandMonitor.Id("monitor"), conversation.id, source, worker, mount, agent,
        filterCommand = "cat", mode = CommandMonitor.Mode.CONTINUOUS, startFrom = CommandMonitor.StartFrom.BEGINNING,
        status = CommandMonitor.Status.WORKING, sourceOutputCursor = 0, processId = 2, processStartedAt = now,
        outputFile = "/tmp/monitor.log", errorFile = "/tmp/monitor.err", outputBytes = 5, eventOutputCursor = 5,
        eventCount = 1, createdAt = now, updatedAt = now)

    private class Origins(private val requests: TestWorkerRequestRepository) : BackgroundActivityOriginRepository {
        val records = mutableMapOf<BackgroundActivityOrigin.Key, BackgroundActivityOrigin>()
        override suspend fun bindWhileRequestPending(origin: BackgroundActivityOrigin, at: Instant) {
            records[origin.key]?.let { require(it == origin); return }
            val request = requireNotNull(requests.find(origin.workerRequestId))
            require(request.completedAt == null && request.dispatchedAt != null && request.cancelRequestedAt == null &&
                request.actorUserId == origin.actorUserId && request.workerId == origin.workerId &&
                requireNotNull(request.executionDeadline) > at)
            check(records.values.none { it.workerRequestId == origin.workerRequestId && it.toolCallId == origin.toolCallId && it.kind == origin.kind && it != origin })
            records[origin.key] = origin
        }
        override suspend fun find(conversationId: Conversation.Id, keys: Set<BackgroundActivityOrigin.Key>) =
            records.filter { it.key in keys && it.value.conversationId == conversationId }
    }

    private inline fun <reified T : Any> mock(): T = Mockito.mock(T::class.java)
}
