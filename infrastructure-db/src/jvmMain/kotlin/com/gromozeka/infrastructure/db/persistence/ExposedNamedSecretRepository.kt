package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.NamedSecret
import com.gromozeka.domain.model.StoredNamedSecret
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.NamedSecretRepository
import com.gromozeka.infrastructure.db.persistence.tables.NamedSecrets
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
internal class ExposedNamedSecretRepository(
    private val cipher: SecretCipher,
) : NamedSecretRepository {
    override suspend fun list(userId: User.Id): List<NamedSecret> = dbQuery {
        NamedSecrets.selectAll()
            .where { NamedSecrets.userId eq userId.value }
            .orderBy(NamedSecrets.name)
            .map { it.toMetadata() }
    }

    override suspend fun find(userId: User.Id, name: String): StoredNamedSecret? = dbQuery {
        NamedSecrets.selectAll()
            .where { (NamedSecrets.userId eq userId.value) and (NamedSecrets.name eq name) }
            .singleOrNull()
            ?.toStoredSecret()
    }

    override suspend fun save(secret: NamedSecret, value: String): NamedSecret = dbQuery {
        val associatedData = associatedData(secret.userId, secret.id)
        val encrypted = cipher.encrypt(value, associatedData)
        val exists = NamedSecrets.selectAll()
            .where { NamedSecrets.id eq secret.id.value }
            .any()
        if (exists) {
            NamedSecrets.update({ NamedSecrets.id eq secret.id.value }) {
                it[name] = secret.name
                it[description] = secret.description
                it[ciphertext] = encrypted.ciphertext
                it[nonce] = encrypted.nonce
                it[encryptionVersion] = encrypted.version
                it[updatedAt] = secret.updatedAt
            }
        } else {
            NamedSecrets.insert {
                it[id] = secret.id.value
                it[userId] = secret.userId.value
                it[name] = secret.name
                it[description] = secret.description
                it[ciphertext] = encrypted.ciphertext
                it[nonce] = encrypted.nonce
                it[encryptionVersion] = encrypted.version
                it[createdAt] = secret.createdAt
                it[updatedAt] = secret.updatedAt
            }
        }
        secret
    }

    override suspend fun delete(userId: User.Id, secretId: NamedSecret.Id): Boolean = dbQuery {
        NamedSecrets.deleteWhere {
            (NamedSecrets.userId eq userId.value) and (NamedSecrets.id eq secretId.value)
        } > 0
    }

    private fun ResultRow.toMetadata() = NamedSecret(
        id = NamedSecret.Id(this[NamedSecrets.id]),
        userId = User.Id(this[NamedSecrets.userId]),
        name = this[NamedSecrets.name],
        description = this[NamedSecrets.description],
        createdAt = this[NamedSecrets.createdAt],
        updatedAt = this[NamedSecrets.updatedAt],
    )

    private fun ResultRow.toStoredSecret(): StoredNamedSecret {
        val metadata = toMetadata()
        val encrypted = EncryptedSecret(
            ciphertext = this[NamedSecrets.ciphertext],
            nonce = this[NamedSecrets.nonce],
            version = this[NamedSecrets.encryptionVersion],
        )
        return StoredNamedSecret(
            metadata = metadata,
            value = cipher.decrypt(encrypted, associatedData(metadata.userId, metadata.id)),
        )
    }

    private fun associatedData(userId: User.Id, secretId: NamedSecret.Id): String =
        "named-secret\u001F${userId.value}\u001F${secretId.value}"
}
