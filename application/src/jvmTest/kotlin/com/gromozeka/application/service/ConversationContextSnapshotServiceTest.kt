package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.ArtifactRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class ConversationContextSnapshotServiceTest {
    @Test fun `pinned prefix includes available runtime layers without turn preparation or catalog writes`() = runBlocking {
        val f = Fixture()
        val original = listOf(f.user, f.incompleteCall)
        Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(original)
        val snapshot = f.capture()
        assertEquals(f.target, snapshot.target)
        assertEquals(2, snapshot.sourceMessageCount)
        assertEquals(1, snapshot.omittedTrailingMessageCount)
        assertEquals(listOf(f.user.id), snapshot.request.messages.map { it.id })
        assertEquals(f.user.id, snapshot.lastIncludedMessageId)
        assertEquals("base", snapshot.request.systemPrompts.first())
        assertTrue(snapshot.request.systemPrompts.any { "<current_agent>" in it })
        for (block in listOf("catalog", "environment", "skills", "read-only-runtime")) assertContains(snapshot.request.systemPrompts, block)
        assertEquals(AiToolChoice.None, snapshot.request.options.toolChoice)
        assertEquals(ToolAccessPolicy.AllowOnly(), snapshot.request.options.toolAccess)
        assertNull(snapshot.request.options.autoCompactionThresholdTokens)
        assertEquals(1, f.snapshotEnrichments)
        assertEquals(original, listOf(f.user, f.incompleteCall))
        Mockito.verify(f.history).getMessagesByThread(f.target.threadId)
        Mockito.verifyNoMoreInteractions(f.history)
        Mockito.verify(f.catalog).readSnapshot(f.project, f.agent.toolAccess, f.conversation)
        Mockito.verifyNoMoreInteractions(f.catalog)
        Mockito.verify(f.capabilities).cachedPromptFor(emptyList())
        Mockito.verifyNoMoreInteractions(f.capabilities)
        Mockito.verify(f.conversations).getProject(f.conversation.id)
        Mockito.verifyNoMoreInteractions(f.conversations)
        Mockito.verifyNoInteractions(f.artifactRepository, f.contentStore)
    }

    @Test fun `history reader never follows an advanced current branch`() = runBlocking {
        val f = Fixture()
        val advanced = f.conversation.copy(currentThread = Conversation.Thread.Id("advanced"))
        Mockito.`when`(f.conversations.findById(f.conversation.id)).thenReturn(advanced)
        Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(listOf(f.user))
        val snapshot = f.capture()
        assertEquals(f.target.threadId, snapshot.target.threadId)
        Mockito.verify(f.history).getMessagesByThread(f.target.threadId)
        Mockito.verifyNoMoreInteractions(f.history)
        Mockito.verify(f.conversations, Mockito.never()).findById(f.conversation.id)
    }

    @Test fun `missing mandatory cached catalog fails instead of reduced history-only view`() = runBlocking {
        val f = Fixture()
        Mockito.`when`(f.history.getMessagesByThread(f.target.threadId)).thenReturn(listOf(f.user))
        Mockito.`when`(f.capabilities.cachedPromptFor(emptyList())).thenThrow(ContextSnapshotUnavailableException("not ready"))
        assertFailsWith<ContextSnapshotUnavailableException> { f.capture() }
        assertEquals(0, f.snapshotEnrichments)
        Mockito.verifyNoInteractions(f.artifactRepository, f.contentStore)
    }

    @Test fun `ordinary and snapshot assemblers share the unchanged ordered system prefix`() {
        val f = Fixture()
        for (format in AiModelConfiguration.AssistantResponseFormat.entries) {
            val result = assembleConversationSystemPrompts(listOf("first", "second"), f.agent, "catalog", "env", "skills", format, false)
            assertEquals(listOf("first", "second"), result.take(2))
            assertEquals("""
                <current_agent>
                Current server-managed Agent identity (JSON data): {"agent_definition_id":"agent","model_configuration_id":"model"}
                Use `agent_definition_id` when a tool asks for the current Agent id.
                </current_agent>
            """.trimIndent(), result[2])
            assertEquals(listOf("catalog", "env", "skills"), result.subList(3, 6))
            assertEquals(listOfNotNull(AssistantResponseFormatContract.instruction(format, false)), result.drop(6))
        }
    }

    private class Fixture {
        val now = Instant.fromEpochMilliseconds(0)
        val actor = User.Id("operator")
        val project = Project(Project.Id("project"), "Project", createdAt = now, lastUsedAt = now)
        val agent = AgentDefinition(AgentDefinition.Id("agent"), name = "Agent", prompts = emptyList(),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model")), type = AgentDefinition.Type.Global,
            createdAt = now, updatedAt = now)
        val conversation = Conversation(Conversation.Id("target"), project.id,
            setOf(Conversation.Participant.User(actor), Conversation.Participant.Agent(agent.id)),
            currentThread = Conversation.Thread.Id("pinned"), createdAt = now, updatedAt = now)
        val target = AgentEndpoint(conversation.id, conversation.currentThread, agent.id)
        val user = Conversation.Message(Conversation.Message.Id("user"), conversation.id, role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("history")), createdAt = now)
        val incompleteCall = user.copy(id = Conversation.Message.Id("pending"), role = Conversation.Message.Role.ASSISTANT,
            content = listOf(Conversation.Message.ContentItem.ToolCall(Conversation.Message.ContentItem.ToolCall.Id("tool"),
                Conversation.Message.ContentItem.ToolCall.Data("read", JsonObject(emptyMap())))))
        val conversations = Mockito.mock(ConversationDomainService::class.java)
        val history = Mockito.mock(ThreadMessageRepository::class.java)
        val configuration = Mockito.mock(AiConfigurationProvider::class.java)
        val prompts = Mockito.mock(AgentPromptAssemblyService::class.java)
        val settings = Mockito.mock(SettingsProvider::class.java)
        val catalog = Mockito.mock(DistributedAiToolCatalog::class.java)
        val capabilities = Mockito.mock(AiToolCapabilityCatalogService::class.java)
        val skills = Mockito.mock(AgentSkillRuntimeCatalogService::class.java)
        val artifactRepository = Mockito.mock(ArtifactRepository::class.java)
        val contentStore = Mockito.mock(ArtifactContentStore::class.java)
        val baseCatalog = DistributedAiToolCatalogSnapshot(emptyList(), emptyMap(), emptyList(), "revision", "environment")
        var snapshotEnrichments = 0
        val enricher = object : ConversationRequestEnricher {
            override suspend fun enrich(conversationId: Conversation.Id, rootUserMessageId: Conversation.Message.Id,
                turnId: ConversationRuntimeTurnId, model: AiModelSpec, request: AiRuntimeRequest): AiRuntimeRequest = error("Mutable enrichment forbidden")
            override suspend fun enrichSnapshot(conversationId: Conversation.Id, rootUserMessageId: Conversation.Message.Id,
                turnId: ConversationRuntimeTurnId, model: AiModelSpec, request: AiRuntimeRequest): AiRuntimeRequest {
                snapshotEnrichments++
                assertEquals(conversation.id, conversationId)
                return request.copy(systemPrompts = request.systemPrompts + "read-only-runtime")
            }
        }
        init {
            val connection = AiConnection.OpenAiSubscription(AiConnection.Id("connection"), "Connection", enabled = true)
            val model = AiModelConfiguration(agent.runtimeSelection.modelConfigurationId, connection.id, "test", "Test")
            val resolved = ResolvedAiRuntime(connection, model, AiModelSpec("test", AiProvider.OPENAI,
                setOf(AiModelCapability.TEXT_GENERATION), AiModelSpec.Limits(textGeneration = AiModelSpec.Limits.TextGeneration(100_000))))
            Mockito.`when`(configuration.resolveAiRuntime(agent.runtimeSelection)).thenReturn(resolved)
            Mockito.`when`(settings.userProfile).thenReturn(UserProfile())
            runBlocking {
                Mockito.`when`(conversations.getProject(conversation.id)).thenReturn(project)
                Mockito.`when`(catalog.readSnapshot(project, agent.toolAccess, conversation)).thenReturn(baseCatalog)
                Mockito.`when`(skills.prepare(agent, project.id, baseCatalog)).thenReturn(AgentSkillRuntimeCatalog(baseCatalog, "skills"))
                Mockito.`when`(capabilities.cachedPromptFor(emptyList())).thenReturn("catalog")
                Mockito.`when`(prompts.assembleSystemPrompt(agent, RuntimeEnvironmentContext.ProjectBound(project,
                    RuntimeEnvironmentExecutor.Server, RuntimeEnvironmentContext.ConversationInfo(conversation.id, conversation.displayName))))
                    .thenReturn(listOf("base"))
            }
        }
        suspend fun capture() = ConversationContextSnapshotService(conversations, history, configuration, prompts, settings,
            catalog, skills, capabilities, AiToolRuntimeCatalogService(), MessageTemporalContextService(), StickyMessageInstructionService(),
            ConversationArtifactApplicationService(artifactRepository, contentStore), listOf(enricher)).capture(target, conversation, agent, actor)
    }
}
