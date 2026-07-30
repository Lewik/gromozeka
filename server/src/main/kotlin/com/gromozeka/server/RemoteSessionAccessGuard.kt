package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.AuthenticationService

internal class RemoteSessionAccessGuard(
    private val authenticationService: AuthenticationService,
    private val remoteAuthorization: GromozekaRemoteAuthorization,
) {
    suspend fun requireUser(session: AuthenticatedRemoteSession): User =
        authenticationService.authenticate(session.token)?.user
            ?: error("Authentication session is no longer active")

    suspend fun requireConversationRead(
        session: AuthenticatedRemoteSession,
        conversationId: Conversation.Id,
    ): User =
        requireUser(session).also { user ->
            remoteAuthorization.requireConversation(
                user,
                conversationId,
                ProjectPermission.READ,
            )
        }
}
