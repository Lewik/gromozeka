package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.AgentPreloadedTools
import com.gromozeka.domain.tool.ToolAccessPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class AgentCollaborationServiceTest {
    private val now = Instant.fromEpochMilliseconds(1)
    private val actor = User(User.Id("u"), displayName = "User", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
    private val source = AgentEndpoint(Conversation.Id("a"), Conversation.Thread.Id("ta"), AgentDefinition.Id("aa"))
    private val target = AgentEndpoint(Conversation.Id("b"), Conversation.Thread.Id("tb"), AgentDefinition.Id("bb"))
    private val selection = AiRuntimeSelection(AiModelConfiguration.Id("config"))
    private val repo = MemoryRepository()
    private val conversations = mock<ConversationRepository>()
    private val identities = mock<IdentityRepository>()
    private val access = mock<ProjectAccessService>()
    private val agents = mock<AgentDomainService>()
    private val coordinator = mock<ConversationRuntimeCoordinator>()
    private val sync = mock<ConversationRuntimeStateSyncService>()
    private val service = AgentCollaborationService(repo, conversations, identities, access, agents, coordinator, sync)
    private fun agent(id: AgentDefinition.Id) = AgentDefinition(id, name = id.value, prompts = emptyList(), runtimeSelection = selection, type = AgentDefinition.Type.Global, createdAt = now, updatedAt = now)
    private fun conversation(endpoint: AgentEndpoint) = Conversation(endpoint.conversationId, Project.Id("p"), setOf(Conversation.Participant.User(actor.id), Conversation.Participant.Agent(endpoint.agentId)), currentThread = endpoint.threadId, createdAt = now, updatedAt = now)
    private suspend fun setup() {
        Mockito.`when`(identities.findUserById(actor.id)).thenReturn(actor)
        Mockito.`when`(conversations.findById(source.conversationId)).thenReturn(conversation(source))
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(conversation(target))
        Mockito.`when`(agents.findById(source.agentId)).thenReturn(agent(source.agentId))
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(agent(target.agentId))
        Mockito.`when`(coordinator.schedulingSnapshot(target.conversationId)).thenReturn(ConversationRuntimeSchedulingSnapshot(target.conversationId))
    }
    private fun targetOnAnotherRuntime() = agent(target.agentId).copy(
        runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("other-runtime")),
        tools = AgentPreloadedTools(listOf("grz_visual")),
    )

    @Test fun `peer resolution returns checked identity across runtimes without runtime or history effects`(): Unit = runBlocking {
        setup()
        val definition = targetOnAnotherRuntime()
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(definition)
        val repository = mock<AgentCollaborationRepository>()
        val history = mock<ConversationDomainService>()
        val reader = AgentCollaborationService(repository, conversations, identities, access, agents, coordinator, sync, history)
        val resolved = reader.resolveEligibleTarget(source, actor.id, "b")
        assertEquals(target, resolved.endpoint)
        assertEquals(conversation(target), resolved.conversation)
        assertEquals(definition, resolved.agent)
        Mockito.verify(access, Mockito.times(2)).requirePermission(actor.id, Project.Id("p"), ProjectPermission.WRITE)
        Mockito.verifyNoInteractions(repository, coordinator, sync, history)
        assertTrue(reader.wakeups.tryReceive().isFailure)
        assertTrue(Mockito.mockingDetails(conversations).invocations.all { it.method.name.substringBefore('-') == "findById" })
    }

    @Test fun `peer resolution requires an explicit connected agent for ambiguous conversations`(): Unit = runBlocking {
        setup()
        val several = conversation(target).copy(participants = conversation(target).participants +
            Conversation.Participant.Agent(AgentDefinition.Id("second-agent")))
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(several)
        assertEquals("Specify target_agent_id when a conversation contains several agents",
            assertFailsWith<IllegalStateException> { service.resolveEligibleTarget(source, actor.id, "b") }.message)
        assertEquals(target, service.resolveEligibleTarget(source, actor.id, "b", "bb").endpoint)
        assertFailsWith<IllegalArgumentException> { service.resolveEligibleTarget(source, actor.id, "b", "not-connected") }
        Mockito.verifyNoInteractions(coordinator, sync)
        assertTrue(repo.items.isEmpty() && repo.deliveries.isEmpty())
    }

    @Test fun `peer resolution rejects local work and preserves peer visibility policy`(): Unit = runBlocking {
        setup()
        assertFailsWith<IllegalArgumentException> { service.resolveEligibleTarget(source, actor.id, "a") }
        for (hidden in listOf(
            conversation(target).copy(projectId = Project.Id("other-project")),
            conversation(target).copy(participants = setOf(Conversation.Participant.Agent(target.agentId))),
            conversation(target).copy(externalChannel = ExternalConversationChannel("telegram", "synthetic", "chat")),
        )) {
            Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(hidden)
            assertFailsWith<IllegalArgumentException> { service.resolveEligibleTarget(source, actor.id, "b", "bb") }
        }
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(conversation(target))
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(agent(target.agentId).copy(toolAccess = ToolAccessPolicy.AllowOnly()))
        assertFailsWith<IllegalArgumentException> { service.resolveEligibleTarget(source, actor.id, "b", "bb") }
        Mockito.verifyNoInteractions(coordinator, sync)
        assertTrue(repo.items.isEmpty() && repo.deliveries.isEmpty())
    }

    @Test fun `peer resolution independently authorizes source and actor`(): Unit = runBlocking {
        setup()
        for (invalidSource in listOf(
            conversation(source).copy(externalChannel = ExternalConversationChannel("telegram", "synthetic", "chat")),
            conversation(source).copy(participants = setOf(Conversation.Participant.Agent(source.agentId))),
            conversation(source).copy(participants = setOf(Conversation.Participant.User(actor.id))),
            conversation(source).copy(currentThread = Conversation.Thread.Id("new-source-thread")),
        )) {
            Mockito.`when`(conversations.findById(source.conversationId)).thenReturn(invalidSource)
            assertFailsWith<IllegalArgumentException> { service.resolveEligibleTarget(source, actor.id, "b") }
        }
        Mockito.`when`(conversations.findById(source.conversationId)).thenReturn(conversation(source))
        Mockito.`when`(identities.findUserById(actor.id)).thenReturn(null)
        assertFailsWith<IllegalArgumentException> { service.resolveEligibleTarget(source, actor.id, "b") }
        Mockito.verifyNoInteractions(coordinator, sync)
        assertTrue(repo.items.isEmpty() && repo.deliveries.isEmpty())
    }

    @Test fun `peer resolution rejects a target branch changed during selection`(): Unit = runBlocking {
        setup()
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(conversation(target),
            conversation(target).copy(currentThread = Conversation.Thread.Id("new-target-thread")))
        assertEquals("History branch changed; collaboration needs a new request",
            assertFailsWith<IllegalArgumentException> { service.resolveEligibleTarget(source, actor.id, "b") }.message)
        Mockito.verifyNoInteractions(coordinator, sync)
        assertTrue(repo.items.isEmpty() && repo.deliveries.isEmpty())
    }

    @Test fun `peer resolution propagates project access denial without resolving an agent`(): Unit = runBlocking {
        setup()
        Mockito.`when`(access.requirePermission(actor.id, Project.Id("p"), ProjectPermission.WRITE))
            .thenThrow(IllegalArgumentException("Access denied"))
        assertEquals("Access denied", assertFailsWith<IllegalArgumentException> {
            service.resolveEligibleTarget(source, actor.id, "b")
        }.message)
        Mockito.verifyNoInteractions(agents, coordinator, sync)
        assertTrue(repo.items.isEmpty() && repo.deliveries.isEmpty())
    }

    @Test fun `runtime selections and preloads do not restrict discovery or informational delivery`(): Unit = runBlocking {
        setup()
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(targetOnAnotherRuntime())
        Mockito.`when`(conversations.findByProject(Project.Id("p"))).thenReturn(listOf(conversation(source), conversation(target)))
        val scheduler = InMemoryConversationRuntimeCoordinator()
        val transport = AgentCollaborationService(repo, conversations, identities, access, agents, scheduler, sync)
        val endpoint = transport.sessions(source, actor.id).single().jsonObject
        assertEquals(target.conversationId.value, endpoint.getValue("conversation_id").jsonPrimitive.content)
        assertEquals(target.agentId.value, endpoint.getValue("agent_id").jsonPrimitive.content)

        val id = transport.send(source, actor.id, "b", null, "Information", "other-runtime-message", false)
        transport.deliverPending()
        transport.deliverPending()
        val queued = scheduler.listPending(target.conversationId).single()
        assertIs<ConversationRuntimeTask.Payload.PostMessage>(queued.payload)
        assertEquals(AgentDelivery.State.QUEUED, repo.findDelivery("$id:input")?.state)
        assertTrue(repo.items.isEmpty())
    }

    @Test fun `unnamed sessions use a display fallback without renaming or conflating the agent name`(): Unit = runBlocking {
        setup()
        for (title in listOf("", " \t\n")) {
            val unnamed = conversation(target).copy(displayName = title)
            Mockito.`when`(conversations.findByProject(Project.Id("p"))).thenReturn(listOf(unnamed))
            Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(unnamed)
            val row = service.sessions(source, actor.id).single().jsonObject
            assertEquals("New conversation", row.getValue("title").jsonPrimitive.content)
            assertEquals(title, row.getValue("stored_title").jsonPrimitive.content)
            assertEquals(JsonPrimitive(true), row["title_is_fallback"])
            assertEquals(target.conversationId.value, row.getValue("conversation_id").jsonPrimitive.content)
            assertEquals(target.agentId.value, row.getValue("agent_name").jsonPrimitive.content)
            assertEquals(title, unnamed.displayName)
        }
        assertTrue(Mockito.mockingDetails(conversations).invocations.all { it.method.name.substringBefore('-') in setOf("findById", "findByProject") })
        assertTrue(Mockito.mockingDetails(coordinator).invocations.all { it.method.name.substringBefore('-') == "schedulingSnapshot" })
        Mockito.verifyNoInteractions(sync)
    }

    @Test fun `named sessions and renames use the latest authorized conversation without changing identity`(): Unit = runBlocking {
        setup()
        val named = conversation(target).copy(displayName = "Original title")
        Mockito.`when`(conversations.findByProject(Project.Id("p"))).thenReturn(listOf(named))
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(named)
        val original = service.sessions(source, actor.id).single().jsonObject
        assertEquals("Original title", original.getValue("title").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), original["title_is_fallback"])
        // Discovery's initial list may predate a rename; sameBoundary reads the current identity.
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(named.copy(displayName = "Renamed title"))
        val renamed = service.sessions(source, actor.id).single().jsonObject
        assertEquals("Renamed title", renamed.getValue("title").jsonPrimitive.content)
        assertEquals("Renamed title", renamed.getValue("stored_title").jsonPrimitive.content)
        assertEquals(original.getValue("conversation_id"), renamed.getValue("conversation_id"))
        assertEquals(original.getValue("agent_id"), renamed.getValue("agent_id"))
        assertTrue(Mockito.mockingDetails(conversations).invocations.all { it.method.name.substringBefore('-') in setOf("findById", "findByProject") })
    }

    @Test fun `session projection reuses the authorized agent definition instead of emitting an empty second lookup`(): Unit = runBlocking {
        setup()
        Mockito.`when`(conversations.findByProject(Project.Id("p"))).thenReturn(listOf(conversation(target)))
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(agent(target.agentId), null)
        val row = service.sessions(source, actor.id).single().jsonObject
        assertEquals(target.agentId.value, row.getValue("agent_name").jsonPrimitive.content)
        Mockito.verify(agents, Mockito.times(1)).findById(target.agentId)
    }

    @Test fun `selected session inspection bypasses list scans and remains a read-only paused observation`(): Unit = runBlocking {
        setup()
        val paused = ConversationRuntimeSchedulingSnapshot(target.conversationId,
            state = ConversationExecutionState(target.conversationId, ConversationExecutionState.ControlState.PAUSED, null, updatedAt = now))
        Mockito.`when`(coordinator.schedulingSnapshot(target.conversationId)).thenReturn(paused)
        repo.items["wait"] = AgentRequest("wait", source, target, actor.id, "PRIVATE_REQUEST_BODY", now,
            state = AgentRequest.State.WAITING_RESULT, waitFor = listOf("known-command"))
        val itemsBefore = repo.items.toMap()
        val history = mock<ConversationDomainService>()
        val reader = AgentCollaborationService(repo, conversations, identities, access, agents, coordinator, sync, history)
        val row = reader.sessions(source, actor.id, "b", "bb", details = true).single().jsonObject
        val runtime = row.getValue("runtime").jsonObject
        assertEquals("tb", row.getValue("thread_id").jsonPrimitive.content)
        assertEquals("PAUSED", runtime.getValue("foreground_state").jsonPrimitive.content)
        assertEquals(JsonPrimitive(1), runtime.getValue("agent_requests").jsonObject["incoming_waiting_result"])
        assertFalse(row.toString().contains("PRIVATE_REQUEST_BODY"))
        assertEquals(itemsBefore, repo.items)
        assertTrue(repo.deliveries.isEmpty())
        assertTrue(reader.wakeups.tryReceive().isFailure)
        Mockito.verify(conversations, Mockito.never()).findByProject(Project.Id("p"))
        Mockito.verify(coordinator).schedulingSnapshot(target.conversationId)
        Mockito.verifyNoMoreInteractions(coordinator)
        Mockito.verifyNoInteractions(history, sync)
    }

    @Test fun `ineligible selected sessions expose no runtime facts and do not bypass visibility`(): Unit = runBlocking {
        setup()
        for (hidden in listOf(
            conversation(target).copy(projectId = Project.Id("other-project")),
            conversation(target).copy(participants = setOf(Conversation.Participant.User(User.Id("other")), Conversation.Participant.Agent(target.agentId))),
            conversation(target).copy(externalChannel = ExternalConversationChannel("telegram", "connection", "chat")),
        )) {
            Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(hidden)
            assertTrue(service.sessions(source, actor.id, "b", "bb", details = true).isEmpty())
        }
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(conversation(target))
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(agent(target.agentId).copy(toolAccess = ToolAccessPolicy.AllowOnly()))
        assertTrue(service.sessions(source, actor.id, "b", "bb", details = true).isEmpty())
        assertTrue(service.sessions(source, actor.id, "a", "aa", details = true).isEmpty())
        Mockito.verifyNoInteractions(coordinator, sync)
        assertTrue(repo.items.isEmpty())
        assertTrue(repo.deliveries.isEmpty())
    }

    @Test fun `invalid detail selectors fail explicitly and unavailable runtime is not reported idle`(): Unit = runBlocking {
        setup()
        assertFailsWith<IllegalArgumentException> { service.sessions(source, actor.id, details = true) }
        assertFailsWith<IllegalArgumentException> { service.sessions(source, actor.id, agentId = "bb") }
        assertFailsWith<IllegalArgumentException> { service.sessions(source, actor.id, conversationId = " ") }
        Mockito.`when`(coordinator.schedulingSnapshot(target.conversationId)).thenThrow(IllegalStateException("PRIVATE_RUNTIME_ERROR_SENTINEL"))
        val failure = assertFailsWith<IllegalStateException> { service.sessions(source, actor.id, "b", "bb", details = true) }
        assertEquals("Stored Runtime facts are unavailable for this session", failure.message)
        assertNull(failure.cause)
        Mockito.verifyNoInteractions(sync)
        assertTrue(service.wakeups.tryReceive().isFailure)
    }

    @Test fun `default discovery bounds rows and shares one scheduling read per conversation but not between calls`(): Unit = runBlocking {
        setup()
        val participants = (0 until 205).map { AgentDefinition.Id("participant-$it") }
        val multi = conversation(target).copy(participants =
            (participants.map { Conversation.Participant.Agent(it) } + Conversation.Participant.User(actor.id)).toSet())
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(multi)
        Mockito.`when`(conversations.findByProject(Project.Id("p"))).thenReturn(listOf(multi))
        participants.forEach { Mockito.`when`(agents.findById(it)).thenReturn(agent(it)) }
        val paused = ConversationRuntimeSchedulingSnapshot(target.conversationId,
            state = ConversationExecutionState(target.conversationId, ConversationExecutionState.ControlState.PAUSED, null, updatedAt = now))
        Mockito.`when`(coordinator.schedulingSnapshot(target.conversationId)).thenReturn(paused, ConversationRuntimeSchedulingSnapshot(target.conversationId))
        val first = service.sessions(source, actor.id)
        assertEquals(200, first.size)
        assertTrue(first.all { it.jsonObject.getValue("runtime").jsonObject.getValue("foreground_state").jsonPrimitive.content == "PAUSED" })
        val second = service.sessions(source, actor.id)
        assertEquals(200, second.size)
        assertTrue(second.all { it.jsonObject.getValue("runtime").jsonObject.getValue("foreground_state").jsonPrimitive.content == "IDLE" })
        Mockito.verify(coordinator, Mockito.times(2)).schedulingSnapshot(target.conversationId)
        Mockito.verifyNoMoreInteractions(coordinator)
        Mockito.verifyNoInteractions(sync)
    }

    @Test fun `different tool policies remain rejected across runtime selections`(): Unit = runBlocking {
        setup()
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(targetOnAnotherRuntime().copy(toolAccess = ToolAccessPolicy.AllowOnly()))
        Mockito.`when`(conversations.findByProject(Project.Id("p"))).thenReturn(listOf(conversation(source), conversation(target)))
        assertTrue(service.sessions(source, actor.id).isEmpty())
        val error = assertFailsWith<IllegalArgumentException> {
            service.send(source, actor.id, "b", null, "Do not bypass tool policy", "policy-boundary", true)
        }
        assertEquals("This experiment requires matching agent tool policies", error.message)
        assertTrue(repo.deliveries.isEmpty())
    }

    @Test fun `different projects remain rejected across runtime selections`(): Unit = runBlocking {
        setup()
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(targetOnAnotherRuntime())
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(conversation(target).copy(projectId = Project.Id("other-project")))
        val error = assertFailsWith<IllegalArgumentException> {
            service.send(source, actor.id, "b", null, "Do not cross projects", "project-boundary", true)
        }
        assertEquals("Cross-project communication is disabled", error.message)
        assertTrue(repo.deliveries.isEmpty())
    }

    @Test fun `delegation records requester and does not wait for model execution`() = runBlocking {
        setup()
        val id = service.send(source, actor.id, "b", null, "Investigate", "call-1", true)
        assertEquals(source, repo.findRequest(id)?.source)
        assertEquals(target, repo.findRequest(id)?.target)
        assertEquals(1, repo.pendingDeliveries().size)
        Mockito.verifyNoInteractions(coordinator)
    }
    @Test fun `external channels still reject direct collaboration calls`() = runBlocking {
        setup()
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(conversation(target).copy(
            externalChannel = ExternalConversationChannel("telegram", "test", "chat")))
        assertFailsWith<IllegalArgumentException> { service.authorize(target, actor.id) }
        assertFailsWith<IllegalArgumentException> { service.send(source, actor.id, "b", null, "No Telegram delegation", "external", true) }
        assertTrue(repo.pendingDeliveries().isEmpty())
    }
    @Test fun `request cycles and self delegation are rejected`() = runBlocking {
        setup()
        service.send(source, actor.id, "b", null, "Investigate", "call-1", true)
        assertFailsWith<IllegalArgumentException> { service.send(target, actor.id, "a", null, "Wait for yourself", "call-2", true) }
        assertFailsWith<IllegalArgumentException> { service.send(source, actor.id, "a", null, "Self", "call-3", true) }
        service.send(target, actor.id, "a", null, "Information without reply obligation", "call-4", false)
        Unit
    }
    @Test fun `response uses recorded requester and cannot answer a foreign request`() = runBlocking {
        setup()
        val id = service.send(source, actor.id, "b", null, "Investigate", "call-1", true)
        assertFailsWith<IllegalArgumentException> { service.reply(source, actor.id, id, "Forged") }
        service.reply(target, actor.id, id, "Result")
        service.reply(target, actor.id, id, "Result")
        val result = repo.pendingDeliveries().single { it.kind == AgentDelivery.Kind.RESULT }
        assertEquals(source, result.target)
        assertEquals(2, repo.pendingDeliveries().size)
        assertEquals(AgentRequest.State.COMPLETED, repo.findRequest(id)?.state)
    }
    @Test fun `removed user changed branch and different tool policy cannot receive delegated work`() = runBlocking {
        setup()
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(conversation(target).copy(participants = setOf(Conversation.Participant.User(User.Id("other")), Conversation.Participant.Agent(target.agentId))))
        assertFailsWith<IllegalArgumentException> { service.send(source, actor.id, "b", null, "Private", "call-1", true) }
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(conversation(target))
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(agent(target.agentId).copy(toolAccess = ToolAccessPolicy.AllowOnly()))
        assertFailsWith<IllegalArgumentException> { service.send(source, actor.id, "b", null, "Private", "call-2", true) }
        assertFailsWith<IllegalArgumentException> { service.authorize(target.copy(threadId = Conversation.Thread.Id("old")), actor.id) }
        assertTrue(repo.pendingDeliveries().isEmpty())
    }
    @Test fun `paused target retains pending delivery without waking it`() = runBlocking {
        setup()
        service.send(source, actor.id, "b", null, "Investigate", "call-1", true)
        Mockito.`when`(coordinator.schedulingSnapshot(target.conversationId)).thenReturn(ConversationRuntimeSchedulingSnapshot(target.conversationId,
            state = ConversationExecutionState(target.conversationId, ConversationExecutionState.ControlState.PAUSED, null, updatedAt = now)))
        service.deliverPending()
        assertEquals(AgentDelivery.State.PENDING, repo.pendingDeliveries().single().state)
    }
    @Test fun `paused first page does not starve later deliveries and a new pass revisits paused messages`() = runBlocking {
        setup()
        val scheduler = InMemoryConversationRuntimeCoordinator()
        var paused = true
        val controlled = object : ConversationRuntimeCoordinator by scheduler {
            override suspend fun schedulingSnapshot(conversationId: Conversation.Id): ConversationRuntimeSchedulingSnapshot =
                if (paused && conversationId == target.conversationId) ConversationRuntimeSchedulingSnapshot(conversationId,
                    state = ConversationExecutionState(conversationId, ConversationExecutionState.ControlState.PAUSED, null, updatedAt = now))
                else scheduler.schedulingSnapshot(conversationId)
        }
        val transport = AgentCollaborationService(repo, conversations, identities, access, agents, controlled, sync)
        // Equal timestamps force the cursor to use id as well as createdAt. The first page remains pending.
        val deliveries = (0 until 130).map { index ->
            val toPaused = index < 64
            AgentDelivery("page-${index.toString().padStart(3, '0')}", null,
                if (toPaused) source else target, if (toPaused) target else source,
                actor.id, AgentDelivery.Kind.MESSAGE, "Information $index", now)
        }
        deliveries.forEach { repo.create(null, it) }
        transport.deliverPending()
        assertEquals(deliveries.take(64), repo.pendingDeliveries())
        assertEquals(deliveries.drop(64).map { it.id }.toSet(), scheduler.listPending(source.conversationId).map { it.id.value }.toSet())
        assertTrue(scheduler.listPending(target.conversationId).isEmpty())
        transport.deliverPending()
        assertEquals(66, scheduler.listPending(source.conversationId).size)
        paused = false
        transport.deliverPending()
        assertTrue(repo.pendingDeliveries().isEmpty())
        assertEquals(deliveries.take(64).map { it.id }.toSet(), scheduler.listPending(target.conversationId).map { it.id.value }.toSet())
    }
    @Test fun `cancelling a queued request prevents execution`() = runBlocking {
        setup()
        val id = service.send(source, actor.id, "b", null, "Investigate", "call-1", true)
        service.cancel(source, actor.id, id)
        service.deliverPending()
        assertTrue(repo.pendingDeliveries().isEmpty())
        assertEquals(AgentRequest.State.CANCELLED, repo.findRequest(id)?.state)
        assertEquals(AgentDelivery.State.BLOCKED, repo.deliveries.values.single().state)
    }
    @Test fun `request and result cross runtime selections once through the real conversation scheduler`(): Unit = runBlocking {
        setup()
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(targetOnAnotherRuntime())
        val scheduler = InMemoryConversationRuntimeCoordinator()
        val transport = AgentCollaborationService(repo, conversations, identities, access, agents, scheduler, sync)
        val id = transport.send(source, actor.id, "b", null, "Investigate", "round-trip", true)
        transport.deliverPending()
        transport.deliverPending()
        val queued = scheduler.schedulingSnapshot(target.conversationId).pendingTasks.single()
        assertEquals(target.agentId, queued.requireAgentInvocation().agentDefinitionId)
        assertEquals(source.agentId, (queued.requireAgentInvocation().userMessage.author as Conversation.Message.Author.Agent).agentDefinitionId)
        assertTrue(transport.validateMessage(queued.requireAgentInvocation().userMessage))
        val owner = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("test"))
        val capabilities = setOf(ConversationRuntimeCapability.CONVERSATION_TURN, ConversationRuntimeCapability.MEMORY_PIPELINE)
        scheduler.claimDeliveredTask(target.conversationId, queued.id, owner, capabilities, emptySet())
        scheduler.markActiveTaskStarted(target.conversationId, queued.id, owner, now)
        transport.reply(target, actor.id, id, "Result")
        scheduler.completeActiveTask(target.conversationId, queued.id, owner, ConversationRuntimeTaskOutcome.CompleteTurn)
        transport.deliverPending()
        transport.deliverPending()
        val result = scheduler.schedulingSnapshot(source.conversationId).pendingTasks.single()
        assertEquals(source.agentId, result.requireAgentInvocation().agentDefinitionId)
        assertEquals("Result", (result.requireAgentInvocation().userMessage.content.single() as Conversation.Message.ContentItem.UserMessage).text)
        assertTrue(transport.validateMessage(result.requireAgentInvocation().userMessage))
        assertTrue(repo.pendingDeliveries().isEmpty())
    }
    @Test fun `stop and interrupt cancel durable waits even without an active foreground turn`() = runBlocking {
        setup()
        val dispatcher = mock<ConversationRuntimeDispatcher>()
        val runtime = ConversationRuntimeApplicationService(dispatcher, sync, mock(), mock(), mock(), mock(), service)
        for (action in listOf(ConversationRuntimeControlAction.STOP, ConversationRuntimeControlAction.INTERRUPT)) {
            val id = service.send(source, actor.id, "b", null, "Wait for a user choice", "idle-$action", true)
            repo.items[id] = requireNotNull(repo.items[id]).copy(state = AgentRequest.State.WAITING_USER)
            Mockito.`when`(dispatcher.controlExecution(source.conversationId, action)).thenReturn(false)
            Mockito.clearInvocations(sync)
            assertTrue(runtime.controlExecution(source.conversationId, action))
            assertEquals(AgentRequest.State.CANCELLED, repo.findRequest(id)?.state)
            Mockito.verify(sync).invalidate(source.conversationId)
            Mockito.verify(sync).invalidate(target.conversationId)
            assertFalse(runtime.controlExecution(source.conversationId, action))
        }
    }
    @Test fun `stopping recipient retries concurrent review revision and notifies requester only once`() = runBlocking {
        setup()
        val id = service.send(source, actor.id, "b", null, "Wait for user", "stop-race", true)
        repo.conflictOnce = true
        assertTrue(service.cancelConversation(target.conversationId))
        assertEquals(AgentRequest.State.CANCELLED, repo.findRequest(id)?.state)
        assertEquals(2L, repo.findRequest(id)?.revision)
        assertFalse(service.cancelConversation(target.conversationId))
        val result = repo.deliveries.values.single { it.kind == AgentDelivery.Kind.RESULT }
        assertEquals(source, result.target)
    }
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)

    private class MemoryRepository : AgentCollaborationRepository {
        val items = mutableMapOf<String, AgentRequest>()
        val deliveries = mutableMapOf<String, AgentDelivery>()
        var conflictOnce = false
        override suspend fun requests(conversationId: Conversation.Id) = items.values.filter { it.source.conversationId == conversationId || it.target.conversationId == conversationId }
        override suspend fun findRequest(id: String) = items[id]
        override suspend fun change(expected: List<AgentRequest>, updated: List<AgentRequest>, deliveries: List<AgentDelivery>): Boolean {
            if (conflictOnce) {
                conflictOnce = false
                expected.forEach { items[it.id] = it.copy(revision = it.revision + 1, state = AgentRequest.State.WAITING_USER) }
                return false
            }
            if (expected.any { items[it.id]?.revision != it.revision }) return false
            updated.forEach { items[it.id] = it }; deliveries.forEach { this.deliveries.putIfAbsent(it.id, it) }; return true
        }
        override suspend fun create(request: AgentRequest?, delivery: AgentDelivery) { request?.let { items.putIfAbsent(it.id, it) }; deliveries.putIfAbsent(delivery.id, delivery) }
        override suspend fun pendingDeliveries(after: AgentCollaborationRepository.DeliveryCursor?) = deliveries.values
            .filter { it.state == AgentDelivery.State.PENDING && (after == null || it.createdAt > after.createdAt || (it.createdAt == after.createdAt && it.id > after.id)) }
            .sortedWith(compareBy<AgentDelivery> { it.createdAt }.thenBy { it.id })
            .take(64)
        override suspend fun findDelivery(id: String) = deliveries[id]
        override suspend fun settleDelivery(delivery: AgentDelivery) { deliveries[delivery.id] = delivery }
        override suspend fun saveDraft(draft: AgentResponseDraft) = error("Not used")
        override suspend fun findDraft(id: String): AgentResponseDraft? = error("Not used")
        override suspend fun failDraft(id: String, reason: String) = error("Not used")
        override suspend fun applyDecision(draft: AgentResponseDraft, activeTaskId: String, decision: AgentResponseDecision, updated: List<AgentRequest>, deliveries: List<AgentDelivery>) = error("Not used")
    }
}
