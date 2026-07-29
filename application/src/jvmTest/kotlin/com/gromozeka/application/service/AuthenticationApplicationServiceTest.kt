package com.gromozeka.application.service

import com.gromozeka.domain.model.LocalPasswordCredential
import com.gromozeka.domain.model.PersonalAccessToken
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.service.FirstUserBootstrapToken
import com.gromozeka.domain.service.PasswordHasher
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AuthenticationApplicationServiceTest {
    private val repository = FakeIdentityRepository()
    private val bootstrapToken = FakeBootstrapToken("bootstrap")
    private val service = AuthenticationApplicationService(
        identityRepository = repository,
        passwordHasher = FakePasswordHasher(),
        bootstrapToken = bootstrapToken,
    )

    @Test
    fun `bootstrap creates first owner and authenticated session`() = runSuspend {
        val issued = service.createFirstUser(
            bootstrapToken = "bootstrap",
            username = "Owner",
            displayName = "Project Owner",
            password = "correct horse battery staple".toCharArray(),
            clientLabel = "test",
        )

        assertEquals("owner", issued.user.username)
        assertEquals("Project Owner", issued.user.displayName)
        assertTrue(bootstrapToken.disabled)
        assertEquals(1, repository.users.size)
        assertNotNull(service.authenticate(issued.token))
    }

    @Test
    fun `bootstrap rejects a second user`() = runSuspend {
        createOwner()

        assertFailsWith<IllegalStateException> {
            service.createFirstUser(
                bootstrapToken = "bootstrap",
                username = "second",
                displayName = "Second",
                password = "another sufficiently long password".toCharArray(),
                clientLabel = null,
            )
        }
    }

    @Test
    fun `login does not reveal whether username exists`() = runSuspend {
        createOwner()

        val wrongPassword = assertFailsWith<AuthenticationRejectedException> {
            service.login("owner", "wrong password value".toCharArray(), null)
        }
        val unknownUser = assertFailsWith<AuthenticationRejectedException> {
            service.login("unknown", "wrong password value".toCharArray(), null)
        }

        assertEquals(wrongPassword.message, unknownUser.message)
    }

    @Test
    fun `logout revokes the current session`() = runSuspend {
        val issued = createOwner()

        service.logout(issued.token)

        assertNull(service.authenticate(issued.token))
    }

    @Test
    fun `expired session is rejected`() = runSuspend {
        val issued = createOwner()
        val session = repository.sessions.single()
        repository.sessions[0] = session.copy(expiresAt = Clock.System.now() - 1.seconds)

        assertNull(service.authenticate(issued.token))
    }

    private suspend fun createOwner() =
        service.createFirstUser(
            bootstrapToken = "bootstrap",
            username = "owner",
            displayName = "Owner",
            password = "correct horse battery staple".toCharArray(),
            clientLabel = null,
        )
}

private class FakePasswordHasher : PasswordHasher {
    override fun hash(password: CharArray): String = "hash:${password.concatToString()}"

    override fun verify(password: CharArray, passwordHash: String): Boolean =
        passwordHash == hash(password)

    override fun needsRehash(passwordHash: String): Boolean = false
}

private class FakeBootstrapToken(
    private var token: String?,
) : FirstUserBootstrapToken {
    var disabled = false
        private set

    override fun currentToken(): String? = token

    override fun consume(candidate: String): Boolean {
        if (candidate != token) return false
        token = null
        return true
    }

    override fun disable() {
        disabled = true
        token = null
    }
}

private class FakeIdentityRepository : IdentityRepository {
    val users = mutableListOf<User>()
    val credentials = mutableListOf<LocalPasswordCredential>()
    val sessions = mutableListOf<UserSession>()
    val personalAccessTokens = mutableListOf<PersonalAccessToken>()

    override suspend fun countUsers(): Long = users.size.toLong()

    override suspend fun findUserById(id: User.Id): User? =
        users.singleOrNull { it.id == id }

    override suspend fun findUserByUsername(normalizedUsername: String): User? =
        users.singleOrNull { it.username == normalizedUsername }

    override suspend fun createUser(
        user: User,
        credential: LocalPasswordCredential,
    ): User {
        check(users.none { it.username == user.username })
        users += user
        credentials += credential
        return user
    }

    override suspend fun findPasswordCredential(userId: User.Id): LocalPasswordCredential? =
        credentials.singleOrNull { it.userId == userId }

    override suspend fun updatePasswordCredential(credential: LocalPasswordCredential) {
        credentials[credentials.indexOfFirst { it.userId == credential.userId }] = credential
    }

    override suspend fun createSession(session: UserSession) {
        sessions += session
    }

    override suspend fun findSessionByTokenHash(tokenHash: String): UserSession? =
        sessions.singleOrNull { it.tokenHash == tokenHash }

    override suspend fun touchSession(id: UserSession.Id, lastSeenAt: kotlinx.datetime.Instant) {
        val index = sessions.indexOfFirst { it.id == id }
        sessions[index] = sessions[index].copy(lastSeenAt = lastSeenAt)
    }

    override suspend fun revokeSession(id: UserSession.Id, revokedAt: kotlinx.datetime.Instant) {
        val index = sessions.indexOfFirst { it.id == id }
        sessions[index] = sessions[index].copy(revokedAt = revokedAt)
    }

    override suspend fun revokeAllSessions(userId: User.Id, revokedAt: kotlinx.datetime.Instant) {
        sessions.indices
            .filter { sessions[it].userId == userId }
            .forEach { index -> sessions[index] = sessions[index].copy(revokedAt = revokedAt) }
    }

    override suspend fun deleteExpiredSessions(expiredBefore: kotlinx.datetime.Instant): Int {
        val before = sessions.size
        sessions.removeAll { it.expiresAt < expiredBefore }
        return before - sessions.size
    }

    override suspend fun createPersonalAccessToken(token: PersonalAccessToken) {
        personalAccessTokens += token
    }

    override suspend fun listPersonalAccessTokens(userId: User.Id): List<PersonalAccessToken> =
        personalAccessTokens.filter { it.userId == userId }

    override suspend fun countActivePersonalAccessTokens(
        userId: User.Id,
        now: kotlinx.datetime.Instant,
    ): Long =
        personalAccessTokens.count {
            it.userId == userId &&
                !it.isRevoked &&
                (it.expiresAt?.let { expiresAt -> expiresAt > now } ?: true)
        }.toLong()

    override suspend fun findPersonalAccessTokenByHash(tokenHash: String): PersonalAccessToken? =
        personalAccessTokens.singleOrNull { it.tokenHash == tokenHash }

    override suspend fun touchPersonalAccessToken(
        id: PersonalAccessToken.Id,
        lastUsedAt: kotlinx.datetime.Instant,
    ) {
        val index = personalAccessTokens.indexOfFirst { it.id == id }
        personalAccessTokens[index] = personalAccessTokens[index].copy(lastUsedAt = lastUsedAt)
    }

    override suspend fun revokePersonalAccessToken(
        userId: User.Id,
        id: PersonalAccessToken.Id,
        revokedAt: kotlinx.datetime.Instant,
    ): Boolean {
        val index = personalAccessTokens.indexOfFirst {
            it.userId == userId && it.id == id && !it.isRevoked
        }
        if (index < 0) return false
        personalAccessTokens[index] = personalAccessTokens[index].copy(revokedAt = revokedAt)
        return true
    }
}

private fun runSuspend(block: suspend () -> Unit) =
    kotlinx.coroutines.runBlocking { block() }
