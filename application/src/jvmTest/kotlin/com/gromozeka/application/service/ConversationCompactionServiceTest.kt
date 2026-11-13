package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult as Checkpoint
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Clock

class ConversationCompactionServiceTest {
    @Test fun `disabled and below threshold do not start compaction or ordinary inference`() = runBlocking {
        for (threshold in listOf(null, 1000)) {
            val f = Fixture(threshold)
            assertNull(f.apply())
            assertEquals(if (threshold == null) 0 else 1, f.inspections)
            assertEquals(0, f.summaryCalls + f.nativeCalls + f.commits)
        }
    }

    @Test fun `text auto summarizes all sources and commits only after the owner check`() = runBlocking {
        val f = Fixture(100)
        val changed = assertNotNull(f.apply())
        assertNotEquals(f.conversation.currentThread, changed.currentThread)
        assertEquals(listOf("inspect", "summary", "owner", "commit", "event"), f.order)
        assertEquals(f.sources, f.summarized)
        val result = assertNotNull(f.committed).content.single() as Checkpoint
        assertEquals(f.sources.map { it.id }, result.sourceMessageIds)
        assertEquals(Checkpoint.Strategy.SUMMARIZE, result.strategy)
        assertEquals(Checkpoint.Coverage.SELECTED_MESSAGES, result.coverage)
        assertEquals(Checkpoint.Origin.GROMOZEKA_POLICY, result.origin)
        assertEquals(0, f.nativeCalls)
    }

    @Test fun `native executor covers only its captured prefix and does not hide unseen appends`() = runBlocking {
        val f = Fixture(100, native = true)
        f.nativeSourceIds = f.sources.take(2).map { it.id }
        f.apply()
        val result = assertNotNull(f.committed).content.single() as Checkpoint
        assertEquals(f.nativeSourceIds, result.sourceMessageIds)
        assertFalse(f.sources.last().id in result.sourceMessageIds)
        assertEquals(1, f.nativeCalls)
        assertEquals(0, f.summaryCalls)
    }

    @Test fun `native error never selects a text fallback or applies history`() = runBlocking {
        val f = Fixture(100, native = true)
        f.nativeFailure = IllegalStateException("provider compact failed")
        assertFailsWith<IllegalStateException> { f.apply() }
        assertEquals(1, f.nativeCalls)
        assertEquals(0, f.summaryCalls + f.commits)
    }

    @Test fun `stop after preparation prevents commit`() = runBlocking {
        val f = Fixture(100)
        assertFailsWith<CancellationException> { f.apply { throw CancellationException("Stop") } }
        assertEquals(1, f.summaryCalls)
        assertEquals(0, f.commits)
    }

    @Test fun `configuration change during preparation prevents commit`() = runBlocking {
        val f = Fixture(100)
        f.onSummary = { f.resolved = f.resolved.copy(modelConfiguration = f.resolved.modelConfiguration.copy(displayName = "Changed")) }
        assertFailsWith<IllegalStateException> { f.apply() }
        assertEquals(0, f.commits)
    }

    @Test fun `replayed operation is idempotent before any provider call`() = runBlocking {
        val f = Fixture(100)
        f.persisted = true
        assertEquals(f.conversation, f.apply())
        assertEquals(0, f.inspections + f.summaryCalls + f.nativeCalls + f.commits)
    }

    @Test fun `insufficient compaction blocks instead of looping or starting inference`() = runBlocking {
        val f = Fixture(100)
        assertFailsWith<IllegalStateException> { f.apply(alreadyCompacted = true) }
        assertEquals(1, f.inspections)
        assertEquals(0, f.summaryCalls + f.nativeCalls + f.commits)
    }

    @Test fun `unrevealed secret space participates in the threshold without entering a summary`() = runBlocking {
        val f = Fixture(500)
        assertNotNull(f.apply(extraTokens = 400))
        assertEquals(f.sources, f.summarized)
    }

