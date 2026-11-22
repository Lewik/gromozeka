package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Clock

/** Native task/admission/catalog/resolver/ACL/executor/delivery; only storage and external resources are doubles. */
class AsyncToolNativePipelineTest {
    @Test fun `real source closes once then delivers an instant result at a safe point without waiting on its foreground owner`() = runBlocking {
        val f = Fixture()
        try {
            f.begin()
            val continuation = f.executeSource()
            assertEquals(0, f.effects.get())
            val accepted = f.operation()
            assertNotNull(accepted.acknowledgedAt)
            val ack = f.messages.single { it.id == accepted.invocation.origin.acknowledgementMessageId }
            assertEquals(1, ack.content.filterIsInstance<ContentItem.ToolResult>().size)
            assertFalse(assertIs<ConversationRuntimeTask.Payload.ToolResultProcessing>(continuation.nextTask.payload).returnDirect)
            assertTrue(f.coordinator.confirmActiveTaskOwner(f.conversation.id, f.task.id, f.executor))
            f.operations.reconcile(f.scope)
            f.awaitTerminal()
            assertEquals(1, f.effects.get())
            assertEquals(1, f.approvalCount)
            assertTrue(f.observedOrigin?.readOnly == true)
            assertNotEquals(f.catalogEntry.contractFingerprint, f.operation().invocation.contractFingerprint)
            assertEquals(f.rawFingerprint(), f.operation().invocation.contractFingerprint)
            assertTrue(f.coordinator.completeActiveTask(f.conversation.id, f.task.id, f.executor, continuation))
            val sourceKey = f.operation().notificationSourceKey()
            assertTrue(f.dispatcher.submitBackgroundActivityCompletion(f.conversation.id, sourceKey, f.agent.id, f.user.id))
            f.claim(continuation.nextTask)
            val insertions = f.coordinator.claimActiveInsertions(f.conversation.id, continuation.nextTask.id, f.executor, QueuedMessagePlacement.AFTER_TOOL_RESULT)
            assertEquals(1, insertions.size)
            val batch = f.completions.prepareBatch(f.conversation.id, sourceKey)
            assertFalse(batch.isEmpty)
            assertEquals(2, batch.messages.size, "Async completion must be a canonical tool-call/result pair")
            val completionCallMessage = batch.messages[0]
            val completionResultMessage = batch.messages[1]
            val completionCall = assertIs<ContentItem.ToolCall>(completionCallMessage.content.single())
            val completionResult = assertIs<ContentItem.ToolResult>(completionResultMessage.content.single())
            assertEquals(Conversation.Message.Role.ASSISTANT, completionCallMessage.role)
            assertEquals(Conversation.Message.Role.USER, completionResultMessage.role)
            assertIs<Conversation.Message.Author.Runtime>(completionCallMessage.author)
            assertIs<Conversation.Message.Author.Runtime>(completionResultMessage.author)
            assertNotEquals(accepted.invocation.origin.sourceToolCallId, completionCall.id)
            assertEquals(completionCall.id, completionResult.toolUseId)
            assertEquals(accepted.invocation.modelToolName, completionCall.call.name)
            assertEquals(accepted.invocation.toolCall.call.input, completionCall.call.input)
            assertEquals(f.operation().outcome?.content, completionResult.result,
                "Completion must preserve the normal tool-result payload without an extra envelope")
            assertFalse(completionResult.isError)
            assertEquals("ASYNC_TOOL_COMPLETION", completionCallMessage.providerMetadata["syntheticKind"]?.jsonPrimitive?.content)
            assertEquals(accepted.id.value, completionCallMessage.providerMetadata["asyncToolOperationId"]?.jsonPrimitive?.content)
            assertEquals(completionCallMessage.providerMetadata, completionResultMessage.providerMetadata)
            assertTrue(completionCallMessage.instructions.filterIsInstance<Conversation.Message.Instruction.RuntimeObservation>()
                .single().text.let { accepted.id.value in it && accepted.invocation.origin.sourceToolCallId.value in it })
            assertEquals(f.working, f.operation().outcome?.structuredContent)
            assertEquals(AsyncToolOperation.Phase.SUCCEEDED, f.operation().phase)
            Mockito.verifyNoInteractions(f.workers)
            f.messages += batch.messages
            val next = continuation.nextTask.copy(id = ConversationRuntimeTask.Id("next"), parentTaskId = continuation.nextTask.id,
                idempotencyKey = "next", payload = ConversationRuntimeTask.Payload.LlmCall(f.root.id, f.agent.id, 2), requirements = f.requirements)
            assertTrue(f.coordinator.completeActiveTask(f.conversation.id, continuation.nextTask.id, f.executor,
                ConversationRuntimeTaskOutcome.Continue(next, backgroundDeliveryReceipts = listOf(batch.receipt))))
            assertNotNull(f.operation().projectedAt)
            assertTrue(f.completions.prepareBatch(f.conversation.id, sourceKey).isEmpty)
            assertEquals(1, f.messages.flatMap { it.content }.filterIsInstance<ContentItem.ToolResult>().count { it.toolUseId == f.call.id })
        } finally { f.scope.cancel() }
    }

