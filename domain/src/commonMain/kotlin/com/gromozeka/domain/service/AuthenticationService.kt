package com.gromozeka.domain.service

import com.gromozeka.domain.model.AuthenticatedUser
import com.gromozeka.domain.model.AuthenticatedPersonalAccessToken
import com.gromozeka.domain.model.IssuedUserSession
import com.gromozeka.domain.model.IssuedPersonalAccessToken
import com.gromozeka.domain.model.PersonalAccessToken
import com.gromozeka.domain.model.User
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

interface PasswordHasher {
    fun hash(password: CharArray): String
    fun verify(password: CharArray, passwordHash: String): Boolean
    fun needsRehash(passwordHash: String): Boolean
}

interface AuthenticationService {
    suspend fun hasUsers(): Boolean
    suspend fun createFirstUser(
        bootstrapToken: String,
        username: String,
        displayName: String,
        password: CharArray,
        clientLabel: String?,
    ): IssuedUserSession

    suspend fun login(
        username: String,
        password: CharArray,
        clientLabel: String?,
    ): IssuedUserSession

    suspend fun authenticate(sessionToken: String): AuthenticatedUser?
    suspend fun logout(sessionToken: String)
    suspend fun revokeAllSessions(userId: User.Id)
}

interface LocalCredentialVerifier {
    suspend fun verifyPassword(
        username: String,
        password: CharArray,
    ): User
}

object AuthenticationSessionPolicy {
    val lifetime = 30.days
    val touchInterval = 5.minutes
    const val maxClientLabelLength = 255
}

interface UserAdministrationService {
    suspend fun list(actor: User): List<User>

    suspend fun create(
        actor: User,
        username: String,
        displayName: String,
        password: CharArray,
        role: User.Role,
    ): User

    suspend fun update(
        actor: User,
        userId: User.Id,
        displayName: String,
        status: User.Status,
        role: User.Role,
    ): User

    suspend fun resetPassword(
        actor: User,
        userId: User.Id,
        password: CharArray,
    )
}

interface UserDirectoryService {
    suspend fun findActiveById(id: User.Id): User?
    suspend fun listActive(): List<User>
}

interface FirstUserBootstrapToken {
    fun currentToken(): String?
    fun consume(candidate: String): Boolean
    fun disable()
}

class UserAdministrationDeniedException :
    IllegalStateException("Runtime owner permission is required")

class LastActiveRuntimeOwnerException :
    IllegalStateException("A runtime must have at least one active owner")

class SoleProjectOwnerException(projectIds: List<String>) :
    IllegalStateException(
        "Disable is blocked because the user is the sole owner of projects: " +
            projectIds.sorted().joinToString(),
    )

interface PersonalAccessTokenService {
    suspend fun issue(
        userId: User.Id,
        name: String,
        scopes: Set<PersonalAccessToken.Scope>,
        expiresAt: Instant?,
    ): IssuedPersonalAccessToken

    suspend fun list(userId: User.Id): List<PersonalAccessToken>

    suspend fun revoke(
        userId: User.Id,
        tokenId: PersonalAccessToken.Id,
    ): Boolean

    suspend fun authenticate(
        rawToken: String,
        requiredScope: PersonalAccessToken.Scope,
    ): AuthenticatedPersonalAccessToken?
}
