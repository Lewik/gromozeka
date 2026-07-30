package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.LocalPasswordCredential
import com.gromozeka.domain.model.PersonalAccessToken
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.infrastructure.db.persistence.tables.LocalPasswordCredentials
import com.gromozeka.infrastructure.db.persistence.tables.PersonalAccessTokenScopes
import com.gromozeka.infrastructure.db.persistence.tables.PersonalAccessTokens
import com.gromozeka.infrastructure.db.persistence.tables.UserSessions
import com.gromozeka.infrastructure.db.persistence.tables.Users
import kotlinx.datetime.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedIdentityRepository : IdentityRepository {
    override suspend fun countUsers(): Long = dbQuery {
        Users.selectAll().count()
    }

    override suspend fun countActiveOwners(): Long = dbQuery {
        Users.selectAll()
            .where {
                (Users.status eq User.Status.ACTIVE.name) and
                    (Users.role eq User.Role.OWNER.name)
            }
            .count()
    }

    override suspend fun listUsers(): List<User> = dbQuery {
        Users.selectAll()
            .orderBy(Users.username)
            .map { it.toUser() }
    }

    override suspend fun findUserById(id: User.Id): User? = dbQuery {
        Users.selectAll()
            .where { Users.id eq id.value }
            .singleOrNull()
            ?.toUser()
    }

    override suspend fun findUserByUsername(normalizedUsername: String): User? = dbQuery {
        Users.selectAll()
            .where { Users.username eq normalizedUsername }
            .singleOrNull()
            ?.toUser()
    }

    override suspend fun createUser(
        user: User,
        credential: LocalPasswordCredential,
    ): User = dbQuery {
        require(credential.userId == user.id) { "Password credential must belong to the created user" }
        Users.insert {
            it[id] = user.id.value
            it[username] = user.username
            it[displayName] = user.displayName
            it[status] = user.status.name
            it[role] = user.role.name
            it[createdAt] = user.createdAt.toKotlin()
            it[updatedAt] = user.updatedAt.toKotlin()
        }
        LocalPasswordCredentials.insert {
            it[userId] = credential.userId.value
            it[passwordHash] = credential.passwordHash
            it[passwordChangedAt] = credential.passwordChangedAt.toKotlin()
        }
        user
    }

    override suspend fun updateUser(user: User): User = dbQuery {
        val updated = Users.update(
            where = { Users.id eq user.id.value },
        ) {
            it[displayName] = user.displayName
            it[status] = user.status.name
            it[role] = user.role.name
            it[updatedAt] = user.updatedAt.toKotlin()
        }
        check(updated == 1) { "User does not exist: ${user.id.value}" }
        user
    }

    override suspend fun findPasswordCredential(userId: User.Id): LocalPasswordCredential? = dbQuery {
        LocalPasswordCredentials.selectAll()
            .where { LocalPasswordCredentials.userId eq userId.value }
            .singleOrNull()
            ?.toLocalPasswordCredential()
    }

    override suspend fun updatePasswordCredential(credential: LocalPasswordCredential): Unit = dbQuery {
        val updated = LocalPasswordCredentials.update(
            where = { LocalPasswordCredentials.userId eq credential.userId.value },
        ) {
            it[passwordHash] = credential.passwordHash
            it[passwordChangedAt] = credential.passwordChangedAt.toKotlin()
        }
        check(updated == 1) { "Password credential does not exist for user ${credential.userId.value}" }
    }

    override suspend fun createSession(session: UserSession): Unit = dbQuery {
        UserSessions.insert {
            it[id] = session.id.value
            it[userId] = session.userId.value
            it[tokenHash] = session.tokenHash
            it[createdAt] = session.createdAt.toKotlin()
            it[lastSeenAt] = session.lastSeenAt.toKotlin()
            it[expiresAt] = session.expiresAt.toKotlin()
            it[revokedAt] = session.revokedAt?.toKotlin()
            it[clientLabel] = session.clientLabel
        }
    }

    override suspend fun findSessionByTokenHash(tokenHash: String): UserSession? = dbQuery {
        UserSessions.selectAll()
            .where { UserSessions.tokenHash eq tokenHash }
            .singleOrNull()
            ?.toUserSession()
    }

    override suspend fun touchSession(id: UserSession.Id, lastSeenAt: Instant): Unit = dbQuery {
        UserSessions.update(
            where = {
                (UserSessions.id eq id.value) and
                    UserSessions.revokedAt.isNull()
            },
        ) {
            it[UserSessions.lastSeenAt] = lastSeenAt.toKotlin()
        }
    }

    override suspend fun revokeSession(id: UserSession.Id, revokedAt: Instant): Unit = dbQuery {
        UserSessions.update(
            where = {
                (UserSessions.id eq id.value) and
                    UserSessions.revokedAt.isNull()
            },
        ) {
            it[UserSessions.revokedAt] = revokedAt.toKotlin()
        }
    }

    override suspend fun revokeAllSessions(userId: User.Id, revokedAt: Instant): Unit = dbQuery {
        UserSessions.update(
            where = {
                (UserSessions.userId eq userId.value) and
                    UserSessions.revokedAt.isNull()
            },
        ) {
            it[UserSessions.revokedAt] = revokedAt.toKotlin()
        }
    }

    override suspend fun deleteExpiredSessions(expiredBefore: Instant): Int = dbQuery {
        UserSessions.deleteWhere {
            UserSessions.expiresAt less expiredBefore.toKotlin()
        }
    }

    override suspend fun createPersonalAccessToken(token: PersonalAccessToken): Unit = dbQuery {
        PersonalAccessTokens.insert {
            it[id] = token.id.value
            it[userId] = token.userId.value
            it[name] = token.name
            it[tokenHash] = token.tokenHash
            it[tokenPrefix] = token.tokenPrefix
            it[createdAt] = token.createdAt.toKotlin()
            it[expiresAt] = token.expiresAt?.toKotlin()
            it[lastUsedAt] = token.lastUsedAt?.toKotlin()
            it[revokedAt] = token.revokedAt?.toKotlin()
        }
        PersonalAccessTokenScopes.batchInsert(token.scopes) { scope ->
            this[PersonalAccessTokenScopes.tokenId] = token.id.value
            this[PersonalAccessTokenScopes.scope] = scope.name
        }
    }

    override suspend fun listPersonalAccessTokens(userId: User.Id): List<PersonalAccessToken> = dbQuery {
        val rows = PersonalAccessTokens.selectAll()
            .where { PersonalAccessTokens.userId eq userId.value }
            .orderBy(PersonalAccessTokens.createdAt)
            .toList()
        val scopesByToken = loadPersonalAccessTokenScopes(rows.map { it[PersonalAccessTokens.id] })
        rows.map { it.toPersonalAccessToken(scopesByToken.getValue(it[PersonalAccessTokens.id])) }
    }

    override suspend fun countActivePersonalAccessTokens(userId: User.Id, now: Instant): Long = dbQuery {
        PersonalAccessTokens.selectAll()
            .where {
                (PersonalAccessTokens.userId eq userId.value) and
                    PersonalAccessTokens.revokedAt.isNull() and
                    (
                        PersonalAccessTokens.expiresAt.isNull() or
                            (PersonalAccessTokens.expiresAt greater now.toKotlin())
                        )
            }
            .count()
    }

    override suspend fun findPersonalAccessTokenByHash(tokenHash: String): PersonalAccessToken? = dbQuery {
        val row = PersonalAccessTokens.selectAll()
            .where { PersonalAccessTokens.tokenHash eq tokenHash }
            .singleOrNull()
            ?: return@dbQuery null
        val tokenId = row[PersonalAccessTokens.id]
        val scopes = loadPersonalAccessTokenScopes(listOf(tokenId)).getValue(tokenId)
        row.toPersonalAccessToken(scopes)
    }

    override suspend fun touchPersonalAccessToken(
        id: PersonalAccessToken.Id,
        lastUsedAt: Instant,
    ): Unit = dbQuery {
        PersonalAccessTokens.update(
            where = {
                (PersonalAccessTokens.id eq id.value) and
                    PersonalAccessTokens.revokedAt.isNull()
            },
        ) {
            it[PersonalAccessTokens.lastUsedAt] = lastUsedAt.toKotlin()
        }
    }

    override suspend fun revokePersonalAccessToken(
        userId: User.Id,
        id: PersonalAccessToken.Id,
        revokedAt: Instant,
    ): Boolean = dbQuery {
        PersonalAccessTokens.update(
            where = {
                (PersonalAccessTokens.id eq id.value) and
                    (PersonalAccessTokens.userId eq userId.value) and
                    PersonalAccessTokens.revokedAt.isNull()
            },
        ) {
            it[PersonalAccessTokens.revokedAt] = revokedAt.toKotlin()
        } == 1
    }

    override suspend fun revokeAllPersonalAccessTokens(
        userId: User.Id,
        revokedAt: Instant,
    ): Unit = dbQuery {
        PersonalAccessTokens.update(
            where = {
                (PersonalAccessTokens.userId eq userId.value) and
                    PersonalAccessTokens.revokedAt.isNull()
            },
        ) {
            it[PersonalAccessTokens.revokedAt] = revokedAt.toKotlin()
        }
    }

    private fun ResultRow.toUser(): User =
        User(
            id = User.Id(this[Users.id]),
            username = this[Users.username],
            displayName = this[Users.displayName],
            status = User.Status.valueOf(this[Users.status]),
            role = User.Role.valueOf(this[Users.role]),
            createdAt = this[Users.createdAt].toKotlinx(),
            updatedAt = this[Users.updatedAt].toKotlinx(),
        )

    private fun ResultRow.toLocalPasswordCredential(): LocalPasswordCredential =
        LocalPasswordCredential(
            userId = User.Id(this[LocalPasswordCredentials.userId]),
            passwordHash = this[LocalPasswordCredentials.passwordHash],
            passwordChangedAt = this[LocalPasswordCredentials.passwordChangedAt].toKotlinx(),
        )

    private fun ResultRow.toUserSession(): UserSession =
        UserSession(
            id = UserSession.Id(this[UserSessions.id]),
            userId = User.Id(this[UserSessions.userId]),
            tokenHash = this[UserSessions.tokenHash],
            createdAt = this[UserSessions.createdAt].toKotlinx(),
            lastSeenAt = this[UserSessions.lastSeenAt].toKotlinx(),
            expiresAt = this[UserSessions.expiresAt].toKotlinx(),
            revokedAt = this[UserSessions.revokedAt]?.toKotlinx(),
            clientLabel = this[UserSessions.clientLabel],
        )

    private fun loadPersonalAccessTokenScopes(
        tokenIds: List<String>,
    ): Map<String, Set<PersonalAccessToken.Scope>> {
        if (tokenIds.isEmpty()) return emptyMap()
        val scopes = PersonalAccessTokenScopes.selectAll()
            .where { PersonalAccessTokenScopes.tokenId inList tokenIds }
            .groupBy(
                keySelector = { it[PersonalAccessTokenScopes.tokenId] },
                valueTransform = {
                    PersonalAccessToken.Scope.valueOf(it[PersonalAccessTokenScopes.scope])
                },
            )
            .mapValues { (_, values) -> values.toSet() }
        return tokenIds.associateWith { scopes[it].orEmpty() }
    }

    private fun ResultRow.toPersonalAccessToken(
        scopes: Set<PersonalAccessToken.Scope>,
    ): PersonalAccessToken =
        PersonalAccessToken(
            id = PersonalAccessToken.Id(this[PersonalAccessTokens.id]),
            userId = User.Id(this[PersonalAccessTokens.userId]),
            name = this[PersonalAccessTokens.name],
            tokenHash = this[PersonalAccessTokens.tokenHash],
            tokenPrefix = this[PersonalAccessTokens.tokenPrefix],
            scopes = scopes,
            createdAt = this[PersonalAccessTokens.createdAt].toKotlinx(),
            expiresAt = this[PersonalAccessTokens.expiresAt]?.toKotlinx(),
            lastUsedAt = this[PersonalAccessTokens.lastUsedAt]?.toKotlinx(),
            revokedAt = this[PersonalAccessTokens.revokedAt]?.toKotlinx(),
        )
}