    @Test fun `async result is identical to ordinary sync output except for its new pairing ID`() = runBlocking {
        val structured = buildJsonObject { put("answer", 42) }
        val samples = listOf(
            AiToolCallResult(listOf(AiToolResult.Text("Exact text body"))),
            AiToolCallResult(listOf(AiToolResult.Text("Binary result"),
                AiToolResult.Binary(byteArrayOf(0, 1, 2, 127), "output.bin", "application/octet-stream"))),
            AiToolCallResult(listOf(AiToolResult.Text("Native body")), structuredContent = structured),
            AiToolCallResult(structuredContent = structured),
            AiToolCallResult(listOf(AiToolResult.Text("Original callback error")), isError = true),
        )
        for (sample in samples) {
            val synchronous = Fixture(asynchronous = false, callbackResult = sample)
            val asynchronous = Fixture(callbackResult = sample)
            try {
                synchronous.begin(); synchronous.executeSource()
                assertTrue(synchronous.store.records.isEmpty())
                val expected = assertIs<ContentItem.ToolResult>(synchronous.messages.last().content.single())
                asynchronous.begin(); asynchronous.executeSource()
                asynchronous.operations.reconcile(asynchronous.scope); asynchronous.awaitTerminal()
                val operation = asynchronous.operation()
                val batch = asynchronous.completions.prepareBatch(asynchronous.conversation.id, operation.notificationSourceKey())
                val actual = assertIs<ContentItem.ToolResult>(batch.messages.last().content.single())
                assertNotEquals(asynchronous.call.id, actual.toolUseId)
                assertEquals(expected.copy(toolUseId = actual.toolUseId), actual,
                    "Async delivery must not add a result envelope, structured-output prefix, or alter callback bytes/error fields")
                assertEquals(1, synchronous.effects.get())
                assertEquals(1, asynchronous.effects.get())
            } finally {
                synchronous.scope.cancel(); asynchronous.scope.cancel()
            }
        }
    }

    @Test fun `engine rejects preoccupied completion IDs without consuming delivery or repeating callbacks`() = runBlocking {
        for (collision in 0..4) {
            val f = Fixture(continueAfterCompletion = false, callbackResult = AiToolCallResult(listOf(
                AiToolResult.Text("actual body"), AiToolResult.Binary(byteArrayOf(0, 1, 2, 127), "result.bin", "application/octet-stream"))))
            try {
                f.persistCompletionArtifacts = true
                f.finishSourceAtIdleForBackground()
                val operation = f.operation()
                val pair = f.completions.prepareBatch(f.conversation.id, operation.notificationSourceKey()).messages
                fun clientMessage(id: Conversation.Message.Id, block: ContentItem) = f.root.copy(id = id,
                    instructions = emptyList(), content = listOf(block), author = null).attributeAuthenticatedSubmission(f.user)
                val fakeResult = clientMessage(pair[1].id, ContentItem.UserMessage("ordinary client input"))
                val call = assertIs<ContentItem.ToolCall>(pair[0].content.single())
                val fakeCall = clientMessage(pair[0].id, call.copy(call = call.call.copy(input = buildJsonObject { put("wrong", true) })))
                val stored = when (collision) {
                    0 -> listOf(fakeResult)
                    1 -> listOf(fakeCall)
                    2 -> listOf(fakeCall, fakeResult)
                    3 -> listOf(pair[0], pair[1].copy(content = listOf(f.persistedResult(
                        assertIs<ContentItem.ToolResult>(pair[1].content.single())).copy(result = listOf(ContentItem.ToolResult.Data.Text("altered saved body"))))))
                    else -> {
                        val legacyId = ContentItem.ToolCall.Id("async-tool:${operation.id.value}:completion")
                        assertEquals(call.id, syntheticToolCallId(legacyId.value))
                        listOf(pair[0].copy(content = listOf(call.copy(id = legacyId))))
                    }
                }
                f.messages += stored
                val before = f.messages.toList()
                val background = f.claimBackground()
                val failure = runCatching {
                    val outcome = f.backgroundEngine.runRuntimeTask(background, f.executor) {}
                    assertTrue(f.coordinator.completeActiveTask(f.conversation.id, background.id, f.executor, outcome))
                }.exceptionOrNull()
                assertIs<IllegalArgumentException>(failure,
                    "Collision $collision must fail before delivery ACK; projectedAt=${f.operation().projectedAt}")
                assertTrue(failure.message.orEmpty().contains("Message id collision"))
                assertEquals(before, f.messages)
                assertNull(f.operation().projectedAt)
                assertNotNull(f.operation().outcome)
                f.operations.reconcile(f.scope)
                assertEquals(1, f.effects.get())
            } finally { f.scope.cancel() }
        }
    }

