package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import kotlin.test.*
import kotlin.time.Instant

class ConversationAgentAccessServiceTest {
    @Test fun `all connected agents are checked without restricting model selection or preloads`(): Unit = runBlocking {
        val f = fixture()
        assertEquals(f.targetConversation, f.resolve())
        for (id in listOf(f.a.agentId, f.b.agentId, f.other)) Mockito.verify(f.agents, Mockito.atLeastOnce()).findById(id)
        f.assertOnlyDirectoryReads()
    }

    @Test fun `own conversation is allowed but every attached agent still needs the same policy`(): Unit = runBlocking {
        val f = fixture()
        val own = f.sourceConversation.copy(participants = f.sourceConversation.participants + Conversation.Participant.Agent(f.other))
        Mockito.`when`(f.conversations.findById(f.a.conversationId)).thenReturn(own)
        assertEquals(own, f.service.resolveEligibleConversation(f.a, f.actor.id, f.a.conversationId.value))
        Mockito.`when`(f.agents.findById(f.other)).thenReturn(f.definition(f.other).copy(toolAccess = ToolAccessPolicy.AllowOnly()))
        assertFailsWith<IllegalArgumentException> { f.service.resolveEligibleConversation(f.a, f.actor.id, f.a.conversationId.value) }
        f.assertOnlyDirectoryReads()
    }

    @Test fun `missing inactive and AI-disabled actors fail closed`(): Unit = runBlocking {
        val f = fixture()
        for (actor in listOf(null, f.actor.copy(status = User.Status.DISABLED), f.actor.copy(aiAllowed = false))) {
            Mockito.`when`(f.identities.findUserById(f.actor.id)).thenReturn(actor)
            assertEquals("Active AI-enabled user required", assertFailsWith<IllegalArgumentException> { f.resolve() }.message)
        }
        Mockito.verifyNoInteractions(f.conversations, f.agents, f.projectAccess)
    }

    @Test fun `source attribution remains required and cannot be supplied by a target selector`(): Unit = runBlocking {
        val f = fixture()
        val fields = mapOf(TOOL_CONTEXT_USER_ID to f.actor.id.value, TOOL_CONTEXT_CONVERSATION_ID to f.a.conversationId.value,
            TOOL_CONTEXT_THREAD_ID to f.a.threadId.value, TOOL_CONTEXT_AGENT_DEFINITION_ID to f.a.agentId.value)
        assertFailsWith<IllegalStateException> { f.service.source(null) }
        for (key in fields.keys) {
            assertFailsWith<IllegalStateException> { f.service.source(ToolExecutionContext(fields - key)) }
        }
        assertEquals(f.a to f.actor.id, f.service.source(ToolExecutionContext(fields)))
        // Preserve the existing canUseAi policy; do not silently add the separate human login flag.
        Mockito.`when`(f.identities.findUserById(f.actor.id)).thenReturn(f.actor.copy(loginAllowed = false))
        assertEquals(f.a to f.actor.id, f.service.source(ToolExecutionContext(fields)))
    }

    @Test fun `source participation external channel and current branch remain required`(): Unit = runBlocking {
        val f = fixture()
        for (source in listOf(
            f.sourceConversation.copy(participants = setOf(Conversation.Participant.User(User.Id("other-user")), Conversation.Participant.Agent(f.a.agentId))),
            f.sourceConversation.copy(participants = setOf(Conversation.Participant.User(f.actor.id))),
            f.sourceConversation.copy(externalChannel = ExternalConversationChannel("telegram", "synthetic", "chat")),
            f.sourceConversation.copy(currentThread = Conversation.Thread.Id("new-source-thread")),
        )) {
            Mockito.`when`(f.conversations.findById(f.a.conversationId)).thenReturn(source)
            assertFailsWith<IllegalArgumentException> { f.resolve() }
        }
        Mockito.verifyNoInteractions(f.agents)
    }

    @Test fun `target user project and external boundaries remain required`(): Unit = runBlocking {
        val f = fixture()
        for (target in listOf(
            f.targetConversation.copy(participants = setOf(Conversation.Participant.User(User.Id("other-user")), Conversation.Participant.Agent(f.b.agentId))),
            f.targetConversation.copy(projectId = Project.Id("other-project")),
            f.targetConversation.copy(externalChannel = ExternalConversationChannel("telegram", "synthetic", "chat")),
        )) {
            Mockito.`when`(f.conversations.findById(f.b.conversationId)).thenReturn(target)
            assertFailsWith<IllegalArgumentException> { f.resolve() }
        }
        f.assertOnlyDirectoryReads()
    }

