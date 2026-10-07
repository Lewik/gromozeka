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
    fun `late completion preserves creating user through real engine continuation and secret resolution`(): Unit = runBlocking {
        val f = fixture()
        val task = command("command")
        f.coordinator.upsertCommandTask(task)
        val call = call("create", "grz_execute_command", buildJsonObject { put("command", "curl secret://token") })
        val requestId = f.submit(alice, call)
        assertFailsWith<WorkerRequestPendingException> { f.requests.await(requestId, 1) }
        val restarted = WorkerRequestService(f.requestsRepository, WorkerRequestAuthorization {}, listOf(f.recorder))
        restarted.accept(worker, response(requestId, call, task.id.value))

        val batch = f.completions.prepareBatch(conversation.id)
        assertEquals(alice, batch.actorUserId)
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
        f.coordinator.upsertCommandTask(task)
        val create = call("create", "grz_execute_command", buildJsonObject { put("command", "echo output") })
        f.requests.accept(worker, response(f.submit(alice, create), create, task.id.value))
        val monitor = monitor(task.id)
        f.coordinator.synchronizeCommandMonitor(monitor, listOf(CommandMonitorEvent(CommandMonitorEvent.Id("event"),
            conversation.id, monitor.id, 0, 5, BinaryContent.fromText("event"), false, now, true)))
        val observe = call("observe", "grz_monitor_command", buildJsonObject { put("task_id", task.id.value); put("filter_command", "cat") })
        f.requests.accept(worker, response(f.submit(bob, observe), observe, monitor.id.value))

        val first = f.completions.prepareBatch(conversation.id)
        assertEquals(alice, first.actorUserId)
        assertEquals(listOf(task.id), first.commandTasks.map { it.id })
        assertTrue(first.monitorDeliveries.isEmpty())
        f.completions.markDelivered(first, now)
        val second = f.completions.prepareBatch(conversation.id)
        assertEquals(bob, second.actorUserId)
        assertEquals(listOf(monitor.id), second.monitorDeliveries.map { it.monitor.id })
        assertEquals(bob, assertIs<ConversationRuntimeTaskOutcome.Continue>(runBackground(f, second)).nextTask.actorUserId)
    }

    @Test
    fun `missing provenance never borrows a participant or resumes anonymously`(): Unit = runBlocking {
        val f = fixture()
        f.coordinator.upsertCommandTask(command("legacy"))
        val batch = f.completions.prepareBatch(conversation.id)
        assertNull(batch.actorUserId)
        assertFalse(f.completions.canContinue(batch, conversation))
        assertIs<ConversationRuntimeTaskOutcome.CompleteTurn>(runBackground(f, batch))
    }

    @Test
    fun `revoked continuation permission delivers output but does not call the model`(): Unit = runBlocking {
        val f = fixture()
        val task = command("command")
        f.coordinator.upsertCommandTask(task)
        val create = call("create", "grz_execute_command", buildJsonObject { put("command", "echo output") })
        f.requests.accept(worker, response(f.submit(alice, create), create, task.id.value))
        f.allowed = false
        val batch = f.completions.prepareBatch(conversation.id)
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
        f.coordinator.upsertCommandTask(task)
        val create = call("create", "grz_execute_command", buildJsonObject { put("command", "echo output") })
        val id = f.submit(alice, create)
        f.requests.accept(worker, response(id, create, task.id.value))
        // Even a different duplicate body must be ignored in favor of the saved response.
        f.requests.accept(worker, response(id, create, "forged"))
        assertEquals(1, f.origins.records.size)
        val other = f.submit(bob, create.copy(id = ContentItem.ToolCall.Id("other-call")))
        assertFailsWith<IllegalStateException> {
            f.requests.accept(worker, response(other, create.copy(id = ContentItem.ToolCall.Id("other-call")), task.id.value))
        }
        assertEquals(alice, f.origins.records.values.single().actorUserId)
    }

    @Test
    fun `worker result cannot bind a command outside request execution scope`(): Unit = runBlocking {
        val f = fixture()
        val task = command("foreign").copy(workspaceMountId = WorkspaceMount.Id("another-mount"))
        f.coordinator.upsertCommandTask(task)
        val create = call("create", "grz_execute_command", buildJsonObject { put("command", "echo output") })
        assertFailsWith<IllegalArgumentException> {
            f.requests.accept(worker, response(f.submit(alice, create), create, task.id.value))
        }
        assertTrue(f.origins.records.isEmpty())
    }

    private suspend fun fixture(): Fixture = Fixture().also {
        Mockito.`when`(it.conversations.findById(conversation.id)).thenReturn(conversation)
    }

    private inner class Fixture {
        val coordinator = InMemoryConversationRuntimeCoordinator()
        val conversations = mock<ConversationRepository>()
        val origins = Origins()
        val recorder = BackgroundActivityOriginRecorder(origins, coordinator, conversations)
        val requestsRepository = TestWorkerRequestRepository()
        val requests = WorkerRequestService(requestsRepository, WorkerRequestAuthorization {}, listOf(recorder))
        var allowed = true
        val completions = BackgroundActivityCompletionApplicationService(coordinator, origins,
            BackgroundActivityContinuationPolicy { _, _ -> allowed })

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
        val task = ConversationRuntimeTask(ConversationRuntimeTask.Id("background"), conversation.id,
            payload = ConversationRuntimeTask.Payload.BackgroundActivityCompletion("event"),
            placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = "background",
            requirements = ConversationRuntimeTaskRequirements(capabilities = setOf(ConversationRuntimeCapability.CONVERSATION_TURN), target = ConversationRuntimeTaskTarget.Server),
            createdAt = now)
        val executor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server"))
        val coordinator = mock<ConversationRuntimeCoordinator>()
        Mockito.`when`(coordinator.confirmActiveTaskOwner(conversation.id, task.id, executor)).thenReturn(true)
        val conversations = mock<ConversationDomainService>()
        Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation)
        Mockito.`when`(conversations.loadCurrentMessages(conversation.id)).thenReturn(emptyList())
        val artifacts = mock<ConversationArtifactApplicationService>()
        batch.messages.forEach { message ->
            val results = message.content.filterIsInstance<ContentItem.ToolResult>()
            Mockito.`when`(artifacts.persistAndCommitToolResults(conversation, batch.actorUserId, results)).thenReturn(results)
        }
        val engine = ConversationEngineService(
            aiRuntimeProvider = mock(), aiToolProvider = mock(), agentDomainService = mock(), agentPromptAssemblyService = mock(),
            toolApprovalService = mock(), toolExecutionTaskService = mock(), conversationService = conversations,
            conversationMessageAppender = mock(), historyMutationExecutor = mock(), memoryApplicationService = mock(),
            memoryToolApplicationService = mock(), backgroundActivityCompletionApplicationService = f.completions,
            memoryMessageRoutingApplicationService = mock(), toolCallSequenceFixerService = mock(), settingsProvider = mock(),
            aiConfigurationProvider = mock(), runtimeCoordinator = coordinator, runtimeStateSyncService = mock(),
            distributedToolCatalog = mock(), agentSkillRuntimeCatalogService = mock(), aiToolRuntimeCatalogService = mock(),
            aiToolCapabilityCatalogService = mock(), toolRoutingService = mock(), artifactService = artifacts,
            activeGenerationPublisher = mock(), messageTemporalContextService = mock(), stickyMessageInstructionService = mock(),
            pendingSecretRevealService = mock(), suggestedRepliesGenerationService = mock(),
        )
        return engine.runRuntimeTask(task, executor) {}
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

    private class Origins : BackgroundActivityOriginRepository {
        val records = mutableMapOf<BackgroundActivityOrigin.Key, BackgroundActivityOrigin>()
        override suspend fun bind(origin: BackgroundActivityOrigin) {
            check(records[origin.key]?.let { it == origin } != false) { "Origin cannot be reassigned" }
            check(records.values.none { it.workerRequestId == origin.workerRequestId && it.toolCallId == origin.toolCallId && it.kind == origin.kind && it != origin })
            records[origin.key] = origin
        }
        override suspend fun find(conversationId: Conversation.Id, keys: Set<BackgroundActivityOrigin.Key>) =
            records.filter { it.key in keys && it.value.conversationId == conversationId }
    }

    private inline fun <reified T : Any> mock(): T = Mockito.mock(T::class.java)
}