    @Test fun `engine recovers an appended canonical artifact pair after lost ACK without executing it again`() = runBlocking {
        val f = Fixture(continueAfterCompletion = false, callbackResult = AiToolCallResult(listOf(
            AiToolResult.Text("actual body"), AiToolResult.Binary(byteArrayOf(0, 1, 2, 127), "result.bin", "application/octet-stream"))))
        try {
            f.persistCompletionArtifacts = true
            f.finishSourceAtIdleForBackground()
            val pair = f.completions.prepareBatch(f.conversation.id, f.operation().notificationSourceKey()).messages
            val background = f.claimBackground()
            f.failAfterCompletionAppend = true
            val lost = assertFailsWith<IllegalStateException> { f.backgroundEngine.runRuntimeTask(background, f.executor) {} }
            assertEquals("Lost completion append acknowledgement", lost.message)
            assertNull(f.operation().projectedAt)
            val appended = f.messages.filter { message -> pair.any { it.id == message.id } }
            assertEquals(2, appended.size)
            assertIs<ContentItem.ToolResult.Data.ArtifactData>(assertIs<ContentItem.ToolResult>(appended.last().content.single()).result.last())
            f.failAfterCompletionAppend = false
            val outcome = f.backgroundEngine.runRuntimeTask(background, f.executor) {}
            assertTrue(f.coordinator.completeActiveTask(f.conversation.id, background.id, f.executor, outcome))
            assertNotNull(f.operation().projectedAt)
            assertEquals(appended, f.messages.filter { message -> pair.any { it.id == message.id } })
            assertContentEquals(byteArrayOf(0, 1, 2, 127), f.persistedArtifactBytes.values.single())
            f.operations.reconcile(f.scope)
            assertEquals(1, f.effects.get())
        } finally { f.scope.cancel() }
    }

    @Test fun `captured readonly never upgrades when the next input switches to writable`() = runBlocking {
        val f = Fixture()
        try {
            f.begin(); f.executeSource()
            f.messages += f.root.copy(id = Conversation.Message.Id("writable"), instructions = listOf(f.mode("mode_writable")))
            f.operations.reconcile(f.scope); f.awaitTerminal()
            assertTrue(f.observedOrigin?.readOnly == true)
            assertEquals(1, f.effects.get())
        } finally { f.scope.cancel() }
    }

    @Test fun `revoked project permission AI permission and tool access prevent dispatch after durable acceptance`() = runBlocking {
        for (revoke in listOf<(Fixture) -> Unit>(
            { it.membership = null }, { it.user = it.user.copy(aiAllowed = false) }, { it.agent = it.agent.copy(toolAccess = ToolAccessPolicy.AllowOnly()) })) {
            val f = Fixture()
            try {
                f.begin(); f.executeSource(); revoke(f)
                f.operations.reconcile(f.scope); f.awaitTerminal()
                assertEquals(0, f.effects.get())
                assertEquals(AsyncToolOperation.Phase.FAILED, f.operation().phase)
                assertNull(f.operation().startedAt)
                assertEquals(1, f.approvalCount)
            } finally { f.scope.cancel() }
        }
    }