    @Test fun `WRITE is required for the target before returning any conversation data`(): Unit = runBlocking {
        val f = fixture()
        val otherProject = Project.Id("other-project")
        Mockito.`when`(f.conversations.findById(f.b.conversationId)).thenReturn(f.targetConversation.copy(projectId = otherProject))
        val denied = IllegalArgumentException("Synthetic write denial")
        Mockito.`when`(f.projectAccess.requirePermission(f.actor.id, otherProject, ProjectPermission.WRITE)).thenThrow(denied)
        assertSame(denied, assertFailsWith<IllegalArgumentException> { f.resolve() })
        Mockito.verify(f.projectAccess).requirePermission(f.actor.id, otherProject, ProjectPermission.WRITE)
        Mockito.verifyNoInteractions(f.agents)
    }

    @Test fun `source WRITE denial stops before target inspection`(): Unit = runBlocking {
        val f = fixture()
        val denied = IllegalArgumentException("Synthetic write denial")
        Mockito.`when`(f.projectAccess.requirePermission(f.actor.id, f.sourceConversation.projectId, ProjectPermission.WRITE)).thenThrow(denied)
        assertSame(denied, assertFailsWith<IllegalArgumentException> { f.resolve() })
        Mockito.verify(f.conversations, Mockito.never()).findById(f.b.conversationId)
        Mockito.verifyNoInteractions(f.agents)
    }

    @Test fun `missing targets and empty or mixed agent sets cannot grant conversation-wide access`(): Unit = runBlocking {
        val f = fixture()
        Mockito.`when`(f.conversations.findById(f.b.conversationId)).thenReturn(null)
        assertFailsWith<IllegalStateException> { f.resolve() }
        Mockito.`when`(f.conversations.findById(f.b.conversationId)).thenReturn(f.targetConversation.copy(participants = setOf(Conversation.Participant.User(f.actor.id))))
        assertFailsWith<IllegalArgumentException> { f.resolve() }
        Mockito.`when`(f.conversations.findById(f.b.conversationId)).thenReturn(f.targetConversation)
        Mockito.`when`(f.agents.findById(f.other)).thenReturn(f.definition(f.other).copy(toolAccess = ToolAccessPolicy.AllowOnly()))
        assertEquals("This experiment requires matching agent tool policies", assertFailsWith<IllegalArgumentException> { f.resolve() }.message)
        assertFailsWith<IllegalArgumentException> { f.service.resolveEligibleConversation(f.a, f.actor.id, " ") }
    }

    @Test fun `every attached definition including the source must exist`(): Unit = runBlocking {
        for (kind in listOf("source", "first", "second")) {
            val f = fixture()
            val missing = when (kind) { "source" -> f.a.agentId; "first" -> f.b.agentId; else -> f.other }
            Mockito.`when`(f.agents.findById(missing)).thenReturn(null)
            assertEquals(if (kind == "source") "Source agent unavailable" else "Target agent unavailable",
                assertFailsWith<IllegalStateException> { f.resolve() }.message)
        }
    }

    @Test fun `observed target participant additions and removals abort instead of checking a partial set`(): Unit = runBlocking {
        val f = fixture()
        for (changed in listOf(
            f.targetConversation.copy(participants = f.targetConversation.participants + Conversation.Participant.Agent(AgentDefinition.Id("new-agent"))),
            f.targetConversation.copy(participants = f.targetConversation.participants - Conversation.Participant.Agent(f.other)),
        )) {
            Mockito.`when`(f.conversations.findById(f.b.conversationId)).thenReturn(f.targetConversation, changed)
            assertFailsWith<ConversationAgentAccessChangedException> { f.resolve() }
        }
    }

    @Test fun `target branch changes during the read are rejected by the shared endpoint policy`(): Unit = runBlocking {
        val f = fixture()
        Mockito.`when`(f.conversations.findById(f.b.conversationId)).thenReturn(f.targetConversation,
            f.targetConversation.copy(currentThread = Conversation.Thread.Id("new-target-thread")))
        assertEquals("History branch changed; collaboration needs a new request", assertFailsWith<IllegalArgumentException> { f.resolve() }.message)
    }

    @Test fun `final revalidation rejects participant changes after the last agent was checked`(): Unit = runBlocking {
        for (sourceChanged in listOf(false, true)) {
            val f = fixture()
            val original = if (sourceChanged) f.sourceConversation else f.targetConversation
            val changed = original.copy(participants = original.participants + Conversation.Participant.User(User.Id("new-user")))
            // Initial read, first agent, second agent, final endpoint revalidation.
            Mockito.`when`(f.conversations.findById(original.id)).thenReturn(original, original, original, changed)
            val error = assertFailsWith<ConversationAgentAccessChangedException> { f.resolve() }
            assertNull(error.cause)
            assertEquals("Conversation access boundary changed during resolution", error.message)
        }
    }

    @Test fun `agent matches cannot be stitched across a changing source policy`(): Unit = runBlocking {
        val f = fixture()
        val restricted = ToolAccessPolicy.AllowOnly()
        Mockito.`when`(f.agents.findById(f.a.agentId)).thenReturn(f.definition(f.a.agentId), f.definition(f.a.agentId).copy(toolAccess = restricted))
        Mockito.`when`(f.agents.findById(f.other)).thenReturn(f.definition(f.other).copy(toolAccess = restricted))
        // Each pair would individually match; the conversation-wide read must nevertheless fail.
        assertFailsWith<ConversationAgentAccessChangedException> { f.resolve() }
        Mockito.verify(f.agents).findById(f.other)
    }

