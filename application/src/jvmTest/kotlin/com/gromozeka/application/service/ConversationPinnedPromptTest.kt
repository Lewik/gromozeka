package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.*
import com.gromozeka.application.service.memory.callMemoryStageWithRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class ConversationPinnedPromptTest {
    private val now = Instant.fromEpochMilliseconds(0)
    private val owner = Conversation.Id("semantic-owner")
    private val project = Project(Project.Id("project"), "Project", createdAt = now, lastUsedAt = now)
    private val context = RuntimeEnvironmentContext.ProjectBound(project, RuntimeEnvironmentExecutor.Server,
        RuntimeEnvironmentContext.ConversationInfo(owner, "Owner"))

    @Test fun `empty is byte and identity preserved and one block contains text only`() = runBlocking<Unit> {
        val pins = ReadOnlyTestPins(); val helper = ConversationPinnedPrompt(pins)
        val base = listOf("", "  base\r\n", "last enricher")
        assertSame(base, helper.append(owner, base))
        pins.values = listOf(testPin(owner, "  Б 👩🏽‍💻\r\n", 2), testPin(owner, "א", 1))
        val result = helper.append(owner, base)
        assertEquals(base, result.dropLast(1))
        assertEquals("Pinned conversation context:\n\nא\n\n---\n\n  Б 👩🏽‍💻\r\n", result.last())
        assertFalse(result.last().contains("PRIVATE_"))
        assertEquals(listOf(owner, owner), pins.reads)
    }

    @Test fun `read failure and cancellation never become empty success`() = runBlocking<Unit> {
        val pins = ReadOnlyTestPins(); val helper = ConversationPinnedPrompt(pins)
        pins.failure = IllegalStateException("PRIVATE_CONTEXT PRIVATE_REASONING")
        val error = assertFailsWith<ConversationPinReadFailedException> { helper.append(owner, emptyList<String>()) }
        assertFalse(error.toString().contains("PRIVATE_")); assertNull(error.cause)
        val cancelled = CancellationException("cancel")
        pins.failure = cancelled
        assertSame(cancelled, assertFailsWith<CancellationException> { helper.append(owner, emptyList<String>()) })
    }

    @Test fun `memory stage matrix uses semantic owner not synthetic ids or attribution and reads each retry`() = runBlocking<Unit> {
        val pins = ReadOnlyTestPins().apply { values = listOf(testPin(owner, "MEMORY_PIN_A")) }
        val helper = ConversationPinnedPrompt(pins)
        val sent = mutableListOf<AiRuntimeRequest>()
        var retry = true
        val runtime = object : AiRuntime {
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                sent += request
                if (retry) {
                    retry = false
                    pins.values = listOf(testPin(owner, "MEMORY_PIN_B"))
                    throw IllegalStateException("connection reset")
                }
                return AiRuntimeResponse(emptyList())
            }
            override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = error("unused")
        }
        val bound = helper.bindMemoryRuntime(context, runtime)
        val request = AiRuntimeRequest(listOf("base", "stage policy"), emptyList(), options = AiRuntimeOptions(
            toolChoice = AiToolChoice.None, toolContext = mapOf("conversationId" to "synthetic-or-attributed-id")))
        bound.callMemoryStageWithRetry(request, "ingest-planner", "synthetic", maxAttempts = 2, timeoutMs = 1000)
        assertTrue(sent[0].systemPrompts.last().contains("MEMORY_PIN_A"))
        assertTrue(sent[1].systemPrompts.last().contains("MEMORY_PIN_B"))
        // All builders share this call boundary: read/write/maintenance, pre-compaction and structured repair.
        for (stage in listOf("read-planner", "read-selector", "read-answer", "thread-context-compactor", "write-router",
            "write-retrieval", "claim-extractor", "claim-reconciler", "entity-canonicalizer", "note-constructor",
            "note-reconciler", "action-item-updater", "forget-planner", "note-consolidator", "repair-planner", "entity-maintenance")) {
            bound.callMemoryStageWithRetry(request, stage, "synthetic", maxAttempts = 1, timeoutMs = 1000)
        }
        assertEquals(sent.size, pins.reads.size)
        assertTrue(pins.reads.all { it == owner })
        assertTrue(sent.all { it.systemPrompts.take(2) == request.systemPrompts && it.systemPrompts.size == 3 })
        assertTrue(sent.all { it.options.toolContext == request.options.toolContext && it.tools.isEmpty() })
        pins.values = emptyList()
        bound.call(request)
        assertSame(request, sent.last())
        assertTrue(sent.first().systemPrompts.last().contains("MEMORY_PIN_A"), "Captured in-flight request is immutable")
    }

    @Test fun `standalone and workspace maintenance cannot inherit pins from usage attribution`() = runBlocking<Unit> {
        val pins = ReadOnlyTestPins().apply { failure = IllegalStateException("Must not read") }
        val helper = ConversationPinnedPrompt(pins)
        val runtime = Mockito.mock(AiRuntime::class.java)
        assertSame(runtime, helper.bindMemoryRuntime(RuntimeEnvironmentContext.Standalone(RuntimeEnvironmentExecutor.Server), runtime))
        assertSame(runtime, helper.bindMemoryRuntime(RuntimeEnvironmentContext.ProjectBound(project, RuntimeEnvironmentExecutor.Server), runtime))
        assertTrue(pins.reads.isEmpty())
    }

    @Test fun `memory resolver supplies explicit Conversation owner while preserving assembled base prompts`() = runBlocking<Unit> {
        val agent = AgentDefinition(AgentDefinition.Id("memory-agent"), name = "Agent", prompts = emptyList(),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model")), type = AgentDefinition.Type.Global, createdAt = now, updatedAt = now)
        val conversation = Conversation(owner, project.id, setOf(Conversation.Participant.User(User.Id("user"))),
            currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now)
        val conversations = Mockito.mock(ConversationDomainService::class.java)
        Mockito.`when`(conversations.findById(owner)).thenReturn(conversation)
        Mockito.`when`(conversations.loadCurrentMessages(owner)).thenReturn(emptyList())
        Mockito.`when`(conversations.getProject(owner)).thenReturn(project)
        val defaults = Mockito.mock(DefaultAgentProvider::class.java)
        Mockito.`when`(defaults.getDefault()).thenReturn(agent)
        val assembly = Mockito.mock(AgentPromptAssemblyService::class.java)
        val originalContext = RuntimeEnvironmentContext.ProjectBound(project, RuntimeEnvironmentExecutor.Server)
        Mockito.`when`(assembly.assembleSystemPrompt(agent, originalContext)).thenReturn(listOf("unchanged base"))
        Mockito.`when`(assembly.assembleSystemPrompt(agent, RuntimeEnvironmentContext.Standalone(RuntimeEnvironmentExecutor.Server))).thenReturn(listOf("standalone base"))
        val tools = Mockito.mock(AiToolProvider::class.java)
        Mockito.`when`(tools.getTools()).thenReturn(emptyList())
        val resolver = com.gromozeka.application.service.memory.MemoryOperationContextResolver(conversations,
            Mockito.mock(AgentDomainService::class.java), assembly, defaults, Mockito.mock(WorkspaceDomainService::class.java),
            Mockito.mock(ProjectDomainService::class.java), ConversationRuntimeExecutorDescriptor(
                ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("server")), setOf(ConversationRuntimeCapability.MEMORY_PIPELINE)),
            tools, Mockito.mock(com.gromozeka.domain.repository.MessageRepository::class.java),
            Mockito.mock(com.gromozeka.domain.repository.ThreadMessageRepository::class.java))
        val resolved = resolver.resolveConversation(owner)
        assertEquals(owner, (resolved.runtimeContext as RuntimeEnvironmentContext.ProjectBound).conversation?.id)
        assertEquals(listOf("unchanged base"), resolved.systemPrompts)
        assertNull(resolver.resolveStandalone().conversation)
        assertIs<RuntimeEnvironmentContext.Standalone>(resolver.resolveStandalone().runtimeContext)
    }

    @Test fun `suggested replies make a fresh pin read without changing their tool-free contract`() = runBlocking<Unit> {
        val pins = ReadOnlyTestPins().apply { values = listOf(testPin(owner, "SUGGESTION_PIN")) }
        val seen = mutableListOf<AiRuntimeRequest>()
        val runtime = object : AiRuntime {
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                seen += request
                return AiRuntimeResponse(listOf(AiAssistantMessage(listOf(Conversation.Message.ContentItem.AssistantMessage(
                    Conversation.Message.StructuredText("", suggestedReplies = listOf("Next")))))))
            }
            override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = error("unused")
        }
        val provider = Mockito.mock(AiRuntimeProvider::class.java) { runtime }
        val service = SuggestedRepliesGenerationService(provider, ConversationPinnedPrompt(pins), Mockito.mock(AiConfigurationProvider::class.java))
        val agent = AgentDefinition.Id("agent")
        val conversation = Conversation(owner, project.id, setOf(Conversation.Participant.User(User.Id("user")), Conversation.Participant.Agent(agent)),
            currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now)
        val message = Conversation.Message(Conversation.Message.Id("message"), owner, role = Conversation.Message.Role.ASSISTANT,
            content = listOf(Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText("Answer"))), createdAt = now)
        assertEquals(listOf("Next"), service.generate(conversation, listOf(message), message, agent,
            AiRuntimeSelection(AiModelConfiguration.Id("model")), User.Id("user"), "suggestions"))
        assertEquals(listOf(owner), pins.reads)
        assertTrue(seen.single().systemPrompts.last().contains("SUGGESTION_PIN"))
        assertEquals(AiToolChoice.None, seen.single().options.toolChoice)
        assertEquals("SUGGESTED_REPLIES", seen.single().options.usagePurpose)
    }
}
