package com.gromozeka.domain.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

@Serializable
data class UserNotification(
    val id: Id,
    val userId: User.Id,
    val title: String,
    val message: String,
    val level: Level,
    val createdAt: Instant,
    val readAt: Instant? = null,
    val conversationId: Conversation.Id? = null,
) {
    @JvmInline
    @Serializable
    value class Id(val value: String) {
        init { require(value.isNotBlank() && value.length <= 128) { "Invalid notification id" } }
    }

    @Serializable
    enum class Level { INFO, WARNING, ERROR }
}

/** A producer supplies a stable source key when retries must not create another notification. */
data class UserNotificationDraft(
    val title: String,
    val message: String,
    val level: UserNotification.Level = UserNotification.Level.INFO,
    val conversationId: Conversation.Id? = null,
    val sourceKey: String? = null,
) {
    init {
        require(title.isNotBlank() && title.length <= 200 && '\n' !in title && '\r' !in title) { "Invalid notification title" }
        require(message.isNotBlank() && message.encodeToByteArray().size <= 16_384) { "Invalid notification message" }
        require(sourceKey == null || (sourceKey.isNotBlank() && sourceKey.length <= 256)) { "Invalid notification source key" }
    }
}

@Serializable
data class UserNotificationCursor(val createdAt: Instant, val id: UserNotification.Id)

@Serializable
data class UserNotificationPage(
    val items: List<UserNotification>,
    val nextCursor: UserNotificationCursor? = null,
)

@Serializable
data class UserNotificationSummary(val unreadCount: Long, val latest: UserNotification? = null)

class UserNotificationNotFoundException : IllegalStateException("Notification is not available")
