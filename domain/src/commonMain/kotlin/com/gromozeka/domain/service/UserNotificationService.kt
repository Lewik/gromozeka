package com.gromozeka.domain.service

import com.gromozeka.domain.model.*
import kotlinx.coroutines.flow.Flow

/** The authenticated remote session owns this inbox; clients cannot choose a recipient. */
interface UserNotificationService {
    fun observeSummary(): Flow<UserNotificationSummary>
    suspend fun summary(): UserNotificationSummary
    suspend fun list(before: UserNotificationCursor? = null, limit: Int = 50, unreadOnly: Boolean = false): UserNotificationPage
    suspend fun setRead(id: UserNotification.Id, read: Boolean): UserNotification
    suspend fun markAllRead()
    suspend fun delete(id: UserNotification.Id)
}

/** Server-side publication API shared by independent notification producers. */
fun interface UserNotificationPublisher {
    suspend fun publish(userId: User.Id, notification: UserNotificationDraft): UserNotification
}
