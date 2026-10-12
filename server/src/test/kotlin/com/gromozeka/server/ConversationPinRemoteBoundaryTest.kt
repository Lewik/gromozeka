package com.gromozeka.server

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class ConversationPinRemoteBoundaryTest {
    @Test fun `human pin reads writes and observation use conversation permissions not agent policy`() = runBlocking<Unit> {
        val f = Fixture()
        f.auth.authorize(f.actor, ListConversationPinsRequest(f.conversation.id))
        f.auth.authorize(f.actor, GetConversationPinRequest(f.conversation.id, ConversationPin.Id("pin")))
        f.auth.authorizeStateQuery(f.actor, DeclarativeStateRevisionQuery(RemoteDeclarativeStateResource.CONVERSATION_PINS, f.conversation.id.value))
        f.auth.authorize(f.actor, CreateConversationPinRequest(f.conversation.id, "text"))
        f.auth.authorize(f.actor, UpdateConversationPinRequest(f.conversation.id, ConversationPin.Id("pin"), 1, "new"))
        f.auth.authorize(f.actor, DeleteConversationPinRequest(f.conversation.id, ConversationPin.Id("pin"), 1))
        Mockito.verify(f.access, Mockito.times(3)).requirePermission(f.actor.id, f.project, ProjectPermission.READ)
        Mockito.verify(f.access, Mockito.times(3)).requirePermission(f.actor.id, f.project, ProjectPermission.WRITE)
        Mockito.verifyNoInteractions(f.agents)
    }

    @Test fun `read permission does not authorize pin mutations`() = runBlocking<Unit> {
        val f = Fixture()
        Mockito.doThrow(ProjectAccessDeniedException()).`when`(f.access).requirePermission(f.actor.id, f.project, ProjectPermission.WRITE)
        f.auth.authorize(f.actor, ListConversationPinsRequest(f.conversation.id))
        for (request in f.mutations()) assertFailsWith<ProjectAccessDeniedException> { f.auth.authorize(f.actor, request) }
    }

    @Test fun `missing or disconnected conversations deny all CRUD and subscriptions`() = runBlocking<Unit> {
        for (source in listOf<Conversation?>(null, Fixture().conversation.copy(participants = setOf(Conversation.Participant.User(User.Id("someone-else")))))) {
            val f = Fixture()
            Mockito.`when`(f.conversations.findById(f.conversation.id)).thenReturn(source)
            for (request in listOf<ClientRequest>(ListConversationPinsRequest(f.conversation.id),
                GetConversationPinRequest(f.conversation.id, ConversationPin.Id("foreign-pin"))) + f.mutations()) {
                assertFailsWith<ProjectAccessDeniedException> { f.auth.authorize(f.actor, request) }
            }
            assertFailsWith<ProjectAccessDeniedException> {
                f.auth.authorizeStateQuery(f.actor, DeclarativeStateRevisionQuery(RemoteDeclarativeStateResource.CONVERSATION_PINS, f.conversation.id.value))
            }
        }
    }

    @Test fun `pin state scope is mandatory and denotes a conversation not a project or user`() = runBlocking<Unit> {
        val f = Fixture()
        for (scope in listOf(null, "", f.project.value, f.actor.id.value, "unknown-conversation")) {
            assertFailsWith<ProjectAccessDeniedException> {
                f.auth.authorizeStateQuery(f.actor, DeclarativeStateRevisionQuery(RemoteDeclarativeStateResource.CONVERSATION_PINS, scope))
            }
        }
    }

    @Test fun `pin preserving restart requires source read same project and target write`() = runBlocking<Unit> {
        val f = Fixture()
        val request = CreateConversationWithCopiedPinsRequest(f.project, emptySet(), f.conversation.id)
        f.auth.authorize(f.actor, request)
        Mockito.verify(f.access).requirePermission(f.actor.id, f.project, ProjectPermission.READ)
        Mockito.verify(f.access).requirePermission(f.actor.id, f.project, ProjectPermission.WRITE)
        assertFailsWith<ProjectAccessDeniedException> { f.auth.authorize(f.actor, request.copy(projectId = Project.Id("other-project"))) }
        assertFailsWith<ProjectAccessDeniedException> { f.auth.authorize(f.actor, request.copy(sourceConversationId = Conversation.Id("missing"))) }
        Mockito.doThrow(ProjectAccessDeniedException()).`when`(f.access).requirePermission(f.actor.id, f.project, ProjectPermission.WRITE)
        assertFailsWith<ProjectAccessDeniedException> { f.auth.authorize(f.actor, request) }
    }

    @Test fun `new operation failures carry only bounded codes not private messages causes or suppressed errors`() {
        val raw = IllegalStateException("PRIVATE_PIN_TEXT", IllegalArgumentException("PRIVATE_CAUSE"))
            .also { it.addSuppressed(IllegalStateException("PRIVATE_SUPPRESSED")) }
        val cases = listOf(
            ConversationPinNotFoundException() to ConversationPinErrorCode.PIN_NOT_FOUND,
            ConversationPinConflictException() to ConversationPinErrorCode.PIN_CONFLICT,
            ConversationPinLimitException() to ConversationPinErrorCode.PIN_LIMIT_EXCEEDED,
            ProjectAccessDeniedException() to ConversationPinErrorCode.PIN_TARGET_UNAVAILABLE,
            raw to ConversationPinErrorCode.PIN_OPERATION_FAILED,
        )
        for ((error, code) in cases) {
            val response = conversationPinErrorResponse(error)
            assertEquals(code.name, response.type); assertEquals(code.name, response.message)
            assertFalse(response.toString().contains("PRIVATE_")); assertNull(response.speechFailure)
        }
        val cancellation = CancellationException("cancelled")
        assertSame(cancellation, assertFailsWith<CancellationException> { conversationPinErrorResponse(cancellation) })
    }

    @Test fun `safe failure boundary covers only new pin operations and pin preserving creation`() {
        val cid = Conversation.Id("conversation"); val id = ConversationPin.Id("pin")
        for (request in listOf<ClientRequest>(ListConversationPinsRequest(cid), GetConversationPinRequest(cid, id),
            CreateConversationPinRequest(cid, "text"), UpdateConversationPinRequest(cid, id, 1, "text"),
            DeleteConversationPinRequest(cid, id, 1), CreateConversationWithCopiedPinsRequest(Project.Id("project"), emptySet(), cid))) {
            assertTrue(request.isConversationPinOperation())
        }
        assertFalse(FindConversationRequest(cid).isConversationPinOperation())
        assertFalse(CreateConversationRequest(Project.Id("project"), emptySet()).isConversationPinOperation())
    }

    private class Fixture {
        val project = Project.Id("pin-project")
        val actor = User(id = User.Id("pin-user"), identities = listOf(UserIdentity.LocalLogin("pin-user")),
            displayName = "User", status = User.Status.ACTIVE, role = User.Role.MEMBER,
            createdAt = Instant.fromEpochMilliseconds(0), updatedAt = Instant.fromEpochMilliseconds(0))
        val conversation = Conversation(Conversation.Id("pin-conversation"), project,
            setOf(Conversation.Participant.User(actor.id)), currentThread = Conversation.Thread.Id("thread"),
            createdAt = Instant.fromEpochMilliseconds(0), updatedAt = Instant.fromEpochMilliseconds(0))
        val access = mock<ProjectAccessService>()
        val conversations = mock<ConversationDomainService>()
        val agents = mock<AgentDomainService>()
        val auth = GromozekaRemoteAuthorization(access, conversations, agents, mock<PromptDomainService>(), mock<AgentSkillDomainService>(), mock<WorkspaceDomainService>())
        init { runBlocking { Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation) } }
        fun mutations(): List<ClientRequest> = listOf(CreateConversationPinRequest(conversation.id, "text"),
            UpdateConversationPinRequest(conversation.id, ConversationPin.Id("pin"), 1, "text"),
            DeleteConversationPinRequest(conversation.id, ConversationPin.Id("pin"), 1))
    }
    private companion object { inline fun <reified T> mock(): T = Mockito.mock(T::class.java) }
}
