package com.gromozeka.server

import com.gromozeka.domain.model.UserNotificationNotFoundException
import com.gromozeka.domain.service.ProjectAccessDeniedException
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.CancellationException

internal fun ClientRequest.isUserNotificationOperation(): Boolean =
    this is GetUserNotificationSummaryRequest || this is ListUserNotificationsRequest ||
        this is SetUserNotificationReadRequest || this is MarkAllUserNotificationsReadRequest ||
        this is DeleteUserNotificationRequest

internal fun userNotificationErrorResponse(error: Throwable): ErrorResponse {
    if (error is CancellationException) throw error
    val code = when(error) {
        is UserNotificationNotFoundException -> "NOTIFICATION_NOT_FOUND"
        is ProjectAccessDeniedException -> "NOTIFICATION_ACCESS_DENIED"
        is IllegalArgumentException -> "INVALID_NOTIFICATION_REQUEST"
        else -> "NOTIFICATION_OPERATION_FAILED"
    }
    return ErrorResponse(message=code, type=code)
}
