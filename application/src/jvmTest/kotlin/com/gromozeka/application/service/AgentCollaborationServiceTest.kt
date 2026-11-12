package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.AgentPreloadedTools
import com.gromozeka.domain.tool.ToolAccessPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class AgentCollaborationServiceTest {
    private val now = Instant.fromEpochMilliseconds(1)
    private val actor = User(User.Id("u"), displayName = "User", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
    private val source = AgentContextRef(Conversation.Id("a"), Conversation.Thread.Id("ta"), AgentDefinition.Id("aa"))
    private val target = AgentContextRef(Conversation.Id("b"), Conversation.Thread.Id("tb"), AgentDefinition.Id("bb"))
    private val selection = AiRuntimeSelection(AiModelConfiguration.Id("config"))
    private val repo = MemoryRepository()
    private val conversations = mock<ConversationRepository>()
    private val identities = mock<IdentityRepository>()
    private val access = mock<ProjectAccessService>()
    private val agents = mock<AgentDomainService>()
    private val coordinator = mock<ConversationRuntimeCoordinator>()
    private val sync = mock<ConversationRuntimeStateSyncService>()
    private val tabLayouts = mock<UserConversationTabLayoutService>()
    private val messageHistory = mock<ConversationHistoryRepository>()
    private val agentAccess = ConversationAgentAccessService(conversations, identities, access, agents)
    private val service = AgentCollaborationService(repo, conversations, agentAccess, agents, coordinator, sync, tabLayouts, messageHistory)
    private fun agent(id: AgentDefinition.Id) = AgentDefinition(id, name = id.value, prompts = emptyList(), runtimeSelection = selection, type = AgentDefinition.Type.Global, createdAt = now, updatedAt = now)
    private fun conversation(endpoint: AgentContextRef) = Conversation(endpoint.conversationId, Project.Id("p"), setOf(Conversation.Participant.User(actor.id), Conversation.Participant.Agent(endpoint.agentId)), currentThread = endpoint.threadId, createdAt = now, updatedAt = now)
    private suspend fun setup() {
        Mockito.`when`(tabLayouts.snapshot(actor.id)).thenReturn(ConversationTabLayout(listOf(target.conversationId)))
        Mockito.`when`(messageHistory.latestMessageTimes(setOf(target.threadId))).thenReturn(mapOf(target.threadId to null))
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
        val reader = AgentCollaborationService(repository, conversations, agentAccess, agents, coordinator, sync, tabLayouts, messageHistory, history)
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
            conversation(target).copy(participants = setOf(Conversation.Participant.User(User.Id("other-user")), Conversation.Participant.Agent(target.agentId))),
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
            conversation(source).copy(participants = setOf(Conversation.Participant.User(User.Id("other-user")), Conversation.Participant.Agent(source.agentId))),
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
        assertEquals("History branch changed; context is no longer current",
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

    @Test fun `conversation-wide access does not relax legacy self exclusion`(): Unit = runBlocking {
        setup()
        assertEquals(conversation(source), agentAccess.resolveEligibleConversation(source, actor.id, "a"))
        assertEquals("Use the current conversation for local work", assertFailsWith<IllegalArgumentException> {
            service.resolveEligibleTarget(source, actor.id, "a", "aa")
        }.message)
        assertFailsWith<IllegalArgumentException> { service.send(source, actor.id, "a", "aa", "No local send", "self", false) }
        assertTrue(service.sessions(source, actor.id, "a", "aa", details = true).getValue("sessions").jsonArray.isEmpty())
        assertTrue(repo.items.isEmpty() && repo.deliveries.isEmpty())
        Mockito.verifyNoInteractions(coordinator, sync)
    }

    @Test fun `legacy discovery and explicit sends stay agent scoped in mixed-policy conversations`(): Unit = runBlocking {
        setup()
        val other = AgentDefinition.Id("restricted-agent")
        val mixed = conversation(target).copy(participants = conversation(target).participants + Conversation.Participant.Agent(other))
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(mixed)
        Mockito.`when`(conversations.findByProject(Project.Id("p"))).thenReturn(listOf(mixed))
        Mockito.`when`(agents.findById(other)).thenReturn(agent(other).copy(toolAccess = ToolAccessPolicy.AllowOnly()))
        assertFailsWith<IllegalArgumentException> { agentAccess.resolveEligibleConversation(source, actor.id, "b") }
        assertEquals("Specify target_agent_id when a conversation contains several agents", assertFailsWith<IllegalStateException> {
            service.resolveEligibleTarget(source, actor.id, "b")
        }.message)
        assertEquals(target, service.resolveEligibleTarget(source, actor.id, "b", "bb").endpoint)
        assertEquals("bb", service.sessions(source, actor.id).getValue("sessions").jsonArray.single().jsonObject.getValue("agent_id").jsonPrimitive.content)
        val id = service.send(source, actor.id, "b", "bb", "Existing agent-scoped behavior", "mixed-explicit", false)
        assertEquals(target, repo.deliveries.getValue("$id:input").target)
        assertTrue(repo.items.isEmpty())
    }

    @Test fun `runtime selections and preloads do not restrict discovery or informational delivery`(): Unit = runBlocking {
        setup()
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(targetOnAnotherRuntime())
        Mockito.`when`(conversations.findByProject(Project.Id("p"))).thenReturn(listOf(conversation(source), conversation(target)))
        val scheduler = InMemoryConversationRuntimeCoordinator()
        val transport = AgentCollaborationService(repo, conversations, agentAccess, agents, scheduler, sync, tabLayouts, messageHistory)
        val endpoint = transport.sessions(source, actor.id).getValue("sessions").jsonArray.single().jsonObject
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
            val row = service.sessions(source, actor.id).getValue("sessions").jsonArray.single().jsonObject
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
        val original = service.sessions(source, actor.id).getValue("sessions").jsonArray.single().jsonObject
        assertEquals("Original title", original.getValue("title").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), original["title_is_fallback"])
        // Discovery's initial list may predate a rename; sameBoundary reads the current identity.
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(named.copy(displayName = "Renamed title"))
        val renamed = service.sessions(source, actor.id).getValue("sessions").jsonArray.single().jsonObject
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
        val row = service.sessions(source, actor.id).getValue("sessions").jsonArray.single().jsonObject
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
        val reader = AgentCollaborationService(repo, conversations, agentAccess, agents, coordinator, sync, tabLayouts, messageHistory, history)
        val row = reader.sessions(source, actor.id, "b", "bb", details = true).getValue("sessions").jsonArray.single().jsonObject
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
            assertTrue(service.sessions(source, actor.id, "b", "bb", details = true).getValue("sessions").jsonArray.isEmpty())
        }
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(conversation(target))
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(agent(target.agentId).copy(toolAccess = ToolAccessPolicy.AllowOnly()))
        assertTrue(service.sessions(source, actor.id, "b", "bb", details = true).getValue("sessions").jsonArray.isEmpty())
        assertTrue(service.sessions(source, actor.id, "a", "aa", details = true).getValue("sessions").jsonArray.isEmpty())
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
        val first = service.sessions(source, actor.id).getValue("sessions").jsonArray
        assertEquals(AgentSessionQuery.DEFAULT_LIMIT, first.size)
        assertTrue(first.all { it.jsonObject.getValue("runtime").jsonObject.getValue("foreground_state").jsonPrimitive.content == "PAUSED" })
        val second = service.sessions(source, actor.id).getValue("sessions").jsonArray
        assertEquals(AgentSessionQuery.DEFAULT_LIMIT, second.size)
        assertTrue(second.all { it.jsonObject.getValue("runtime").jsonObject.getValue("foreground_state").jsonPrimitive.content == "IDLE" })
        Mockito.verify(coordinator, Mockito.times(2)).schedulingSnapshot(target.conversationId)
        Mockito.verifyNoMoreInteractions(coordinator)
        Mockito.verifyNoInteractions(sync)
    }

    @Test fun `saved open tabs are the default and explicit inspection can include closed tabs`(): Unit = runBlocking {
        setup()
        Mockito.`when`(tabLayouts.snapshot(actor.id)).thenReturn(ConversationTabLayout())
        assertTrue(service.sessions(source, actor.id, "b", "bb").getValue("sessions").jsonArray.isEmpty())
        Mockito.verifyNoInteractions(messageHistory, coordinator)
        val closed = service.sessions(source, actor.id, "b", "bb", query = AgentSessionQuery(openOnly = false))
            .getValue("sessions").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(false), closed.getValue("is_open_tab"))
        assertEquals(kotlinx.serialization.json.JsonNull, closed.getValue("last_message_at"))
        Mockito.verify(tabLayouts, Mockito.times(2)).snapshot(actor.id)
    }

    @Test fun `open and title filters see conversations beyond the old first 200 cap`(): Unit = runBlocking {
        setup()
        val candidates = (0..220).map { i -> sessionConversation("entry-${i.toString().padStart(3, '0')}", if (i == 220) "Wanted [C++]" else "Other $i") }
        directory(candidates.map { it to now }, listOf(candidates.last().id))
        for (query in listOf(AgentSessionQuery(), AgentSessionQuery(openOnly = false, query = "wanted [c++]"),
            AgentSessionQuery(openOnly = false, query = "^wanted", queryMode = AgentSessionQuery.Mode.REGEX))) {
            val page = service.sessions(source, actor.id, query = query)
            val row = page.getValue("sessions").jsonArray.single().jsonObject
            assertEquals("entry-220", row.getValue("conversation_id").jsonPrimitive.content)
            assertEquals(JsonPrimitive(true), row.getValue("is_open_tab"))
            assertEquals(kotlinx.serialization.json.JsonNull, page.getValue("next_cursor"))
        }
        assertTrue(repo.items.isEmpty() && repo.deliveries.isEmpty())
        Mockito.verifyNoInteractions(sync)
    }

    @Test fun `last message ordering and independent strict bounds ignore conversation update times`(): Unit = runBlocking {
        setup()
        val low = Instant.parse("2026-11-01T00:00:00Z")
        val middle = Instant.parse("2026-11-03T00:00:00Z")
        val high = Instant.parse("2026-11-06T00:00:00Z")
        directory(listOf(sessionConversation("old").copy(updatedAt = high) to low,
            sessionConversation("empty").copy(updatedAt = high) to null,
            sessionConversation("new") to high, sessionConversation("middle") to middle))
        suspend fun ids(query: AgentSessionQuery) = service.sessions(source, actor.id, query = query).getValue("sessions").jsonArray
            .map { it.jsonObject.getValue("conversation_id").jsonPrimitive.content }
        assertEquals(listOf("new", "middle", "old", "empty"), ids(AgentSessionQuery()))
        assertEquals(listOf("new", "middle"), ids(AgentSessionQuery(messageGt = low)))
        assertEquals(listOf("old"), ids(AgentSessionQuery(messageLt = middle)))
        assertEquals(listOf("middle"), ids(AgentSessionQuery(messageGt = low, messageLt = high)))
        assertTrue(ids(AgentSessionQuery(messageGt = high)).isEmpty())
        assertTrue(Mockito.mockingDetails(messageHistory).invocations.all { it.method.name == "latestMessageTimes" })
    }

    @Test fun `keyset pages traverse equal times multiple agents and empty threads without duplicates`(): Unit = runBlocking {
        setup()
        val one = sessionConversation("one").let { it.copy(participants = it.participants +
            setOf(Conversation.Participant.Agent(AgentDefinition.Id("second-one")), Conversation.Participant.Agent(AgentDefinition.Id("denied")))) }
        val two = sessionConversation("two").let { it.copy(participants = it.participants + Conversation.Participant.Agent(AgentDefinition.Id("second-two"))) }
        directory(listOf(sessionConversation("empty") to null, two to now, one to now))
        Mockito.`when`(agents.findById(AgentDefinition.Id("denied"))).thenReturn(agent(AgentDefinition.Id("denied")).copy(toolAccess = ToolAccessPolicy.AllowOnly()))
        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = service.sessions(source, actor.id, query = AgentSessionQuery(limit = 2, cursor = cursor))
            val rows = page.getValue("sessions").jsonArray
            assertTrue(rows.size <= 2)
            seen += rows.map { row -> row.jsonObject.let { it.getValue("conversation_id").jsonPrimitive.content + ":" + it.getValue("agent_id").jsonPrimitive.content } }
            cursor = page.getValue("next_cursor").jsonPrimitive.contentOrNull
            assertTrue(++pages <= 3)
        } while (cursor != null)
        assertEquals(listOf("one:agent-one", "one:second-one", "two:agent-two", "two:second-two", "empty:agent-empty"), seen)
        assertEquals(seen.size, seen.distinct().size)
        assertTrue(repo.items.isEmpty() && repo.deliveries.isEmpty())
        assertTrue(service.wakeups.tryReceive().isFailure)
    }

    @Test fun `cursor validates caller query and syntax instead of restarting the first page`(): Unit = runBlocking {
        setup()
        directory(listOf(sessionConversation("one") to now, sessionConversation("two") to now))
        val first = service.sessions(source, actor.id, query = AgentSessionQuery(limit = 1))
        val cursor = requireNotNull(first.getValue("next_cursor").jsonPrimitive.contentOrNull)
        assertFailsWith<IllegalArgumentException> { service.sessions(source, actor.id, query = AgentSessionQuery(openOnly = false, cursor = cursor)) }
        assertFailsWith<IllegalArgumentException> { service.sessions(source, actor.id, query = AgentSessionQuery(query = "one", cursor = cursor)) }
        assertFailsWith<IllegalArgumentException> { service.sessions(source, actor.id, query = AgentSessionQuery(cursor = "not-a-cursor")) }
        val second = service.sessions(source, actor.id, query = AgentSessionQuery(limit = 200, cursor = cursor))
        assertEquals("two", second.getValue("sessions").jsonArray.single().jsonObject.getValue("conversation_id").jsonPrimitive.content)
    }

    @Test fun `message metadata failure cannot masquerade as empty history`(): Unit = runBlocking {
        setup()
        Mockito.`when`(messageHistory.latestMessageTimes(setOf(target.threadId))).thenReturn(emptyMap())
        assertFailsWith<IllegalStateException> { service.sessions(source, actor.id, "b", "bb") }
        Mockito.verifyNoInteractions(coordinator, sync)
    }

    private fun sessionConversation(id: String, title: String = id): Conversation = conversation(
        AgentContextRef(Conversation.Id(id), Conversation.Thread.Id("thread-$id"), AgentDefinition.Id("agent-$id")),
    ).copy(displayName = title)

    private suspend fun directory(entries: List<Pair<Conversation, Instant?>>, open: List<Conversation.Id> = entries.map { it.first.id }) {
        Mockito.`when`(conversations.findByProject(Project.Id("p"))).thenReturn(entries.map { it.first })
        Mockito.`when`(tabLayouts.snapshot(actor.id)).thenReturn(ConversationTabLayout(open))
        entries.forEach { (conversation, _) ->
            Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation)
            conversation.participants.filterIsInstance<Conversation.Participant.Agent>().forEach {
                Mockito.`when`(agents.findById(it.agentDefinitionId)).thenReturn(agent(it.agentDefinitionId))
            }
            Mockito.`when`(coordinator.schedulingSnapshot(conversation.id)).thenReturn(ConversationRuntimeSchedulingSnapshot(conversation.id))
        }
        val times = entries.associate { it.first.currentThread to it.second }
        Mockito.`when`(messageHistory.latestMessageTimes(Mockito.anySet())).thenAnswer { invocation ->
            invocation.getArgument<Set<Conversation.Thread.Id>>(0).associateWith { times.getValue(it) }
        }
    }

    @Test fun `different tool policies remain rejected across runtime selections`(): Unit = runBlocking {
        setup()
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(targetOnAnotherRuntime().copy(toolAccess = ToolAccessPolicy.AllowOnly()))
        Mockito.`when`(conversations.findByProject(Project.Id("p"))).thenReturn(listOf(conversation(source), conversation(target)))
        assertTrue(service.sessions(source, actor.id).getValue("sessions").jsonArray.isEmpty())
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
        assertFailsWith<IllegalArgumentException> { service.authorize(target.address, actor.id) }
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
        assertFailsWith<IllegalArgumentException> { service.reply(source.address, actor.id, id, "Forged") }
        service.reply(target.address, actor.id, id, "Result")
        service.reply(target.address, actor.id, id, "Result")
        val result = repo.pendingDeliveries().single { it.kind == AgentDelivery.Kind.RESULT }
        assertEquals(source, result.target)
        assertEquals(2, repo.pendingDeliveries().size)
        assertEquals(AgentRequest.State.COMPLETED, repo.findRequest(id)?.state)
    }
    @Test fun `removed user and different tool policy cannot receive delegated work`() = runBlocking {
        setup()
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(conversation(target).copy(participants = setOf(Conversation.Participant.User(User.Id("other")), Conversation.Participant.Agent(target.agentId))))
        assertFailsWith<IllegalArgumentException> { service.send(source, actor.id, "b", null, "Private", "call-1", true) }
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(conversation(target))
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(agent(target.agentId).copy(toolAccess = ToolAccessPolicy.AllowOnly()))
        assertFailsWith<IllegalArgumentException> { service.send(source, actor.id, "b", null, "Private", "call-2", true) }
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
        val transport = AgentCollaborationService(repo, conversations, agentAccess, agents, controlled, sync, tabLayouts, messageHistory)
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
        service.cancel(source.address, actor.id, id)
        service.deliverPending()
        assertTrue(repo.pendingDeliveries().isEmpty())
        assertEquals(AgentRequest.State.CANCELLED, repo.findRequest(id)?.state)
        assertEquals(AgentDelivery.State.BLOCKED, repo.deliveries.values.single().state)
    }
    @Test fun `request and result cross runtime selections once through the real conversation scheduler`(): Unit = runBlocking {
        setup()
        Mockito.`when`(agents.findById(target.agentId)).thenReturn(targetOnAnotherRuntime())
        val scheduler = InMemoryConversationRuntimeCoordinator()
        val transport = AgentCollaborationService(repo, conversations, agentAccess, agents, scheduler, sync, tabLayouts, messageHistory)
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
        transport.reply(target.address, actor.id, id, "Result")
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
    private suspend fun advance(ref: AgentContextRef, thread: String): AgentContextRef =
        ref.copy(threadId = Conversation.Thread.Id(thread)).also {
            Mockito.`when`(conversations.findById(ref.conversationId)).thenReturn(conversation(it))
        }

    @Test fun `request and reply survive both history changes and preserve captured provenance`(): Unit = runBlocking {
        setup()
        val scheduler = InMemoryConversationRuntimeCoordinator()
        val transport = AgentCollaborationService(repo, conversations, agentAccess, agents, scheduler, sync, tabLayouts, messageHistory)
        val id = transport.send(source, actor.id, "b", null, "Investigate", "history-round-trip", true)
        val newSource = advance(source, "source-v2")
        val newTarget = advance(target, "target-v2")
        assertEquals(id, transport.send(newSource, actor.id, "b", null, "Investigate", "history-round-trip", true))
        assertEquals(1, repo.items.size)
        assertEquals(1, repo.deliveries.size)
        assertEquals(source, repo.items.getValue(id).source)
        assertEquals(target, repo.items.getValue(id).target)
        assertEquals(listOf(id), transport.context(newTarget.address, actor.id).map { it.id })
        assertEquals(listOf(id), transport.context(newSource.address, actor.id).map { it.id })
        transport.deliverPending()
        transport.deliverPending()
        val invocation = scheduler.listPending(target.conversationId).single().requireAgentInvocation()
        assertEquals(target.agentId, invocation.agentDefinitionId)
        advance(target, "target-v3")
        assertTrue(transport.validateMessage(invocation.userMessage))
        transport.reply(newTarget.address, actor.id, id, "Result")
        advance(source, "source-v3")
        transport.reply(newTarget.address, actor.id, id, "Result")
        transport.deliverPending()
        transport.deliverPending()
        val result = scheduler.listPending(source.conversationId).single().requireAgentInvocation()
        assertEquals(source.agentId, result.agentDefinitionId)
        assertTrue(transport.validateMessage(result.userMessage))
        assertEquals("Result", (result.userMessage.content.single() as Conversation.Message.ContentItem.UserMessage).text)
        assertEquals(source, repo.deliveries.getValue("$id:result").target)
        assertEquals(target, repo.deliveries.getValue("$id:result").source)
        assertEquals(AgentRequest.State.COMPLETED, repo.items.getValue(id).state)
        assertTrue(repo.pendingDeliveries().isEmpty())
    }

    @Test fun `informational delivery is idempotent across history changes without starting a turn`(): Unit = runBlocking {
        setup()
        val scheduler = InMemoryConversationRuntimeCoordinator()
        val transport = AgentCollaborationService(repo, conversations, agentAccess, agents, scheduler, sync, tabLayouts, messageHistory)
        val id = transport.send(source, actor.id, "b", null, "Information", "info", false)
        val newSource = advance(source, "source-v2")
        advance(target, "target-v2")
        assertEquals(id, transport.send(newSource, actor.id, "b", null, "Information", "info", false))
        transport.deliverPending()
        val payload = assertIs<ConversationRuntimeTask.Payload.PostMessage>(scheduler.listPending(target.conversationId).single().payload)
        assertTrue(transport.validateMessage(payload.userMessage))
        assertTrue(repo.items.isEmpty())
        assertEquals(source, repo.deliveries.values.single().source)
        assertEquals(target, repo.deliveries.values.single().target)
    }

    @Test fun `history changes cannot hide a cycle or inherited read only obligation`(): Unit = runBlocking {
        setup()
        val id = service.send(source, actor.id, "b", null, "Read only task", "original", true)
        repo.items[id] = repo.items.getValue(id).copy(readOnly = true, state = AgentRequest.State.WAITING_RESULT, waitFor = listOf("command"))
        advance(source, "source-v2")
        val newTarget = advance(target, "target-v2")
        assertFailsWith<IllegalArgumentException> { service.send(newTarget, actor.id, "a", null, "Cycle", "cycle", true) }
        val third = target.copy(conversationId = Conversation.Id("third"), threadId = Conversation.Thread.Id("third-thread"))
        Mockito.`when`(conversations.findById(third.conversationId)).thenReturn(conversation(third))
        val delegated = service.send(newTarget, actor.id, "third", null, "Delegated read only", "delegated", true)
        assertTrue(repo.items.getValue(delegated).readOnly)
        assertTrue(repo.deliveries.getValue("$delegated:input").readOnly)
        assertEquals(listOf("command"), service.context(newTarget.address, actor.id).single { it.id == id }.waitFor)
    }

    @Test fun `a new conversation or another attached agent cannot inherit or complete a request`(): Unit = runBlocking {
        setup()
        val id = service.send(source, actor.id, "b", null, "Investigate", "isolated", true)
        val fork = target.copy(conversationId = Conversation.Id("fork"))
        Mockito.`when`(conversations.findById(fork.conversationId)).thenReturn(conversation(fork))
        assertTrue(service.context(fork.address, actor.id).isEmpty())
        assertFailsWith<IllegalArgumentException> { service.reply(fork.address, actor.id, id, "Wrong conversation") }
        val otherAgent = target.address.copy(agentId = AgentDefinition.Id("other"))
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(conversation(target).copy(
            participants = conversation(target).participants + Conversation.Participant.Agent(otherAgent.agentId)))
        assertTrue(service.context(otherAgent, actor.id).isEmpty())
        assertFailsWith<IllegalArgumentException> { service.reply(otherAgent, actor.id, id, "Wrong agent") }
        assertFailsWith<IllegalArgumentException> { service.reply(target.address, User.Id("other-user"), id, "Wrong user") }
        assertEquals(AgentRequest.State.WORKING, repo.items.getValue(id).state)
    }

    @Test fun `cancellation after a history change prevents queued execution and late completion`(): Unit = runBlocking {
        setup()
        val scheduler = InMemoryConversationRuntimeCoordinator()
        val transport = AgentCollaborationService(repo, conversations, agentAccess, agents, scheduler, sync, tabLayouts, messageHistory)
        val id = transport.send(source, actor.id, "b", null, "Investigate", "cancel-after-change", true)
        transport.deliverPending()
        val message = scheduler.listPending(target.conversationId).single().requireAgentInvocation().userMessage
        val newSource = advance(source, "source-v2")
        val newTarget = advance(target, "target-v2")
        transport.cancel(newSource.address, actor.id, id)
        assertFalse(transport.validateMessage(message))
        assertFailsWith<IllegalArgumentException> { transport.reply(newTarget.address, actor.id, id, "Too late") }
        assertEquals(AgentRequest.State.CANCELLED, repo.items.getValue(id).state)
        assertFalse(repo.deliveries.values.any { it.kind == AgentDelivery.Kind.RESULT })
    }

    @Test fun `stop after history changes cancels obligations and delivers the recipient cancellation once`(): Unit = runBlocking {
        setup()
        val scheduler = InMemoryConversationRuntimeCoordinator()
        val transport = AgentCollaborationService(repo, conversations, agentAccess, agents, scheduler, sync, tabLayouts, messageHistory)
        val id = transport.send(source, actor.id, "b", null, "Investigate", "stop-after-change", true)
        advance(source, "source-v2")
        val newTarget = advance(target, "target-v2")
        assertTrue(transport.cancelConversation(target.conversationId))
        assertFalse(transport.cancelConversation(target.conversationId))
        assertFailsWith<IllegalArgumentException> { transport.reply(newTarget.address, actor.id, id, "Too late") }
        transport.deliverPending()
        transport.deliverPending()
        assertTrue(scheduler.listPending(target.conversationId).isEmpty())
        val result = scheduler.listPending(source.conversationId).single().requireAgentInvocation().userMessage
        assertTrue(transport.validateMessage(result))
        assertEquals(AgentRequest.State.CANCELLED, repo.items.getValue(id).state)
    }

    @Test fun `history independent delivery still rejects revoked target access`(): Unit = runBlocking {
        setup()
        val id = service.send(source, actor.id, "b", null, "Investigate", "revoked", true)
        val next = advance(target, "target-v2")
        Mockito.`when`(conversations.findById(target.conversationId)).thenReturn(conversation(next).copy(participants = setOf(Conversation.Participant.User(actor.id))))
        service.deliverPending()
        assertEquals(AgentDelivery.State.BLOCKED, repo.deliveries.getValue("$id:input").state)
        assertEquals(AgentRequest.State.BLOCKED, repo.items.getValue(id).state)
        Mockito.verifyNoInteractions(coordinator)
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
