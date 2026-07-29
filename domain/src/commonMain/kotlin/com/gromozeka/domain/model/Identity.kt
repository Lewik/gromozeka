package com.gromozeka.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
data class User(
    val id: Id,
    val username: String,
    val displayName: String,
    val status: Status,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "User id must not be blank" }
        }
    }

    @Serializable
    enum class Status {
        ACTIVE,
        DISABLED,
    }
}

data class LocalPasswordCredential(
    val userId: User.Id,
    val passwordHash: String,
    val passwordChangedAt: Instant,
)

data class UserSession(
    val id: Id,
    val userId: User.Id,
    val tokenHash: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val clientLabel: String?,
) {
    val isRevoked: Boolean
        get() = revokedAt != null

    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "User session id must not be blank" }
        }
    }
}

data class AuthenticatedUser(
    val user: User,
    val sessionId: UserSession.Id,
)

data class IssuedUserSession(
    val user: User,
    val sessionId: UserSession.Id,
    val token: String,
    val expiresAt: Instant,
)

data class PersonalAccessToken(
    val id: Id,
    val userId: User.Id,
    val name: String,
    val tokenHash: String,
    val tokenPrefix: String,
    val scopes: Set<Scope>,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?,
) {
    val isRevoked: Boolean
        get() = revokedAt != null

    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "Personal access token id must not be blank" }
        }
    }

    @Serializable
    enum class Scope {
        MCP_MEMORY,
        MCP_CONTROL,
    }
}

data class IssuedPersonalAccessToken(
    val token: PersonalAccessToken,
    val rawToken: String,
)

data class AuthenticatedPersonalAccessToken(
    val user: User,
    val token: PersonalAccessToken,
)
