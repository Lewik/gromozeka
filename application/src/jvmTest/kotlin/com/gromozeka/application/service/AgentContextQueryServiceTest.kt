package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class AgentContextQueryServiceTest {
    @Test fun `uses shared eligibility before reading before invoking and before returning without creating work`() = runBlocking<Unit> {
        val f = Fixture()
        val result = f.service.query(f.context, "target", "Question", "receiver")
        assertEquals("Unknown from context", result.answer)
        assertSame(f.snapshot, result.snapshot)
        Mockito.verify(f.collaboration).source(f.context)
        Mockito.verify(f.collaboration, Mockito.times(3)).resolveEligibleTarget(f.source, f.actor, "target", "receiver")
        Mockito.verifyNoMoreInteractions(f.collaboration)
        Mockito.verify(f.snapshots).capture(f.peer.endpoint, f.peer.conversation, f.peer.agent, f.actor)
        Mockito.verifyNoMoreInteractions(f.snapshots)
        Mockito.verify(f.invoker).answer(f.snapshot, "Question")
        Mockito.verifyNoMoreInteractions(f.invoker)
    }

    @Test fun `shared eligibility rejection stops before snapshot or invocation`() = runBlocking<Unit> {
        val f = Fixture()
        Mockito.`when`(f.collaboration.resolveEligibleTarget(f.source, f.actor, "target", null))
            .thenThrow(IllegalArgumentException("Specify target_agent_id"))
        assertFailsWith<IllegalArgumentException> { f.service.query(f.context, "target", "Question") }
        Mockito.verifyNoInteractions(f.snapshots, f.invoker)
    }

    @Test fun `revocation during model call cannot return the captured context`() = runBlocking<Unit> {
        val f = Fixture()
        Mockito.`when`(f.collaboration.resolveEligibleTarget(f.source, f.actor, "target", "receiver"))
            .thenReturn(f.peer).thenReturn(f.peer).thenThrow(IllegalArgumentException("Access revoked"))
        assertFailsWith<IllegalArgumentException> { f.service.query(f.context, "target", "Question", "receiver") }
        Mockito.verify(f.invoker).answer(f.snapshot, "Question")
    }

    @Test fun `changed branch during assembly prevents invocation`() = runBlocking<Unit> {
        val f = Fixture()
        Mockito.`when`(f.collaboration.resolveEligibleTarget(f.source, f.actor, "target", "receiver"))
            .thenReturn(f.peer).thenReturn(f.peer.copy(endpoint = f.peer.endpoint.copy(threadId = Conversation.Thread.Id("new"))))
        assertFailsWith<IllegalStateException> { f.service.query(f.context, "target", "Question", "receiver") }
        Mockito.verifyNoInteractions(f.invoker)
    }

    private class Fixture {
        val context = ToolExecutionContext()
        val actor = User.Id("user")
        val source = AgentEndpoint(Conversation.Id("source"), Conversation.Thread.Id("source-thread"), AgentDefinition.Id("sender"))
        private val now = Instant.fromEpochMilliseconds(0)
        private val agent = AgentDefinition(AgentDefinition.Id("receiver"), name = "Receiver", prompts = emptyList(),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model")), type = AgentDefinition.Type.Global,
            createdAt = now, updatedAt = now)
        private val conversation = Conversation(Conversation.Id("target"), Project.Id("project"),
            setOf(Conversation.Participant.User(actor), Conversation.Participant.Agent(agent.id)),
            currentThread = Conversation.Thread.Id("target-thread"), createdAt = now, updatedAt = now)
        val peer = AgentCollaborationService.EligibleTarget(AgentEndpoint(conversation.id, conversation.currentThread, agent.id), conversation, agent)
        val snapshot = Mockito.mock(ConversationContextSnapshot::class.java)
        val collaboration = Mockito.mock(AgentCollaborationService::class.java)
        val snapshots = Mockito.mock(ConversationContextSnapshotService::class.java)
        val invoker = Mockito.mock(IsolatedContextQueryInvoker::class.java)
        val service = AgentContextQueryService(collaboration, snapshots, invoker)
        init { runBlocking {
            Mockito.`when`(collaboration.source(context)).thenReturn(source to actor)
            Mockito.`when`(collaboration.resolveEligibleTarget(source, actor, "target", "receiver")).thenReturn(peer)
            Mockito.`when`(snapshots.capture(peer.endpoint, peer.conversation, peer.agent, actor)).thenReturn(snapshot)
            Mockito.`when`(invoker.answer(snapshot, "Question")).thenReturn("Unknown from context")
        } }
    }
}