    @Test fun `actor revocation during final revalidation does not return data`(): Unit = runBlocking {
        val f = fixture()
        // Initial source, source/target for each of two agents, final source check.
        Mockito.`when`(f.identities.findUserById(f.actor.id)).thenReturn(f.actor, f.actor, f.actor, f.actor, f.actor,
            f.actor.copy(status = User.Status.DISABLED))
        assertEquals("Active AI-enabled user required", assertFailsWith<IllegalArgumentException> { f.resolve() }.message)
    }

    @Test fun `cosmetic title changes are not treated as authorization changes`(): Unit = runBlocking {
        val f = fixture()
        val renamed = f.targetConversation.copy(displayName = "A later name", updatedAt = Instant.fromEpochMilliseconds(10))
        Mockito.`when`(f.conversations.findById(f.b.conversationId)).thenReturn(f.targetConversation, renamed)
        assertEquals(renamed, f.resolve())
    }

    @Test fun `the access bean is available with collaboration disabled and without its runtime dependencies`(): Unit = runBlocking {
        val f = fixture()
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(MapPropertySource("synthetic-test", mapOf("gromozeka.collaboration.enabled" to "false")))
            context.beanFactory.registerSingleton("conversations", f.conversations)
            context.beanFactory.registerSingleton("identities", f.identities)
            context.beanFactory.registerSingleton("projectAccess", f.projectAccess)
            context.beanFactory.registerSingleton("agents", f.agents)
            context.register(ConversationAgentAccessService::class.java, AgentCollaborationService::class.java)
            context.refresh()
            assertTrue(context.getBeansOfType(AgentCollaborationService::class.java).isEmpty())
            assertEquals(f.targetConversation, context.getBean(ConversationAgentAccessService::class.java)
                .resolveEligibleConversation(f.a, f.actor.id, f.b.conversationId.value))
        }
    }

    private suspend fun fixture() = Fixture().also { it.setup() }

    private class Fixture {
        private val now = Instant.fromEpochMilliseconds(1)
        val actor = User(User.Id("actor"), displayName = "Synthetic actor", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
        val a = AgentEndpoint(Conversation.Id("source"), Conversation.Thread.Id("source-thread"), AgentDefinition.Id("agent-a"))
        val b = AgentEndpoint(Conversation.Id("target"), Conversation.Thread.Id("target-thread"), AgentDefinition.Id("agent-b1"))
        val other = AgentDefinition.Id("agent-b2")
        val sourceConversation = conversation(a)
        val targetConversation = conversation(b).copy(participants = conversation(b).participants + Conversation.Participant.Agent(other))
        val conversations = Mockito.mock(ConversationRepository::class.java)
        val identities = Mockito.mock(IdentityRepository::class.java)
        val projectAccess = Mockito.mock(ProjectAccessService::class.java)
        val agents = Mockito.mock(AgentDomainService::class.java)
        val service = ConversationAgentAccessService(conversations, identities, projectAccess, agents)
        private fun conversation(endpoint: AgentEndpoint) = Conversation(endpoint.conversationId, Project.Id("project"),
            setOf(Conversation.Participant.User(actor.id), Conversation.Participant.Agent(endpoint.agentId)),
            currentThread = endpoint.threadId, createdAt = now, updatedAt = now)
        fun definition(id: AgentDefinition.Id) = AgentDefinition(id, name = id.value, prompts = emptyList(),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model-${id.value}")),
            tools = AgentPreloadedTools(listOf("synthetic-preload-${id.value}")), type = AgentDefinition.Type.Global, createdAt = now, updatedAt = now)
        suspend fun setup() {
            Mockito.`when`(identities.findUserById(actor.id)).thenReturn(actor)
            Mockito.`when`(conversations.findById(a.conversationId)).thenReturn(sourceConversation)
            Mockito.`when`(conversations.findById(b.conversationId)).thenReturn(targetConversation)
            for (id in listOf(a.agentId, b.agentId, other)) Mockito.`when`(agents.findById(id)).thenReturn(definition(id))
        }
        suspend fun resolve() = service.resolveEligibleConversation(a, actor.id, b.conversationId.value)
        fun assertOnlyDirectoryReads() {
            assertTrue(Mockito.mockingDetails(conversations).invocations.all { it.method.name.substringBefore('-') == "findById" })
            assertTrue(Mockito.mockingDetails(identities).invocations.all { it.method.name.substringBefore('-') == "findUserById" })
            assertTrue(Mockito.mockingDetails(agents).invocations.all { it.method.name.substringBefore('-') == "findById" })
            assertTrue(Mockito.mockingDetails(projectAccess).invocations.all {
                it.method.name.substringBefore('-') == "requirePermission" && it.arguments[2] == ProjectPermission.WRITE
            })
        }
    }
}
