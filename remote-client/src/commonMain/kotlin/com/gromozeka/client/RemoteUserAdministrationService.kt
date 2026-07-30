package com.gromozeka.client

import com.gromozeka.domain.model.User
import com.gromozeka.remote.protocol.CreateUserRequest
import com.gromozeka.remote.protocol.ListUsersRequest
import com.gromozeka.remote.protocol.ResetUserPasswordRequest
import com.gromozeka.remote.protocol.UpdateUserRequest
import com.gromozeka.remote.protocol.UserPasswordResetResponse
import com.gromozeka.remote.protocol.UserResponse
import com.gromozeka.remote.protocol.UsersResponse

class RemoteUserAdministrationService internal constructor(
    private val client: GromozekaWsClient,
) {
    suspend fun list(): List<User> =
        client.requestTyped<ListUsersRequest, UsersResponse>(ListUsersRequest).users

    suspend fun create(
        username: String,
        displayName: String,
        password: String,
        role: User.Role,
    ): User =
        client.requestTyped<CreateUserRequest, UserResponse>(
            CreateUserRequest(
                username = username,
                displayName = displayName,
                password = password,
                role = role,
            )
        ).user

    suspend fun update(
        userId: User.Id,
        displayName: String,
        status: User.Status,
        role: User.Role,
    ): User =
        client.requestTyped<UpdateUserRequest, UserResponse>(
            UpdateUserRequest(
                userId = userId,
                displayName = displayName,
                status = status,
                role = role,
            )
        ).user

    suspend fun resetPassword(
        userId: User.Id,
        password: String,
    ) {
        client.requestTyped<ResetUserPasswordRequest, UserPasswordResetResponse>(
            ResetUserPasswordRequest(userId, password)
        )
    }
}
