package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiUserCredential
import com.gromozeka.domain.repository.AiUserCredentialRepository
import com.gromozeka.infrastructure.db.persistence.tables.AiUserCredentials
import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
internal class ExposedAiUserCredentialRepository(
    private val cipher: AiUserCredentialCipher,
) : AiUserCredentialRepository {
    override suspend fun find(
        userId: User.Id,
        connectionId: AiConnection.Id,
    ): AiUserCredential? = dbQuery {
        AiUserCredentials.selectAll()
            .where {
                (AiUserCredentials.userId eq userId.value) and
                    (AiUserCredentials.connectionId eq connectionId.value)
            }
            .singleOrNull()
            ?.toCredential(userId, connectionId)
    }

    override suspend fun save(
        userId: User.Id,
        connectionId: AiConnection.Id,
        secret: String,
        updatedAt: Instant,
    ): AiUserCredential = dbQuery {
        val associatedData = associatedData(userId, connectionId)
        val encrypted = cipher.encrypt(secret, associatedData)
        val existing = AiUserCredentials.selectAll()
            .where {
                (AiUserCredentials.userId eq userId.value) and
                    (AiUserCredentials.connectionId eq connectionId.value)
            }
            .singleOrNull()
        val createdAt = existing?.get(AiUserCredentials.createdAt) ?: updatedAt
        if (existing == null) {
            AiUserCredentials.insert {
                it[AiUserCredentials.userId] = userId.value
                it[AiUserCredentials.connectionId] = connectionId.value
                it[ciphertext] = encrypted.ciphertext
                it[nonce] = encrypted.nonce
                it[encryptionVersion] = encrypted.version
                it[AiUserCredentials.createdAt] = createdAt
                it[AiUserCredentials.updatedAt] = updatedAt
            }
        } else {
            AiUserCredentials.update(
                where = {
                    (AiUserCredentials.userId eq userId.value) and
                        (AiUserCredentials.connectionId eq connectionId.value)
                }
            ) {
                it[ciphertext] = encrypted.ciphertext
                it[nonce] = encrypted.nonce
                it[encryptionVersion] = encrypted.version
                it[AiUserCredentials.updatedAt] = updatedAt
            }
        }
        AiUserCredential(userId, connectionId, secret, createdAt, updatedAt)
    }

    override suspend fun delete(
        userId: User.Id,
        connectionId: AiConnection.Id,
    ): Boolean = dbQuery {
        AiUserCredentials.deleteWhere {
            (AiUserCredentials.userId eq userId.value) and
                (AiUserCredentials.connectionId eq connectionId.value)
        } > 0
    }

    private fun ResultRow.toCredential(
        userId: User.Id,
        connectionId: AiConnection.Id,
    ): AiUserCredential {
        val encrypted = EncryptedAiUserCredential(
            ciphertext = this[AiUserCredentials.ciphertext],
            nonce = this[AiUserCredentials.nonce],
            version = this[AiUserCredentials.encryptionVersion],
        )
        return AiUserCredential(
            userId = userId,
            connectionId = connectionId,
            secret = cipher.decrypt(encrypted, associatedData(userId, connectionId)),
            createdAt = this[AiUserCredentials.createdAt],
            updatedAt = this[AiUserCredentials.updatedAt],
        )
    }

    private fun associatedData(
        userId: User.Id,
        connectionId: AiConnection.Id,
    ): String = "${userId.value}\u001F${connectionId.value}"
}
