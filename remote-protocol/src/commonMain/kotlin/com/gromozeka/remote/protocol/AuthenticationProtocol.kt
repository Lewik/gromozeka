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
    val code: AuthenticationErrorCode,
    val message: String? = null,
)

@Serializable
enum class AuthenticationErrorCode {
    INVALID_CREDENTIALS,
    RATE_LIMITED,
    BOOTSTRAP_REJECTED,
    INVALID_REQUEST,
    AUTHENTICATION_REQUIRED,
    HTTPS_REQUIRED,
    CROSS_ORIGIN_REJECTED,
    REQUEST_TOO_LARGE,
    REQUEST_READ_FAILED,
    RUNTIME_NOT_INITIALIZED,
    DEVICE_CONNECTION_RATE_LIMITED,
    INVALID_DEVICE_CONNECTION,
    DEVICE_CONNECTION_FAILED,
    REQUEST_FAILED,
}

fun User.toAuthenticatedUserView(): AuthenticatedUserView =
    AuthenticatedUserView(
        id = id,
        username = username,
        displayName = displayName,
        role = role,
    )