    @Test fun `branch owner and tool revision are not rebound while a result remains privately inspectable`() = runBlocking {
        for (change in listOf<(Fixture) -> Unit>(
            { it.conversation = it.conversation.copy(currentThread = Conversation.Thread.Id("different")) },
            { it.conversation = it.conversation.copy(participants = setOf(Conversation.Participant.User(User.Id("replacement-owner")), Conversation.Participant.Agent(it.agent.id))) },
            { it.definition = it.definition.copy(description = "Different contract") })) {
            val f = Fixture()
            try {
                f.begin(); f.executeSource(); change(f)
                f.operations.reconcile(f.scope); f.awaitTerminal()
                assertEquals(0, f.effects.get())
                assertEquals(AsyncToolOperation.Phase.FAILED, f.operation().phase)
                assertNotNull(f.operation().outcome)
                assertFailsWith<IllegalArgumentException> { f.scopes.requireInspection(User.Id("another-actor"), f.operation(), false) }
            } finally { f.scope.cancel() }
        }
    }

    @Test fun `approval refusal never creates an operation or invokes a callback`() = runBlocking {
        val rejected = Fixture(reject = true)
        try {
            rejected.begin(); rejected.executeSource()
            assertTrue(rejected.store.records.isEmpty())
            assertTrue(rejected.messages.last().content.filterIsInstance<ContentItem.ToolResult>().single().isError)
            assertEquals(0, rejected.effects.get())
        } finally { rejected.scope.cancel() }
    }

    @Test fun `retry after a lost append acknowledgement recovers the exact durable source without accepting a changed call`() = runBlocking {
        val f = Fixture()
        try {
            f.begin(); f.failAfterAppend = true
            assertFailsWith<IllegalStateException> { f.executeSource() }
            assertEquals(AsyncToolOperation.Phase.ACCEPTED, f.operation().phase)
            assertEquals(0, f.effects.get())
            val receipt = assertNotNull(f.admission.recoverReceipt(f.task, f.agent.id, f.call, f.conversation.currentThread))
            assertEquals(f.messages.last().content.single(), receipt)
            assertNull(f.admission.recoverReceipt(f.task, f.agent.id, f.call.copy(id = ContentItem.ToolCall.Id("not-admitted")), f.conversation.currentThread))
            assertFailsWith<IllegalStateException> { f.admission.recoverReceipt(f.task.copy(actorUserId = User.Id("another-actor")), f.agent.id, f.call, f.conversation.currentThread) }
            f.operations.reconcile(f.scope); f.awaitTerminal()
            assertEquals(1, f.effects.get())
            f.operations.reconcile(f.scope)
            assertEquals(1, f.approvalCount)
            assertEquals(1, f.store.records.size)
        } finally { f.scope.cancel() }
    }

    @Test fun `untrusted origin fields cannot change model-bound actor task thread or raw approval proof`() = runBlocking {
        for (key in listOf("origin", "actor_user_id", "source_task_id", "thread_id", "preapprovedFingerprints", "context")) {
            val f = Fixture()
            try {
                f.begin(extra = key)
                assertFailsWith<IllegalArgumentException> { f.executeSource() }
                assertTrue(f.store.records.isEmpty())
                assertEquals(0, f.effects.get())
                assertEquals(0, f.approvalCount)
            } finally { f.scope.cancel() }
        }
    }

