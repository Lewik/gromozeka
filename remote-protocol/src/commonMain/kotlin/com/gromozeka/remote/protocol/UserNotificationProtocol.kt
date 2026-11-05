package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("get_user_notification_summary")
data object GetUserNotificationSummaryRequest : ClientRequest

@Serializable
@SerialName("list_user_notifications")
data class ListUserNotificationsRequest(
    val before: UserNotificationCursor? = null,
    val limit: Int = 50,
    val unreadOnly: Boolean = false,
) : ClientRequest

@Serializable
@SerialName("set_user_notification_read")
data class SetUserNotificationReadRequest(val id: UserNotification.Id, val read: Boolean) : ClientRequest

@Serializable
@SerialName("mark_all_user_notifications_read")
data object MarkAllUserNotificationsReadRequest : ClientRequest

@Serializable
@SerialName("delete_user_notification")
data class DeleteUserNotificationRequest(val id: UserNotification.Id) : ClientRequest

@Serializable
@SerialName("user_notification_summary")
data class UserNotificationSummaryResponse(val summary: UserNotificationSummary) : ServerResponse

@Serializable
@SerialName("user_notifications")
data class UserNotificationsResponse(val page: UserNotificationPage) : ServerResponse

@Serializable
@SerialName("user_notification")
data class UserNotificationResponse(val notification: UserNotification) : ServerResponse
