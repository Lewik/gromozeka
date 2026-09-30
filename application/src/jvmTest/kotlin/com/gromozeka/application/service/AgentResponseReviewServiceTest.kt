package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.AgentCollaborationRepository
import com.gromozeka.domain.service.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class AgentResponseReviewServiceTest {
    private val now = Instant.fromEpochMilliseconds(1)
    private val a = AgentEndpoint(Conversation.Id("a"), Conversation.Thread.Id("a-thread"), AgentDefinition.Id("a-agent"))
    private val b = AgentEndpoint(Conversation.Id("b"), Conversation.Thread.Id("b-thread"), AgentDefinition.Id("b-agent"))
    private val user = User.Id("user")
    private val incoming = AgentRequest("req-1", a, b, user, "Check this", now)
    private val text = ContentItem.AssistantMessage(Conversation.Message.StructuredText("Actual model answer", ttsText = "Wrong recipient", attentionRequested = true))
    private val message = Conversation.Message(Conversation.Message.Id("draft-answer"), b.conversationId, role = Conversation.Message.Role.ASSISTANT, author = Conversation.Message.Author.Agent(b.agentId, "B"), content = listOf(text), createdAt = now)
    private val draft = AgentResponseDraft("draft", b, user, Conversation.Message.Id("root"), "turn", 1, listOf(message), listOf(incoming), emptyList(), listOf("command-1"), "", listOf("root"), now)
    private val service = AgentResponseReviewService(mock(), mock(), mock(), mock())
    private fun complete() = AgentResponseDecision(null, AgentResponseDecision.Next.COMPLETE, emptyList(), listOf(AgentResponseDecision.RequestDecision(incoming.id, AgentRequest.State.COMPLETED, "Result for A", emptyList())))

    @Test fun `external channel final answers bypass response review before any repository access`() = runBlocking {
        val collaboration = mock<AgentCollaborationService>()
        val repository = mock<AgentCollaborationRepository>()
        val coordinator = mock<ConversationRuntimeCoordinator>()
        val sync = mock<ConversationRuntimeStateSyncService>()
        val reviewer = AgentResponseReviewService(collaboration, repository, coordinator, sync)
        val conversation = Conversation(b.conversationId, Project.Id("project"), setOf(Conversation.Participant.User(user), Conversation.Participant.Agent(b.agentId)),
            currentThread = b.threadId, createdAt = now, updatedAt = now,
            externalChannel = ExternalConversationChannel("telegram", "test", "chat"))
        val payload = ConversationRuntimeTask.Payload.LlmCall(Conversation.Message.Id("root"), b.agentId, 1)
        val task = ConversationRuntimeTask(ConversationRuntimeTask.Id("call"), conversation.id, actorUserId = user,
            turnId = ConversationRuntimeTurnId("turn"), parentTaskId = ConversationRuntimeTask.Id("root"),
            payload = payload, placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = "call",
            requirements = ConversationRuntimeTaskRequirements(setOf(ConversationRuntimeCapability.AI_REQUEST_RESPONSE, ConversationRuntimeCapability.MEMORY_PIPELINE), ConversationRuntimeTaskTarget.Server), createdAt = now)
        assertNull(reviewer.prepare(task, payload, conversation, emptyList(), listOf(message)))
        Mockito.verifyNoInteractions(collaboration, repository, coordinator, sync)
    }

    @Test fun `finished peer result may suppress user output`() {
        AgentResponseReviewService.validate(complete(), draft)
        val accepted = service.acceptedMessages(draft, complete()).single()
        assertTrue(accepted.content.none { it is ContentItem.AssistantMessage })
        assertEquals(message.content, AiRuntimeRequest(emptyList(), listOf(accepted)).originalCollaborationMessages().single().content)
    }
    @Test fun `corrected visible answer preserves actual model output for every provider`() {
        val decision = complete().copy(userText = "Summary for the human")
        val accepted = service.acceptedMessages(draft, decision).single()
        val visible = assertIs<ContentItem.AssistantMessage>(accepted.content.single()).structured
        assertEquals("Summary for the human", visible.fullText)
        assertNull(visible.ttsText)
        assertFalse(visible.attentionRequested)
        assertEquals(message.content, AiRuntimeRequest(emptyList(), listOf(accepted)).projectedMessages().single().content)
    }
    @Test fun `missing duplicated and invented request dispositions are rejected`() {
        assertFailsWith<IllegalArgumentException> { AgentResponseReviewService.validate(complete().copy(requests = emptyList()), draft) }
        assertFailsWith<IllegalArgumentException> { AgentResponseReviewService.validate(complete().copy(requests = complete().requests + complete().requests), draft) }
        assertFailsWith<IllegalArgumentException> { AgentResponseReviewService.validate(complete().copy(requests = complete().requests.map { it.copy(requestId = "invented") }), draft) }
    }
    @Test fun `cannot leave working obligations without automatic continuation`() {
        val d = complete().copy(requests = listOf(AgentResponseDecision.RequestDecision(incoming.id, AgentRequest.State.WORKING, null, emptyList())))
        assertFailsWith<IllegalArgumentException> { AgentResponseReviewService.validate(d, draft) }
        AgentResponseReviewService.validate(d.copy(next = AgentResponseDecision.Next.CONTINUE), draft)
    }
    @Test fun `waiting requires a concrete known event`() {
        assertFailsWith<IllegalArgumentException> { AgentResponseReviewService.validate(complete().copy(next = AgentResponseDecision.Next.WAIT), draft) }
        assertFailsWith<IllegalArgumentException> { AgentResponseReviewService.validate(complete().copy(next = AgentResponseDecision.Next.WAIT, waitFor = listOf("invented")), draft) }
        AgentResponseReviewService.validate(complete().copy(next = AgentResponseDecision.Next.WAIT, waitFor = listOf("command-1")), draft)
    }
    @Test fun `user wait requires visible question but existing waits do not block unrelated replies`() {
        val d = AgentResponseDecision("Which file?", AgentResponseDecision.Next.ASK_USER, emptyList(), listOf(AgentResponseDecision.RequestDecision(incoming.id, AgentRequest.State.WAITING_USER, null, emptyList())))
        AgentResponseReviewService.validate(d, draft)
        assertFailsWith<IllegalArgumentException> { AgentResponseReviewService.validate(d.copy(userText = null), draft) }
        assertFailsWith<IllegalArgumentException> { AgentResponseReviewService.validate(d.copy(next = AgentResponseDecision.Next.COMPLETE), draft) }
        AgentResponseReviewService.validate(d.copy(next = AgentResponseDecision.Next.COMPLETE, userText = "Answer to an unrelated question"), draft.copy(incoming = listOf(incoming.copy(state = AgentRequest.State.WAITING_USER))))
    }
    @Test fun `empty results and attempts to cancel requests are rejected`() {
        assertFailsWith<IllegalArgumentException> { AgentResponseReviewService.validate(complete().copy(requests = complete().requests.map { it.copy(result = " ") }), draft) }
        assertFailsWith<IllegalArgumentException> { AgentResponseReviewService.validate(complete().copy(requests = complete().requests.map { it.copy(state = AgentRequest.State.CANCELLED) }), draft) }
    }
    @Test fun `strict decision parser rejects additional fields`() {
        val valid = Json.encodeToString(complete())
        assertEquals(complete(), AgentResponseReviewService.parseAndValidate(valid, draft))
        assertFailsWith<IllegalArgumentException> { AgentResponseReviewService.parseAndValidate(valid.dropLast(1) + ",\"send_to\":\"attacker\"}", draft) }
    }
    @Test fun `review is tool-free isolated and bounded to one correction`() = runBlocking {
        val seen = mutableListOf<AiRuntimeRequest>()
        val runtime = object : AiRuntime {
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                seen += request
                val body = if (seen.size == 1) "{}" else Json.encodeToString(complete())
                return AiRuntimeResponse(listOf(AiAssistantMessage(listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText(body))))))
            }
            override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = error("No streaming")
        }
        assertEquals(complete(), service.review(draft, runtime, AiRuntimeOptions()))
        assertEquals(2, seen.size)
        assertTrue(seen.all { it.tools.isEmpty() && it.options.toolChoice == AiToolChoice.None && it.options.usagePurpose == "AGENT_RESPONSE_REVIEW" })
        assertTrue(seen.all { it.systemPrompts.size == 1 })
    }
    @Test fun `second invalid review cannot loop or publish a guessed answer`() = runBlocking {
        var calls = 0
        val runtime = object : AiRuntime {
            override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
                calls++
                return AiRuntimeResponse(listOf(AiAssistantMessage(listOf(ContentItem.AssistantMessage(Conversation.Message.StructuredText("{}"))))))
            }
            override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = error("No streaming")
        }
        assertFailsWith<IllegalStateException> { service.review(draft, runtime, AiRuntimeOptions()) }
        assertEquals(2, calls)
    }
    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
