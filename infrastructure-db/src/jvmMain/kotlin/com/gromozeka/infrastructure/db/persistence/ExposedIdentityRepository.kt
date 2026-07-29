package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.LocalPasswordCredential
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.infrastructure.db.persistence.tables.LocalPasswordCredentials
import com.gromozeka.infrastructure.db.persistence.tables.UserSessions
import com.gromozeka.infrastructure.db.persistence.tables.Users
import kotlinx.datetime.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedIdentityRepository : IdentityRepository {
    override suspend fun countUsers(): Long = dbQuery {
        Users.selectAll().count()
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

    private fun ResultRow.toUser(): User =
        User(
            id = User.Id(this[Users.id]),
            username = this[Users.username],
            displayName = this[Users.displayName],
            status = User.Status.valueOf(this[Users.status]),
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
}
