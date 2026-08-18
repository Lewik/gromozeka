package com.gromozeka.application.service

import com.gromozeka.domain.model.NamedSecret
import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.SecurityAuditRecord
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.NamedSecretRepository
import com.gromozeka.domain.service.SecurityAuditRecorder
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Clock
import org.springframework.stereotype.Service

@Service
class NamedSecretApplicationService(
    private val repository: NamedSecretRepository,
    private val securityAuditRecorder: SecurityAuditRecorder,
) {
    suspend fun list(userId: User.Id): List<NamedSecret> = repository.list(userId)

    suspend fun save(userId: User.Id, name: String, description: String, value: String): NamedSecret {
        val normalizedName = NamedSecret.normalizeName(name)
        require(NamedSecret.NAME_PATTERN.matches(normalizedName)) { "Invalid secret name" }
        require(value.isNotBlank()) { "Secret value must not be blank" }
        require(value.length <= MAX_VALUE_LENGTH) { "Secret value must not exceed $MAX_VALUE_LENGTH characters" }
        val existing = repository.find(userId, normalizedName)?.metadata
        val now = Clock.System.now()
        val secret = NamedSecret(
            id = existing?.id ?: NamedSecret.Id(uuid7()),
            userId = userId,
            name = normalizedName,
            description = description.trim(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        repository.save(secret, value)
        securityAuditRecorder.record(
            SecurityAuditRecord(
                actorUserId = userId,
                action = SecurityAuditEvent.Action.NAMED_SECRET_SAVED,
                targetType = SecurityAuditEvent.TargetType.NAMED_SECRET,
                targetId = secret.id.value,
                attributes = mapOf("name" to secret.name),
            )
        )
        return secret
    }

    suspend fun delete(userId: User.Id, secretId: NamedSecret.Id): Boolean {
        val deleted = repository.delete(userId, secretId)
        if (deleted) {
            securityAuditRecorder.record(
                SecurityAuditRecord(
                    actorUserId = userId,
                    action = SecurityAuditEvent.Action.NAMED_SECRET_DELETED,
                    targetType = SecurityAuditEvent.TargetType.NAMED_SECRET,
                    targetId = secretId.value,
                )
            )
        }
        return deleted
    }

    suspend fun resolve(userId: User.Id, names: Set<String>): Map<String, String> = names.associateWith { name ->
        repository.find(userId, name)?.value ?: error("Named secret not found: $name")
    }

    private companion object {
        const val MAX_VALUE_LENGTH = 65_536
    }
}
