package com.gromozeka.server

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.service.AuthenticationService
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteSessionAccessGuardTest {
    private val authenticationService = Mockito.mock(AuthenticationService::class.java)
    private val remoteAuthorization = Mockito.mock(GromozekaRemoteAuthorization::class.java)
    private val guard = RemoteSessionAccessGuard(authenticationService, remoteAuthorization)

    @Test
    fun `each access check uses the current authenticated user`() = runBlocking {
        val owner = user(User.Role.OWNER)
        val member = owner.copy(role = User.Role.MEMBER)
        val session = remoteSession(owner)
        Mockito.`when`(authenticationService.authenticate(session.token))
            .thenReturn(authenticated(owner), authenticated(member))

        assertEquals(User.Role.OWNER, guard.requireUser(session).role)
        assertEquals(User.Role.MEMBER, guard.requireUser(session).role)
        Mockito.verify(authenticationService, Mockito.times(2)).authenticate(session.token)
    }

    @Test
    fun `conversation reads recheck project authorization`() = runBlocking {
        val user = user(User.Role.MEMBER)
        val session = remoteSession(user)
        val conversation = conversation()
        Mockito.`when`(authenticationService.authenticate(session.token))
            .thenReturn(authenticated(user))
        Mockito.`when`(
            remoteAuthorization.requireConversation(
                user,
                conversation.id,
                ProjectPermission.READ,
            )
        ).thenReturn(conversation)

        assertEquals(user, guard.requireConversationRead(session, conversation.id))
        Mockito.verify(remoteAuthorization).requireConversation(
            user,
            conversation.id,
            ProjectPermission.READ,
        )
    }

    @Test
    fun `revoked authentication session fails closed`() = runBlocking {
        val session = remoteSession(user(User.Role.MEMBER))
        Mockito.`when`(authenticationService.authenticate(session.token)).thenReturn(null)

        assertFailsWith<IllegalStateException> {
            guard.requireUser(session)
        }
    }

    private fun remoteSession(user: User): AuthenticatedRemoteSession =
        AuthenticatedRemoteSession(
            token = "session-token",
            principal = authenticated(user),
        )

    private fun authenticated(user: User): AuthenticatedUser =
        AuthenticatedUser(
            user = user,
            sessionId = UserSession.Id("session-id"),
        )

    private fun user(role: User.Role): User =
        User(
            id = User.Id("user-id"),
            username = "user",
            displayName = "User",
            status = User.Status.ACTIVE,
            role = role,
            createdAt = Instant.fromEpochMilliseconds(1),
            updatedAt = Instant.fromEpochMilliseconds(1),
        )

    private fun conversation(): Conversation =
        Conversation(
            id = Conversation.Id("conversation-id"),
            projectId = Project.Id("project-id"),
            agentDefinitionId = AgentDefinition.Id("agent-id"),
            currentThread = Conversation.Thread.Id("thread-id"),
            createdAt = Instant.fromEpochMilliseconds(1),
            updatedAt = Instant.fromEpochMilliseconds(1),
        )
}
