package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.AgentPreloadedTools
import com.gromozeka.domain.tool.ToolAccessPolicy
import kotlinx.coroutines.runBlocking
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
