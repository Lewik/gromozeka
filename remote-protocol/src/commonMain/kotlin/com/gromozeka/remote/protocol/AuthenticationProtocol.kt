package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.User
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class AuthenticationStatusResponse(
    val initialized: Boolean,
    val authenticatedUser: AuthenticatedUserView?,
)

@Serializable
data class AuthenticatedUserView(
    val id: User.Id,
    val username: String,
    val displayName: String,
    val role: User.Role,
)

@Serializable
data class BootstrapUserRequest(
    val bootstrapToken: String,
    val username: String,
    val displayName: String,
    val password: String,
    val clientLabel: String? = null,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val clientLabel: String? = null,
)

@Serializable
data class AuthenticationSessionResponse(
    val user: AuthenticatedUserView,
    val expiresAt: Instant,
)

@Serializable
data class AuthenticationErrorResponse(
    val message: String,
)

fun User.toAuthenticatedUserView(): AuthenticatedUserView =
    AuthenticatedUserView(
        id = id,
        username = username,
        displayName = displayName,
        role = role,
    )