    @Test fun `measured append only continuation has no count call even near the threshold`() = runBlocking {
        val f = Fixture(150)
        f.withMeasurement()
        f.request = f.request.copy(messages = f.request.messages + f.sources.last().copy(id = Conversation.Message.Id("tail"),
            content = listOf(Conversation.Message.ContentItem.UserMessage("New"))))
        assertNull(f.apply())
        assertEquals(0, f.inspections)
        assertEquals(0, f.counterCalls)
    }

    @Test fun `editing measured history with the same ids recounts even when text shrinks`() = runBlocking {
        for (text in listOf("X", "A longer changed question")) {
            val f = Fixture(1000)
            f.withMeasurement()
            f.request = f.request.copy(messages = f.request.messages.mapIndexed { i, message ->
                if (i == 0) message.copy(content = listOf(Conversation.Message.ContentItem.UserMessage(text))) else message
            })
            assertNull(f.apply())
            assertEquals(listOf(true), f.countPermissions)
        }
    }

    @Test fun `disabled count and unsupported API stay local without contacting any runtime`() = runBlocking {
        for (disabled in listOf(true, false)) {
            val f = Fixture(1000)
            f.resolved = if (disabled) f.resolved.copy(modelConfiguration = f.resolved.modelConfiguration.copy(
                compaction = f.resolved.modelConfiguration.compaction.copy(tokenCounting = AiModelConfiguration.TokenCounting(AiModelConfiguration.TokenCounting.Mode.LOCAL))))
            else f.resolved.copy(connection = AiConnection.OpenAiCompatible(f.resolved.connection.id, "Gateway", baseUrl = "https://example.test"),
                modelSpec = f.resolved.modelSpec.copy(provider = AiProvider.CUSTOM))
            assertNull(f.apply())
            assertEquals(0, f.inspections + f.counterCalls)
        }
    }

    @Test fun `local policy still permits native capability inspection without a count endpoint`() = runBlocking {
        val f = Fixture(1, native = true)
        f.resolved = f.resolved.copy(modelConfiguration = f.resolved.modelConfiguration.copy(compaction =
            f.resolved.modelConfiguration.compaction.copy(tokenCounting = AiModelConfiguration.TokenCounting(AiModelConfiguration.TokenCounting.Mode.LOCAL))))
        assertNotNull(f.apply())
        assertEquals(listOf(false), f.countPermissions)
        assertEquals(1, f.nativeCalls)
    }

    @Test fun `alternate counter does not replace native selection and actual main usage supersedes it`() = runBlocking {
        val f = Fixture(100)
        f.useCounter()
        assertNotNull(f.apply())
        assertEquals(1, f.counterCalls)
        assertEquals(0, f.inspections + f.nativeCalls)
        assertEquals(1, f.summaryCalls)
        assertEquals("CONTEXT_COUNT", f.counterRequest?.options?.usagePurpose)
        assertEquals(f.request.messages.map { it.content }, f.counterRequest?.messages?.map { it.content })
        val next = Fixture(1000)
        next.useCounter(); next.withMeasurement()
        assertNull(next.apply())
        assertEquals(0, next.counterCalls + next.inspections)
    }

    @Test fun `enabled counter failures never start generation or silently select local estimation`() = runBlocking {
        for (alternate in listOf(false, true)) {
            val f = Fixture(1000)
            if (alternate) { f.useCounter(); f.counterFailure = IllegalStateException("counter unavailable") }
            else f.countFailure = IllegalStateException("counter unavailable")
            assertFailsWith<IllegalStateException> { f.apply() }
            assertEquals(0, f.nativeCalls + f.summaryCalls + f.commits)
        }
    }

