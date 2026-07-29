package com.gromozeka.application.service

import com.gromozeka.domain.model.LocalPasswordCredential
import com.gromozeka.domain.model.PersonalAccessToken
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.repository.IdentityRepository
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class PersonalAccessTokenApplicationServiceTest {
    private val user = User(
        id = User.Id("owner"),
        username = "owner",
        displayName = "Owner",
        status = User.Status.ACTIVE,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )
    private val repository = TokenIdentityRepository(user)
    private val service = PersonalAccessTokenApplicationService(repository)

    @Test
    fun `issued token is stored only as a hash and authenticates required scope`() = runBlocking {
        val issued = service.issue(
            userId = user.id,
            name = "Claude Code",
            scopes = setOf(PersonalAccessToken.Scope.MCP_MEMORY),
            expiresAt = Clock.System.now() + 30.days,
        )

        assertTrue(issued.rawToken.startsWith("grz_pat_"))
        assertNotEquals(issued.rawToken, issued.token.tokenHash)
        assertEquals(64, issued.token.tokenHash.length)
        assertNotNull(service.authenticate(issued.rawToken, PersonalAccessToken.Scope.MCP_MEMORY))
        assertNull(service.authenticate(issued.rawToken, PersonalAccessToken.Scope.MCP_CONTROL))
    }

    @Test
    fun `revoked token stops authenticating`() = runBlocking {
        val issued = service.issue(
            userId = user.id,
            name = "Codex",
            scopes = PersonalAccessToken.Scope.entries.toSet(),
            expiresAt = null,
        )

        assertTrue(service.revoke(user.id, issued.token.id))

        assertNull(service.authenticate(issued.rawToken, PersonalAccessToken.Scope.MCP_MEMORY))
    }

    @Test
    fun `expired token is rejected`() = runBlocking {
        val issued = service.issue(
            userId = user.id,
            name = "Short lived",
            scopes = setOf(PersonalAccessToken.Scope.MCP_MEMORY),
            expiresAt = Clock.System.now() + 1.days,
        )
        repository.tokens[0] = issued.token.copy(expiresAt = Clock.System.now())

        assertNull(service.authenticate(issued.rawToken, PersonalAccessToken.Scope.MCP_MEMORY))
    }
}

private class TokenIdentityRepository(
    private val user: User,
) : IdentityRepository {
    val tokens = mutableListOf<PersonalAccessToken>()

    override suspend fun countUsers(): Long = 1
    override suspend fun findUserById(id: User.Id): User? = user.takeIf { it.id == id }
    override suspend fun findUserByUsername(normalizedUsername: String): User? =
        user.takeIf { it.username == normalizedUsername }
    override suspend fun createUser(user: User, credential: LocalPasswordCredential): User = unsupported()
    override suspend fun findPasswordCredential(userId: User.Id): LocalPasswordCredential? = null
    override suspend fun updatePasswordCredential(credential: LocalPasswordCredential) = unsupported<Unit>()
    override suspend fun createSession(session: UserSession) = unsupported<Unit>()
    override suspend fun findSessionByTokenHash(tokenHash: String): UserSession? = null
    override suspend fun touchSession(id: UserSession.Id, lastSeenAt: Instant) = unsupported<Unit>()
    override suspend fun revokeSession(id: UserSession.Id, revokedAt: Instant) = unsupported<Unit>()
    override suspend fun revokeAllSessions(userId: User.Id, revokedAt: Instant) = unsupported<Unit>()
    override suspend fun deleteExpiredSessions(expiredBefore: Instant): Int = 0

    override suspend fun createPersonalAccessToken(token: PersonalAccessToken) {
        tokens += token
    }

    override suspend fun listPersonalAccessTokens(userId: User.Id): List<PersonalAccessToken> =
        tokens.filter { it.userId == userId }

    override suspend fun countActivePersonalAccessTokens(userId: User.Id, now: Instant): Long =
        tokens.count {
            it.userId == userId &&
                !it.isRevoked &&
                (it.expiresAt?.let { expiresAt -> expiresAt > now } ?: true)
        }.toLong()

    override suspend fun findPersonalAccessTokenByHash(tokenHash: String): PersonalAccessToken? =
        tokens.singleOrNull { it.tokenHash == tokenHash }

    override suspend fun touchPersonalAccessToken(id: PersonalAccessToken.Id, lastUsedAt: Instant) {
        val index = tokens.indexOfFirst { it.id == id }
        tokens[index] = tokens[index].copy(lastUsedAt = lastUsedAt)
    }

    override suspend fun revokePersonalAccessToken(
        userId: User.Id,
        id: PersonalAccessToken.Id,
        revokedAt: Instant,
    ): Boolean {
        val index = tokens.indexOfFirst { it.userId == userId && it.id == id && !it.isRevoked }
        if (index < 0) return false
        tokens[index] = tokens[index].copy(revokedAt = revokedAt)
        return true
    }
}

private fun <T> unsupported(): T = error("Unsupported test operation")
