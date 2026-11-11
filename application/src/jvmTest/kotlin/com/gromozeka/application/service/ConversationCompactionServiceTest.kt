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
        var inspections = 0
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
            override suspend fun inspectContext(request: AiRuntimeRequest): AiContextInspection {
                inspections++; order += "inspect"
                return AiContextInspection(200, AiContextInspection.Precision.ESTIMATE, native)
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
        private val configurations = object : AiConfigurationProvider {
            override val snapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(null)
            override val snapshot: AiCatalogSnapshot get() = error("Not used")
            override fun resolveAiRuntime(selection: AiRuntimeSelection) = resolved
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
        private val service = ConversationCompactionService(configurations, history, summaries, committer, coordinator, events)
        suspend fun apply(alreadyCompacted: Boolean = false, extraTokens: Int = 0, owner: suspend () -> Unit = {}): Conversation? =
            service.compactIfNeeded(task, conversation, agent, provider, AiRuntimeRequest(emptyList(), sources), alreadyCompacted, extraTokens) {
                order += "owner"; owner()
            }
    }
}
