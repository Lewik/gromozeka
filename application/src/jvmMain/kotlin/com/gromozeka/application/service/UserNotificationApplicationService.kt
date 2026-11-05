package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.repository.UserNotificationRepository
import com.gromozeka.domain.service.*
import org.springframework.stereotype.Service
import kotlin.time.Clock

@Service
class UserNotificationApplicationService(
    private val repository: UserNotificationRepository,
    private val identities: IdentityRepository,
    private val changes: DeclarativeStateChangePublisher,
) : UserNotificationPublisher {
    override suspend fun publish(userId: User.Id, notification: UserNotificationDraft): UserNotification {
        requireNotNull(identities.findUserById(userId)) { "Notification recipient does not exist" }
        val result = repository.publish(userId, notification, Clock.System.now())
        if (result.created) changes.publish(DeclarativeStateKey.userNotifications(userId))
        return result.notification
    }

    suspend fun summary(actor: User.Id): UserNotificationSummary {
        authorize(actor)
        return repository.summary(actor)
    }

    suspend fun list(actor: User.Id, before: UserNotificationCursor?, limit: Int, unreadOnly: Boolean): UserNotificationPage {
        authorize(actor)
        require(limit in 1..100) { "Notification page size must be between 1 and 100" }
        return repository.list(actor, before, limit, unreadOnly)
    }

    suspend fun setRead(actor: User.Id, id: UserNotification.Id, read: Boolean): UserNotification {
        authorize(actor)
        val result = repository.setRead(actor, id, read, Clock.System.now())
        changes.publish(DeclarativeStateKey.userNotifications(actor))
        return result
    }

    suspend fun markAllRead(actor: User.Id) {
        authorize(actor)
        if (repository.markAllRead(actor, Clock.System.now()) > 0) changes.publish(DeclarativeStateKey.userNotifications(actor))
    }

    suspend fun delete(actor: User.Id, id: UserNotification.Id) {
        authorize(actor)
        if (repository.delete(actor, id)) changes.publish(DeclarativeStateKey.userNotifications(actor))
    }

    private suspend fun authorize(actor: User.Id) {
        if (identities.findUserById(actor)?.status != User.Status.ACTIVE) throw ProjectAccessDeniedException()
    }
}
