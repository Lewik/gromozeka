package com.gromozeka.domain.repository

import com.gromozeka.domain.model.LocalPasswordCredential
import com.gromozeka.domain.model.PersonalAccessToken
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import kotlinx.datetime.Instant

interface IdentityRepository {
    suspend fun countUsers(): Long
    suspend fun findUserById(id: User.Id): User?
    suspend fun findUserByUsername(normalizedUsername: String): User?
    suspend fun createUser(user: User, credential: LocalPasswordCredential): User
    suspend fun findPasswordCredential(userId: User.Id): LocalPasswordCredential?
    suspend fun updatePasswordCredential(credential: LocalPasswordCredential)
    suspend fun createSession(session: UserSession)
    suspend fun findSessionByTokenHash(tokenHash: String): UserSession?
    suspend fun touchSession(id: UserSession.Id, lastSeenAt: Instant)
    suspend fun revokeSession(id: UserSession.Id, revokedAt: Instant)
    suspend fun revokeAllSessions(userId: User.Id, revokedAt: Instant)
    suspend fun deleteExpiredSessions(expiredBefore: Instant): Int
    suspend fun createPersonalAccessToken(token: PersonalAccessToken)
    suspend fun listPersonalAccessTokens(userId: User.Id): List<PersonalAccessToken>
    suspend fun countActivePersonalAccessTokens(userId: User.Id, now: Instant): Long
    suspend fun findPersonalAccessTokenByHash(tokenHash: String): PersonalAccessToken?
    suspend fun touchPersonalAccessToken(id: PersonalAccessToken.Id, lastUsedAt: Instant)
    suspend fun revokePersonalAccessToken(
        userId: User.Id,
        id: PersonalAccessToken.Id,
        revokedAt: Instant,
    ): Boolean
}