    private class Fixture(
        val reject: Boolean = false,
        val asynchronous: Boolean = true,
        val callbackResult: AiToolCallResult? = null,
        val continueAfterCompletion: Boolean = true,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val now = Clock.System.now()
        var user = User(User.Id("actor"), displayName = "Actor", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
        val project = Project(Project.Id("project"), "Project", createdAt = now, lastUsedAt = now)
        var agent = AgentDefinition(AgentDefinition.Id("agent"), project.id, "Agent", emptyList(), runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model")),
            type = AgentDefinition.Type.Project, createdAt = now, updatedAt = now)
        var conversation = Conversation(Conversation.Id("conversation"), project.id,
            setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(agent.id)),
            currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now)
        var membership: ProjectMembership? = ProjectMembership(project.id, user.id, ProjectMembership.Role.EDITOR, now, user.id)
        val root = Conversation.Message(Conversation.Message.Id("root"), conversation.id, role = Conversation.Message.Role.USER,
            author = Conversation.Message.Author.User(user.id, "Actor"), content = listOf(ContentItem.UserMessage("Go")), instructions = listOf(mode("mode_readonly")), createdAt = now)
        fun mode(id: String) = Conversation.Message.Instruction.UserInstruction(id, id, id)
        val messages = mutableListOf(root)
        val store = TestAsyncToolOperations()
        val coordinator = InMemoryConversationRuntimeCoordinator(store)
        val executor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server"))
        val requirements = ConversationRuntimeTaskRequirements(ConversationRuntimeCapability.entries.toSet(), ConversationRuntimeTaskTarget.Server)
        val effects = AtomicInteger()
        var observedOrigin: AsyncToolOrigin? = null
        var definition = AiToolDefinition("native", "Synthetic callback", """{"type":"object","properties":{}}""")
        val working = buildJsonObject { put("status", "WORKING"); put("task_id", "nested-managed-command") }
        private val callback = object : AiToolCallback {
            override val definition get() = this@Fixture.definition
            override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.SERVER)
            override fun call(toolInput: String, context: ToolExecutionContext?) = error("Result callback required")
            override suspend fun callResult(toolInput: String, context: ToolExecutionContext?): AiToolCallResult {
                assertEquals(JsonObject(emptyMap()), Json.parseToJsonElement(toolInput))
                observedOrigin = context?.asyncOrigin
                effects.incrementAndGet()
                return callbackResult ?: AiToolCallResult(listOf(AiToolResult.Text(working.toString())), structuredContent = working)
            }
        }
        private val provider = object : AiToolProvider { override fun getTools() = listOf(callback) }
        val users = responding<UserDirectoryService> { if (it.method.name.substringBefore('-') == "findActiveById") user else error("Unexpected user call") }
        private val conversationRepo = responding<ConversationRepository> { conversation }
        private val conversations = responding<ConversationDomainService> {
            when (it.method.name.substringBefore('-')) {
                "findById" -> conversation
                "getProject" -> project
                "loadCurrentMessages" -> messages.toList()
                else -> error("Unexpected conversation call ${it.method.name}")
            }
        }
        private val agents = responding<AgentDomainService> { agent }
        private val membershipRepo = responding<ProjectMembershipRepository> { membership }
        private val projects = ProjectAccessApplicationService(mock(), membershipRepo, conversationRepo, mock(), mock())
        private val workspaces = mock<WorkspaceDomainService>()
        private val workerAccess = mock<WorkerAccessService>()
        private val registry = mock<ConversationRuntimeWorkerRegistry>()
        private val catalog = DistributedAiToolCatalog(registry, workspaces, provider, workerAccess, InMemoryAiToolContractRepository())
        private val contexts = ToolInvocationContextResolver(users, conversationRepo, projects, agents, workspaces, workerAccess, coordinator)
        private val slots = mock<SlotApplicationService>()
        val workers = mock<AsyncWorkerToolExecutionClient>()
        var approvalCount = 0
        private val approvals = object : ToolApprovalService {
            override suspend fun approve(toolCalls: List<ContentItem.ToolCall>): ApprovalResult {
                approvalCount++; return if (reject) ApprovalResult.Rejected("Denied") else ApprovalResult.Approved
            }
        }
        val scopes = AsyncToolScopeAccess(users, conversationRepo, projects, agents)
        val admission = AsyncToolAdmissionService(store, scopes, conversations, agents, catalog, contexts, slots, approvals, coordinator, workers)
        private val outcomes = AsyncToolOutcomeService(store, scopes)
        private val historyStore = responding<ConversationHistoryRepository> {
            assertEquals("thread", it.arguments[0])
            val index = messages.indexOfFirst { message -> message.id.value == it.arguments[1] }
            when (it.method.name.substringBefore('-')) {
                "position" -> index.takeIf { position -> position >= 0 }
                "message" -> index.takeIf { position -> position >= 0 }?.let { position -> PositionedConversationMessage(position, messages[position]) }
                else -> error("Unexpected history read ${it.method.name}")
            }
        }
        private val history = ConversationHistoryReadService(conversationRepo, historyStore, coordinator)
        private val secrets = ToolSecretResolutionService(mock())
        private val parallel = ParallelToolExecutor(provider, approvals)
        val operations = AsyncToolOperationService(store, admission, outcomes, parallel, workers, historyStore, conversations, secrets,
            ConversationRuntimeExecutorDescriptor(executor, requirements.capabilities))
        var failAfterAppend = false
        var failAfterCompletionAppend = false
        var persistCompletionArtifacts = false
        val persistedArtifactBytes = mutableMapOf<Artifact.Id, ByteArray>()
        val validatedBatches = mutableListOf<List<Conversation.Message>>()
        private val appender = object : ConversationRuntimeMessageAppender {
            override suspend fun appendRuntimeMessages(conversationId: Conversation.Id, messages: List<Conversation.Message>): List<Conversation.Message> {
                require(messages.all { it.conversationId == conversationId })
                require(messages.map { it.id }.distinct().size == messages.size)
                validatedBatches += messages
                messages.forEach { message ->
                    val existing = this@Fixture.messages.firstOrNull { it.id == message.id }
                    require(existing?.samePersistentBodyAs(message) != false) { "Message id collision with different content" }
                }
                val added = messages.filter { message -> this@Fixture.messages.none { it.id == message.id } }
                this@Fixture.messages += added
                if (failAfterCompletionAppend) throw IllegalStateException("Lost completion append acknowledgement")
                return added
            }
            override suspend fun appendRuntimeMessage(conversationId: Conversation.Id, message: Conversation.Message): Conversation {
                this@Fixture.messages += message
                if (failAfterAppend) throw IllegalStateException("Lost source append acknowledgement")
                return conversation
            }
        }
        fun persistedResult(result: ContentItem.ToolResult): ContentItem.ToolResult = if (!persistCompletionArtifacts) result else result.copy(
            result = result.result.map { data ->
                if (data !is ContentItem.ToolResult.Data.Base64Data) data else {
                    val bytes = java.util.Base64.getDecoder().decode(data.data)
                    val id = Artifact.Id(java.util.UUID.nameUUIDFromBytes(
                        "${result.toolUseId.value}:${data.fileName}:${data.data}".encodeToByteArray()).toString())
                    persistedArtifactBytes[id] = bytes
                    ContentItem.ToolResult.Data.ArtifactData(Artifact.Reference(id, data.fileName ?: "output.bin", data.mediaType.value,
                        bytes.size.toLong(), Artifact.Purpose.TOOL_OUTPUT, Artifact.Kind.fromMediaType(data.mediaType.value)))
                }
            })
        private val artifacts = responding<ConversationArtifactApplicationService> {
            check(it.method.name.substringBefore('-') == "persistAndCommitToolResults")
            @Suppress("UNCHECKED_CAST")
            (it.arguments[2] as List<ContentItem.ToolResult>).map(::persistedResult)
        }
        private val source = ConversationToolExecutionTaskService(conversations, history, appender, workspaces, coordinator, mock(), parallel,
            workerAccess, mock(), mock(), artifacts, secrets, admission, operations)
        val dispatcher = ConversationRuntimeDispatcher(coordinator, InMemoryConversationRuntimeEventBus(), mock(), ArtifactReferenceValidator { _, _ -> })
        val completions = BackgroundActivityCompletionApplicationService(coordinator,
            responding<BackgroundActivityOriginRepository> { emptyMap<BackgroundActivityOrigin.Key, BackgroundActivityOrigin>() },
            BackgroundActivityContinuationPolicy { _, _ -> continueAfterCompletion }, operations)
        val backgroundEngine = ConversationEngineService(
            aiRuntimeProvider = mock(), aiToolProvider = provider, agentDomainService = agents, agentPromptAssemblyService = mock(),
            toolApprovalService = approvals, toolExecutionTaskService = source, conversationService = conversations,
            conversationHistory = history, contextHistory = mock(), conversationMessageAppender = appender,
            historyMutationExecutor = mock(), compactions = mock(), memoryApplicationService = mock(), memoryToolApplicationService = mock(),
            backgroundActivityCompletionApplicationService = completions, memoryMessageRoutingApplicationService = mock(),
            toolCallSequenceFixerService = mock(), settingsProvider = mock(), aiConfigurationProvider = mock(), runtimeCoordinator = coordinator,
            runtimeStateSyncService = mock(), distributedToolCatalog = catalog, agentSkillRuntimeCatalogService = mock(),
            aiToolRuntimeCatalogService = mock(), aiToolCapabilityCatalogService = mock(), toolRoutingService = mock(), artifactService = artifacts,
            activeGenerationPublisher = mock(), messageTemporalContextService = MessageTemporalContextService(),
            stickyMessageInstructionService = StickyMessageInstructionService(),
            suggestedRepliesGenerationService = mock(), conversationPins = mock(),
        )
        lateinit var task: ConversationRuntimeTask
        lateinit var call: ContentItem.ToolCall
        lateinit var catalogEntry: DistributedAiTool
        suspend fun begin(extra: String? = null) {
            Mockito.`when`(workerAccess.listAvailableToProject(project.id)).thenReturn(emptyList())
            Mockito.`when`(workspaces.findByProject(project.id)).thenReturn(emptyList())
            Mockito.`when`(registry.list()).thenReturn(emptyList())
            catalogEntry = catalog.snapshot(project, agent.toolAccess, conversation).entries.values.single()
            call = ContentItem.ToolCall(ContentItem.ToolCall.Id("call"), ContentItem.ToolCall.Data(catalogEntry.modelName,
                buildJsonObject { put("_grz", buildJsonObject { put("async", asynchronous); extra?.let { put(it, "forged") } }) }))
            val rootTask = ConversationRuntimeTask(ConversationRuntimeTask.Id("root-task"), conversation.id, actorUserId = user.id,
                payload = ConversationRuntimeTask.Payload.AgentInvocation(root, agent.id), placement = QueuedMessagePlacement.END_OF_TURN,
                idempotencyKey = "root-task", requirements = requirements, createdAt = now)
            assertTrue(coordinator.submit(rootTask)); claim(rootTask)
            task = rootTask.copy(id = ConversationRuntimeTask.Id("tool-task"), parentTaskId = rootTask.id, idempotencyKey = "tool-task",
                payload = ConversationRuntimeTask.Payload.ToolExecution(root.id, agent.id, 1, listOf(call), true,
                    mapOf(call.id.value to ConversationRuntimeTaskTarget.Server), mapOf(call.id.value to catalogEntry.executionName)))
            assertTrue(coordinator.completeActiveTask(conversation.id, rootTask.id, executor, ConversationRuntimeTaskOutcome.Continue(task)))
            claim(task)
        }
        suspend fun finishSourceAtIdleForBackground() {
            begin()
            messages += Conversation.Message(Conversation.Message.Id("source-call-message"), conversation.id,
                role = Conversation.Message.Role.ASSISTANT, author = Conversation.Message.Author.Agent(agent.id, "Agent"),
                content = listOf(call), createdAt = now)
            executeSource(); operations.reconcile(scope); awaitTerminal()
            assertTrue(coordinator.completeActiveTask(conversation.id, task.id, executor, ConversationRuntimeTaskOutcome.CompleteTurn))
        }
        suspend fun claimBackground(): ConversationRuntimeTask {
            val background = ConversationRuntimeTask(ConversationRuntimeTask.Id("background"), conversation.id,
                actorUserId = user.id, payload = ConversationRuntimeTask.Payload.BackgroundActivityCompletion(operation().notificationSourceKey(), agent.id),
                placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = "background", requirements = requirements, createdAt = now)
            assertTrue(coordinator.submitBackgroundActivity(background, agent.id)); claim(background)
            return background
        }
        suspend fun claim(task: ConversationRuntimeTask) {
            assertNotNull(coordinator.claimDeliveredTask(task.conversationId, task.id, executor, requirements.capabilities, emptySet()))
            assertTrue(coordinator.markActiveTaskStarted(task.conversationId, task.id, executor, now))
        }
        suspend fun executeSource() = assertIs<ConversationRuntimeTaskOutcome.Continue>(source.run(task, executor, task.payload as ConversationRuntimeTask.Payload.ToolExecution) {})
        suspend fun operation() = assertNotNull(store.records.values.singleOrNull())
        suspend fun awaitTerminal() = withTimeout(5_000) { while (!operation().phase.isTerminal) delay(5) }
        fun rawFingerprint() = AiToolDescriptor(callback.definition, callback.metadata).contractFingerprint()
    }
    companion object {
        private inline fun <reified T : Any> mock(): T = Mockito.mock(T::class.java)
        private inline fun <reified T : Any> responding(crossinline answer: (InvocationOnMock) -> Any?): T = Mockito.mock(T::class.java) { answer(it) }
    }
}
