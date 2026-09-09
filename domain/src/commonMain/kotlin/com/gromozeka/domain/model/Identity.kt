package com.gromozeka.domain.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlin.jvm.JvmInline

@Serializable
data class User(
    val id: Id,
    val identities: List<UserIdentity> = emptyList(),
    val displayName: String,
    val status: Status,
    val role: Role = Role.MEMBER,
    val createdAt: Instant,
    val updatedAt: Instant,
    val loginAllowed: Boolean = true,
    val aiAllowed: Boolean = true,
) {
    val username: String?
        get() = identities.filterIsInstance<UserIdentity.LocalLogin>().singleOrNull()?.username

    val canLogin: Boolean get() = status == Status.ACTIVE && loginAllowed
    val canUseAi: Boolean get() = status == Status.ACTIVE && aiAllowed

    init {
        require(identities.map { it.key }.distinct().size == identities.size)
        require(identities.count { it is UserIdentity.LocalLogin } <= 1)
    }

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

    @Serializable
    enum class Role {
        OWNER,
        MEMBER,
    }
}

@Serializable
sealed interface UserIdentity {
    val key: String

    @Serializable
    @SerialName("local_login")
    data class LocalLogin(val username: String) : UserIdentity {
        override val key: String get() = "local:$username"
        init {
            require(username.isNotBlank() && username == username.trim().lowercase())
        }
    }

    @Serializable
    @SerialName("telegram")
    data class Telegram(
        val telegramUserId: Long,
        val displayName: String,
        val username: String? = null,
    ) : UserIdentity {
        override val key: String get() = "telegram:$telegramUserId"
        init {
            require(telegramUserId > 0)
            require(displayName.isNotBlank())
        }
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
