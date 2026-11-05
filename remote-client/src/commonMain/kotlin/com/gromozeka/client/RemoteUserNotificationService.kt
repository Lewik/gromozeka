package com.gromozeka.client

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.UserNotificationService
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.flow.Flow

internal class RemoteUserNotificationService(
    private val client: GromozekaWsClient,
    private val userId: User.Id,
) : UserNotificationService {
    override fun observeSummary(): Flow<UserNotificationSummary> =
        client.observeDeclarativeState(RemoteDeclarativeStateResource.USER_NOTIFICATIONS, userId.value) { summary() }

    override suspend fun summary(): UserNotificationSummary =
        request<UserNotificationSummaryResponse>(GetUserNotificationSummaryRequest).summary

    override suspend fun list(before: UserNotificationCursor?, limit: Int, unreadOnly: Boolean): UserNotificationPage =
        request<UserNotificationsResponse>(ListUserNotificationsRequest(before,limit,unreadOnly)).page

    override suspend fun setRead(id: UserNotification.Id, read: Boolean): UserNotification =
        request<UserNotificationResponse>(SetUserNotificationReadRequest(id,read)).notification

    override suspend fun markAllRead() {
        check(request<OperationResultResponse>(MarkAllUserNotificationsReadRequest).success) { "Notification update failed" }
    }

    override suspend fun delete(id: UserNotification.Id) {
        check(request<OperationResultResponse>(DeleteUserNotificationRequest(id)).success) { "Notification deletion failed" }
    }

    private suspend inline fun <reified T : ServerResponse> request(request: ClientRequest): T = when(val response=client.request(request)) {
        is T -> response
        is ErrorResponse -> throw IllegalStateException(response.message)
        else -> error("Unexpected notification response")
    }
}
