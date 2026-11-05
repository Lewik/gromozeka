package com.gromozeka.domain.repository

import com.gromozeka.domain.model.*
import kotlin.time.Instant

interface UserNotificationRepository {
    data class PublishResult(val notification: UserNotification, val created: Boolean)
    suspend fun publish(userId: User.Id, draft: UserNotificationDraft, at: Instant): PublishResult
    suspend fun summary(userId: User.Id): UserNotificationSummary
    suspend fun list(userId: User.Id, before: UserNotificationCursor?, limit: Int, unreadOnly: Boolean): UserNotificationPage
    suspend fun setRead(userId: User.Id, id: UserNotification.Id, read: Boolean, at: Instant): UserNotification
    suspend fun markAllRead(userId: User.Id, at: Instant): Int
    suspend fun delete(userId: User.Id, id: UserNotification.Id): Boolean
}
