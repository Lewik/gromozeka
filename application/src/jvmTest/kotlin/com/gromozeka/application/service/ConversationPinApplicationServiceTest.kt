package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class ConversationPinApplicationServiceTest {
    private val now = Instant.fromEpochMilliseconds(0)
    private val actor = User.Id("actor")
    private val owner = Conversation.Id("conversation")
    private val project = Project.Id("project")
    private val conversation = Conversation(owner, project, setOf(Conversation.Participant.User(actor)),
        currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now)
    private val user = User(actor, displayName = "User", status = User.Status.ACTIVE, createdAt = now, updatedAt = now, aiAllowed = false)

    @Test fun `UI reads and writes require membership but not AI permission and invalidate only committed changes`() = runBlocking<Unit> {
        val f = Fixture(); f.authorize()
        val pin = testPin(owner, "  exact\n")
        Mockito.`when`(f.repository.create(owner, actor, pin.text, null, "key")).thenReturn(ConversationPinCreation(pin, true), ConversationPinCreation(pin, false))
        assertEquals(pin, f.service.create(actor, owner, pin.text, creationKey = "key"))
        assertEquals(pin, f.service.create(actor, owner, pin.text, creationKey = "key"))
        assertEquals(1, f.events.size)
        Mockito.`when`(f.repository.update(owner, pin.id, 1, "edited")).thenReturn(pin.copy(text = "edited", revision = 2))
        f.service.update(actor, owner, pin.id, 1, "edited")
        Mockito.`when`(f.repository.update(owner, pin.id, 2, "edited")).thenReturn(pin.copy(text = "edited", revision = 2))
        f.service.update(actor, owner, pin.id, 2, "edited")
        Mockito.`when`(f.repository.update(owner, pin.id, 1, "stale")).thenThrow(ConversationPinConflictException())
        assertFailsWith<ConversationPinConflictException> { f.service.update(actor, owner, pin.id, 1, "stale") }
        assertEquals(2, f.events.size)
        f.service.delete(actor, owner, pin.id, 2)
        assertEquals(List(3) { DeclarativeStateKey.conversationPins(owner) }, f.events)
        Mockito.`when`(f.repository.list(owner)).thenReturn(listOf(pin))
        assertEquals(listOf(pin), f.service.list(actor, owner))
        Mockito.verify(f.access).requirePermission(actor, project, ProjectPermission.READ)
        assertFalse(user.canUseAi)
    }

    @Test fun `revoked inactive nonparticipant and readonly actors cannot mutate or publish`() = runBlocking<Unit> {
        for (kind in listOf("inactive", "missing", "nonparticipant", "readonly")) {
            val f = Fixture(); f.authorize()
            when (kind) {
                "inactive" -> Mockito.`when`(f.identities.findUserById(actor)).thenReturn(user.copy(status = User.Status.DISABLED))
                "missing" -> Mockito.`when`(f.conversations.findById(owner)).thenReturn(null)
                "nonparticipant" -> Mockito.`when`(f.conversations.findById(owner)).thenReturn(conversation.copy(participants = setOf(Conversation.Participant.User(User.Id("other")))))
                else -> Mockito.`when`(f.access.requirePermission(actor, project, ProjectPermission.WRITE)).thenThrow(ProjectAccessDeniedException())
            }
            assertFailsWith<ProjectAccessDeniedException> { f.service.create(actor, owner, "text") }
            assertTrue(f.events.isEmpty()); Mockito.verifyNoInteractions(f.repository)
        }
    }

    @Test fun `storage failure is sanitized cancellation propagates and limits are not writes`() = runBlocking<Unit> {
        val f = Fixture(); f.authorize()
        Mockito.`when`(f.repository.list(owner)).thenThrow(IllegalStateException("PRIVATE_TEXT PRIVATE_REASONING"))
        val error = assertFailsWith<ConversationPinOperationFailedException> { f.service.list(actor, owner) }
        assertNull(error.cause); assertFalse(error.toString().contains("PRIVATE_"))
        Mockito.reset(f.repository)
        val cancelled = CancellationException("cancel")
        Mockito.`when`(f.repository.list(owner)).thenThrow(cancelled)
        assertSame(cancelled, assertFailsWith<CancellationException> { f.service.list(actor, owner) })
        Mockito.clearInvocations(f.repository)
        assertFailsWith<ConversationPinLimitException> { f.service.create(actor, owner, "x".repeat(32_001)) }
        assertFailsWith<IllegalArgumentException> { f.service.create(actor, owner, " ") }
        Mockito.verifyNoInteractions(f.repository); assertTrue(f.events.isEmpty())
    }

    private inner class Fixture {
        val repository = Mockito.mock(ConversationPinRepository::class.java)
        val conversations = Mockito.mock(ConversationRepository::class.java)
        val identities = Mockito.mock(IdentityRepository::class.java)
        val access = Mockito.mock(ProjectAccessService::class.java)
        val events = mutableListOf<DeclarativeStateKey>()
        val service = ConversationPinApplicationService(repository, conversations, identities, access,
            DeclarativeStateChangePublisher { keys -> events += keys })
        suspend fun authorize() {
            Mockito.`when`(identities.findUserById(actor)).thenReturn(user)
            Mockito.`when`(conversations.findById(owner)).thenReturn(conversation)
            val membership = ProjectMembership(project, actor, ProjectMembership.Role.OWNER, now, actor)
            Mockito.`when`(access.requirePermission(actor, project, ProjectPermission.READ)).thenReturn(membership)
            Mockito.`when`(access.requirePermission(actor, project, ProjectPermission.WRITE)).thenReturn(membership)
        }
    }
}
