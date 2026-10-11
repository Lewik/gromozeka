package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.slot.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class SlotRequestEnricherTest {
    @Test fun `held lease adds only release without any discovery history`() = runBlocking {
        val f = Fixture()
        val existing = tool("grz_read_file")
        val request = f.request().copy(tools = listOf(existing))
        val enriched = f.enrich(request)
        assertEquals(listOf("grz_read_file", "grz_slot_release"), enriched.tools.map { it.definition.name })
        assertSame(existing, enriched.tools.first())
        assertContains(enriched.systemPrompts.last(), "mount-7")
        assertEquals(request.messages, enriched.messages)
        assertEquals(request.options, enriched.options)
    }

    @Test fun `compacted or replaced history does not drop release for same conversation`() = runBlocking {
        val f = Fixture()
        val compacted = Conversation.Message(
            id = Conversation.Message.Id("checkpoint"), conversationId = f.conversation.id,
            role = Conversation.Message.Role.USER, createdAt = NOW,
            content = listOf(Conversation.Message.ContentItem.ContextCompactionResult(
                payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary("Synthetic summary"),
                origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY,
                coverage = Conversation.Message.ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                sourceMessageIds = listOf(Conversation.Message.Id("old-discovery")),
            )),
        )
        val request = f.request().let { it.copy(messages = listOf(compacted), options = it.options.copy(
            toolContext = it.options.toolContext + (TOOL_CONTEXT_THREAD_ID to "new-history") +
                (TOOL_CONTEXT_AGENT_DEFINITION_ID to "another-agent"),
        )) }
        assertEquals(listOf("grz_slot_release"), f.enrich(request).tools.map { it.definition.name })
        assertEquals(listOf("grz_slot_release"), f.enrich(f.request()).tools.map { it.definition.name })
    }

    @Test fun `no active current occupation never preloads release`() = runBlocking {
        val f = Fixture()
        val original = f.views.single()
        for (views in listOf(
            emptyList(),
            listOf(original.copy(snapshot = original.snapshot.copy(leases = emptyList()))),
            listOf(original.copy(snapshot = original.snapshot.copy(leases = listOf(f.lease.copy(releasedAt = NOW))))),
            listOf(original.copy(snapshot = original.snapshot.copy(leases = listOf(f.lease.copy(conversationId = Conversation.Id("fork")))))),
            listOf(original.copy(snapshot = original.snapshot.copy(leases = listOf(f.lease.copy(actorUserId = User.Id("other")))))),
            listOf(original.copy(snapshot = original.snapshot.copy(slot = f.slot.copy(projectId = Project.Id("other"))))),
        )) {
            f.setViews(views)
            assertTrue(f.enrich(f.request()).tools.isEmpty())
        }
        assertTrue(f.contracts.findAll().isEmpty())
    }

    @Test fun `current versioned release is selected and not duplicated`() = runBlocking {
        val f = Fixture()
        f.contracts.resolveAll(listOf(AiToolDescriptor(tool("grz_slot_release", "Old contract").definition, f.release.metadata)))
        val first = f.enrich(f.request())
        assertEquals("grz_slot_release__v2", first.tools.single().definition.name)
        val second = f.enrich(f.request().copy(tools = first.tools))
        assertEquals(first.tools, second.tools)
        assertEquals(AiToolLoadingPolicy.ON_DEMAND, second.tools.single().metadata.loadingPolicy)
    }

    @Test fun `logical and exact revision policies remain authoritative`() = runBlocking {
        val f = Fixture()
        val old = f.contracts.resolveAll(listOf(AiToolDescriptor(tool("grz_slot_release", "Old contract").definition, f.release.metadata))).single()
        val name = ToolSelector.ByName(QualifiedToolName("gromozeka", "grz_slot_release"))
        assertTrue(f.enrich(f.request(ToolAccessPolicy.DenyListed(setOf(name)))).tools.isEmpty())
        assertTrue(f.enrich(f.request(ToolAccessPolicy.AllowOnly())).tools.isEmpty())
        assertTrue(f.enrich(f.request(ToolAccessPolicy.AllowOnly(setOf(ToolSelector.ExactRevision(ToolContractFingerprint(old.fingerprint)))))).tools.isEmpty())
        assertEquals("grz_slot_release__v2", f.enrich(f.request(ToolAccessPolicy.AllowOnly(setOf(name)))).tools.single().definition.name)
        val current = f.contracts.findAll().single { it.modelName == "grz_slot_release__v2" }
        val exact = ToolSelector.ExactRevision(ToolContractFingerprint(current.fingerprint))
        assertEquals("grz_slot_release__v2", f.enrich(f.request(ToolAccessPolicy.AllowOnly(setOf(exact)))).tools.single().definition.name)
        assertTrue(f.enrich(f.request(ToolAccessPolicy.DenyListed(setOf(exact)))).tools.isEmpty())
    }

    @Test fun `unavailable foreign-source and worker tools are not release substitutes`() = runBlocking {
        for (callback in listOf(
            tool("grz_slot_release", available = false),
            tool("grz_slot_release", source = "mcp:external"),
            tool("grz_slot_release", scope = AiToolExecutionScope.WORKER),
            tool("grz_slot_reclaim"),
        )) {
            val f = Fixture(callback)
            assertTrue(f.enrich(f.request()).tools.isEmpty())
        }
    }

    @Test fun `unbound automatic wait is reconstructed without discovery or history and does not preload release`() = runBlocking {
        val f = Fixture()
        f.setViews(emptyList())
        f.automatic = listOf(SlotRequest("waiting", "key", null, f.conversation.id, f.user.id,
            AgentDefinition.Id("agent"), SlotAccess.WRITE, NOW, projectId = f.conversation.projectId))
        val enriched = f.enrich(f.request())
        assertContains(enriched.systemPrompts.last(), "automatic_requests")
        assertContains(enriched.systemPrompts.last(), "waiting")
        assertTrue(enriched.tools.isEmpty())
        assertFalse("mount-7" in enriched.systemPrompts.last())
    }

    @Test fun `snapshot retains current slot and automatic wait context without resolving release contracts`() = runBlocking {
        val f = Fixture()
        f.automatic = listOf(SlotRequest("waiting", "key", null, f.conversation.id, f.user.id,
            AgentDefinition.Id("agent"), SlotAccess.WRITE, NOW, projectId = f.conversation.projectId))
        val request = f.request()
        val snapshot = f.enrich(request, readOnly = true)
        assertTrue(snapshot.tools.isEmpty())
        assertTrue(f.contracts.findAll().isEmpty())
        assertContains(snapshot.systemPrompts.last(), "mount-7")
        assertContains(snapshot.systemPrompts.last(), "waiting")
        val ordinary = f.enrich(request)
        assertEquals(snapshot.systemPrompts, ordinary.systemPrompts)
        assertEquals(listOf("grz_slot_release"), ordinary.tools.map { it.definition.name })
        assertFalse(f.contracts.findAll().isEmpty())
    }

    private class Fixture(val release: AiToolCallback = tool("grz_slot_release")) {
        val user = User(User.Id("user"), displayName = "User", status = User.Status.ACTIVE, createdAt = NOW, updatedAt = NOW)
        val conversation = Conversation(Conversation.Id("conversation"), Project.Id("project"),
            setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(AgentDefinition.Id("agent"))),
            currentThread = Conversation.Thread.Id("history"), createdAt = NOW, updatedAt = NOW)
        val slot = Slot("slot-7", 7, user.id, conversation.projectId, Workspace.Id("workspace-7"), WorkspaceMount.Id("mount-7"), createdAt = NOW)
        val lease = SlotLease("lease-7", 7, conversation.id, user.id, AgentDefinition.Id("agent"), SlotAccess.WRITE, NOW)
        var views = listOf(SlotView(SlotSnapshot(slot, listOf(lease), emptyList()), "worker", "/synthetic-checkout"))
            private set
        var automatic: List<SlotRequest> = emptyList()
        val users = Mockito.mock(UserDirectoryService::class.java)
        val slots = Mockito.mock(SlotApplicationService::class.java)
        val contracts = InMemoryAiToolContractRepository()
        private val catalog = DistributedAiToolCatalog(
            Mockito.mock(ConversationRuntimeWorkerRegistry::class.java), Mockito.mock(WorkspaceDomainService::class.java),
            object : AiToolProvider { override fun getTools() = listOf(release, tool("grz_slot_list"), tool("grz_slot_acquire")) },
            Mockito.mock(WorkerAccessService::class.java), contracts,
        )
        private val enricher = SlotRequestEnricher(slots, users, catalog)
        suspend fun setViews(value: List<SlotView>) { views = value }
        fun request(policy: ToolAccessPolicy = ToolAccessPolicy.DenyListed()) = AiRuntimeRequest(
            systemPrompts = listOf("system"), messages = emptyList(), options = AiRuntimeOptions(toolAccess = policy,
                toolContext = mapOf(TOOL_CONTEXT_USER_ID to user.id.value, TOOL_CONTEXT_CONVERSATION_ID to conversation.id.value,
                    TOOL_CONTEXT_THREAD_ID to "history", TOOL_CONTEXT_AGENT_DEFINITION_ID to "agent")),
        )
        suspend fun enrich(request: AiRuntimeRequest, readOnly: Boolean = false): AiRuntimeRequest {
            Mockito.`when`(users.findActiveById(user.id)).thenReturn(user)
            Mockito.`when`(slots.authorizeConversation(user, conversation.id, ProjectPermission.READ)).thenReturn(conversation)
            Mockito.`when`(slots.catalog(user, conversation.projectId)).thenReturn(SlotCatalogSnapshot(views, automatic))
            return if (readOnly) enricher.enrichSnapshot(conversation.id, Conversation.Message.Id("root"), ConversationRuntimeTurnId("turn"), MODEL, request)
            else enricher.enrich(conversation.id, Conversation.Message.Id("root"), ConversationRuntimeTurnId("turn"), MODEL, request)
        }
    }

    private companion object {
        val NOW = Instant.parse("2026-10-11T00:00:00Z")
        val MODEL = AiModelSpec("synthetic-model", AiProvider.OPENAI, setOf(AiModelCapability.TEXT_GENERATION),
            AiModelSpec.Limits(textGeneration = AiModelSpec.Limits.TextGeneration(10_000)))
        fun tool(name: String, description: String = "Synthetic contract", source: String = "gromozeka",
            scope: AiToolExecutionScope = AiToolExecutionScope.SERVER, available: Boolean = true) = object : AiToolCallback {
            override val definition = AiToolDefinition(name, description, """{"type":"object","properties":{}}""", source)
            override val metadata = AiToolMetadata(executionScope = scope, loadingPolicy = AiToolLoadingPolicy.ON_DEMAND)
            override val available = available
            override fun call(toolInput: String, context: ToolExecutionContext?): String = error("Must not execute")
        }
    }
}