    @Test fun `measured ordinary Worker step sends only the generation RPC`() = runBlocking {
        val f = Fixture(1000)
        val identity = ConversationRuntimeWorkerIdentity(ConversationRuntimeWorkerId("worker"), ConversationRuntimeWorkerSessionId("session"))
        f.resolved = f.resolved.copy(connection = (f.resolved.connection as AiConnection.OpenAiApi).copy(
            executionTarget = AiExecutionTarget.Worker(identity.workerId.value)))
        f.withMeasurement()
        var remoteCalls = 0
        var resolutions = 0
        val client = Mockito.mock(AiRequestResponseExecutionClient::class.java) { invocation ->
            when (invocation.method.name.substringBefore('-')) {
                "inspectContext" -> error("An append-only step must not send an inspection RPC")
                "call" -> { remoteCalls++; AiRuntimeResponse(emptyList()) }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val direct = Mockito.mock(DirectAiRuntimeProvider::class.java) { invocation ->
            when (invocation.method.name) {
                "capabilities" -> AiRuntimeCapabilities()
                "getRuntime" -> error("The Server must not execute a Worker model")
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val targets = object : ConversationRuntimeWorkerTargetResolver {
            override suspend fun requireRegistered(workerId: ConversationRuntimeWorkerId, capability: ConversationRuntimeCapability): ConversationRuntimeWorkerIdentity {
                assertEquals(identity.workerId, workerId); resolutions++; return identity
            }
            override suspend fun requireOnline(workerId: ConversationRuntimeWorkerId, capability: ConversationRuntimeCapability) = requireRegistered(workerId, capability)
        }
        val provider = TargetedAiRuntimeProvider(direct, f.configurations, targets, listOf(client),
            Mockito.mock(AiUsageRecorder::class.java), Mockito.mock(com.gromozeka.domain.repository.IdentityRepository::class.java))
        f.routedRuntime = provider.getRuntime(f.selection, null)
        assertNull(f.apply())
        assertEquals(0, resolutions)
        requireNotNull(f.routedRuntime).call(f.request)
        assertEquals(1, remoteCalls)
        assertEquals(1, resolutions)
    }

    @Test fun `alternate count never receives signed reasoning or expands opaque checkpoints`() = runBlocking {
        val f = Fixture(1000)
        f.useCounter()
        val checkpoint = f.sources.last().copy(id = Conversation.Message.Id("checkpoint"), role = Conversation.Message.Role.ASSISTANT,
            content = listOf(Checkpoint(Checkpoint.Payload.OpaqueProviderState(JsonObject(mapOf(
                "replay_item" to JsonObject(mapOf("type" to JsonPrimitive("compaction"), "encrypted_content" to JsonPrimitive("private-ciphertext".repeat(100))))))),
                Checkpoint.Origin.GROMOZEKA_POLICY, sourceMessageIds = f.sources.map { it.id }, coverage = Checkpoint.Coverage.ALL_PREVIOUS,
                providerScope = Checkpoint.ProviderScope("OPENAI_API", "connection", "configuration", "model"))))
        val tail = f.sources.last().copy(id = Conversation.Message.Id("tail"), content = listOf(Conversation.Message.ContentItem.UserMessage("Continue")))
        f.request = f.request.copy(messages = f.sources + checkpoint + tail)
        assertNull(f.apply())
        val counted = requireNotNull(f.counterRequest)
        assertEquals(listOf(tail.id), counted.messages.map { it.id })
        assertFalse(counted.toString().contains("private-ciphertext"))
        assertEquals(1, f.counterCalls)
    }

    private class Fixture(threshold: Int?, native: Boolean = false) {
        private val now = Clock.System.now()
        val conversation = Conversation(id = Conversation.Id("conversation"), projectId = Project.Id("project"), displayName = "Test",
            participants = setOf(Conversation.Participant.Agent(AgentDefinition.Id("agent")), Conversation.Participant.User(User.Id("user"))),
            currentThread = Conversation.Thread.Id("source"), createdAt = now, updatedAt = now)
        val selection = AiRuntimeSelection(AiModelConfiguration.Id("configuration"))
        val agent = AgentDefinition(AgentDefinition.Id("agent"), name = "Agent", prompts = emptyList(), runtimeSelection = selection,
            type = AgentDefinition.Type.Global, createdAt = now, updatedAt = now)
        var resolved = ResolvedAiRuntime(AiConnection.OpenAiApi(AiConnection.Id("connection"), "Connection"),
            AiModelConfiguration(selection.modelConfigurationId, AiConnection.Id("connection"), "model", "Model",
                compaction = AiModelConfiguration.Compaction(threshold)),
            AiModelSpec("model", AiProvider.OPENAI, setOf(AiModelCapability.TEXT_GENERATION),
                AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(2000, 1000))))
        val sources = (1..3).map { index -> Conversation.Message(id = Conversation.Message.Id("m$index"), conversationId = conversation.id,
            role = Conversation.Message.Role.USER, content = listOf(Conversation.Message.ContentItem.UserMessage("Message $index")), createdAt = now) }
        val task = ConversationRuntimeTask(ConversationRuntimeTask.Id("task"), conversation.id, ConversationRuntimeTurnId("turn"),
            payload = ConversationRuntimeTask.Payload.LlmCall(sources.first().id, agent.id, 1),
            parentTaskId = ConversationRuntimeTask.Id("root"), actorUserId = User.Id("user"),
            placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = "task",
            requirements = ConversationRuntimeTaskRequirements(capabilities = setOf(ConversationRuntimeCapability.AI_REQUEST_RESPONSE, ConversationRuntimeCapability.MEMORY_PIPELINE), target = ConversationRuntimeTaskTarget.Server), createdAt = now)
        val order = mutableListOf<String>()
        var routedRuntime: AiRuntime? = null
        var inspections = 0
        var countFailure: Exception? = null
        val countPermissions = mutableListOf<Boolean>()
        var counterCalls = 0
        var counterRequest: AiRuntimeRequest? = null
        var counterResolved: ResolvedAiRuntime? = null
        var counterFailure: Exception? = null
        var summaryCalls = 0
        var nativeCalls = 0
        var commits = 0
        var persisted = false
        var nativeSourceIds = sources.map { it.id }
        var nativeFailure: Exception? = null
        var onSummary: () -> Unit = {}
        var summarized: List<Conversation.Message>? = null
        var committed: Conversation.Message? = null
        val provider = object : AiRuntime {
            override val capabilities = AiRuntimeCapabilities(supportsNativeCompaction = native)
            override suspend fun inspectContext(request: AiRuntimeRequest, allowProviderCount: Boolean): AiContextInspection {
                inspections++; order += "inspect"; countPermissions += allowProviderCount
                countFailure?.let { throw it }
                return AiContextInspection(200, if (allowProviderCount) AiContextInspection.Precision.PROVIDER_COUNT else AiContextInspection.Precision.ESTIMATE, native)
            }
            override suspend fun compact(request: AiRuntimeRequest): AiRuntimeResponse {
                nativeCalls++; order += "native"
                assertEquals("CONTEXT_COMPACTION", request.options.usagePurpose)
                nativeFailure?.let { throw it }
                return AiRuntimeResponse(listOf(AiAssistantMessage(listOf(Checkpoint(
                    Checkpoint.Payload.OpaqueProviderState(JsonObject(mapOf("state" to JsonPrimitive("opaque")))),
                    Checkpoint.Origin.GROMOZEKA_POLICY, sourceMessageIds = nativeSourceIds,
                    coverage = Checkpoint.Coverage.ALL_PREVIOUS,
                    providerScope = Checkpoint.ProviderScope("OPENAI_API", "connection", "configuration", "model"),
                )))), outcome = AiStepOutcome.CONTINUE)
            }
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse = error("Ordinary inference must not run inside compaction")
            override fun stream(request: AiRuntimeRequest) = emptyFlow<AiRuntimeResponse>()
        }
        val configurations = object : AiConfigurationProvider {
            override val snapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(null)
            override val snapshot: AiCatalogSnapshot get() = error("Not used")
            override fun resolveAiRuntime(selection: AiRuntimeSelection) =
                if (selection == this@Fixture.selection) resolved else requireNotNull(counterResolved).also {
                    assertEquals(it.modelConfiguration.id, selection.modelConfigurationId)
                }
        }
        private val history = Mockito.mock(ThreadMessageRepository::class.java) { invocation ->
            when (invocation.method.name.substringBefore('-')) {
                "contains" -> persisted
                "getMessagesByThread" -> sources
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        private val summaries = Mockito.mock(MessageSquashService::class.java) { invocation ->
            if (invocation.method.name.startsWith("generateWithAi")) {
                summaryCalls++; order += "summary"
                @Suppress("UNCHECKED_CAST")
                summarized = invocation.arguments[1] as List<Conversation.Message>
                assertEquals(SquashType.SUMMARIZE, invocation.arguments[2])
                onSummary()
                MessageSquashService.GeneratedCompaction("Handoff", null, null)
            } else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        private val committer = object : ContextCompactionCommitter {
            override suspend fun commit(expectedThreadId: Conversation.Thread.Id, message: Conversation.Message): Conversation {
                assertEquals(conversation.currentThread, expectedThreadId)
                commits++; order += "commit"; committed = message
                return conversation.copy(currentThread = Conversation.Thread.Id("compacted"))
            }
        }
        private val coordinator = Mockito.mock(ConversationRuntimeCoordinator::class.java) { invocation ->
            if (invocation.method.name.startsWith("recordEvent")) ConversationRuntimeEventLogEntry(1, conversation.id,
                invocation.arguments[0] as ConversationRuntimeEvent, now) else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        private val events = Mockito.mock(ConversationRuntimeEventBus::class.java) { invocation ->
            if (invocation.method.name == "publish") { order += "event"; Unit } else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        var request = AiRuntimeRequest(emptyList(), sources, options = AiRuntimeOptions(
            usagePurpose = "CONVERSATION", toolContext = mapOf("threadId" to conversation.currentThread.value)))
        private val runtimeProvider = object : AiRuntimeProvider {
            override fun capabilities(selection: AiRuntimeSelection) = AiRuntimeCapabilities()
            override fun getRuntime(selection: AiRuntimeSelection, workspaceRootPath: String?): AiRuntime {
                assertEquals(counterResolved?.modelConfiguration?.id, selection.modelConfigurationId)
                return object : AiRuntime {
                    override suspend fun inspectContext(request: AiRuntimeRequest, allowProviderCount: Boolean): AiContextInspection {
                        assertTrue(allowProviderCount)
                        counterCalls++; counterRequest = request
                        counterFailure?.let { throw it }
                        return AiContextInspection(200, AiContextInspection.Precision.PROVIDER_COUNT, nativeCompactionAvailable = true)
                    }
                    override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse = error("Counter must not generate")
                    override fun stream(request: AiRuntimeRequest) = emptyFlow<AiRuntimeResponse>()
                }
            }
        }
        fun withMeasurement() {
            val response = AiRuntimeResponse(listOf(AiAssistantMessage(listOf(
                Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText("Measured answer"))))),
                contextUsage = AiContextUsage(100, 20))
            val measurement = requireNotNull(AiContextMeasurement.capture(resolved, request, response, AiRequestTokenEstimate))
            val answer = sources.last().copy(id = Conversation.Message.Id("answer"), role = Conversation.Message.Role.ASSISTANT,
                content = response.messages.single().content, providerMetadata = JsonObject(mapOf(
                    AiContextMeasurement.KEY to Json.encodeToJsonElement(measurement),
                    AI_REPLAY_THREAD_METADATA_KEY to JsonPrimitive(conversation.currentThread.value))))
            request = request.copy(messages = request.messages + answer)
        }
        fun useCounter() {
            val counterId = AiModelConfiguration.Id("counter")
            counterResolved = resolved.copy(modelConfiguration = resolved.modelConfiguration.copy(id = counterId))
            resolved = resolved.copy(modelConfiguration = resolved.modelConfiguration.copy(compaction = resolved.modelConfiguration.compaction.copy(
                tokenCounting = AiModelConfiguration.TokenCounting(AiModelConfiguration.TokenCounting.Mode.MODEL_CONFIGURATION, counterId))))
        }
        private val service = ConversationCompactionService(configurations, history, summaries, committer, coordinator, events, runtimeProvider)
        suspend fun apply(alreadyCompacted: Boolean = false, extraTokens: Int = 0, owner: suspend () -> Unit = {}): Conversation? =
            service.compactIfNeeded(task, conversation, agent, routedRuntime ?: provider, request, alreadyCompacted, extraTokens) {
                order += "owner"; owner()
            }
    }
}
