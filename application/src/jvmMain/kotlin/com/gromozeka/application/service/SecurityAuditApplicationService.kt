package com.gromozeka.application.service

import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.SecurityAuditRecord
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.SecurityAuditRepository
import com.gromozeka.domain.service.SecurityAuditRecorder
import com.gromozeka.domain.service.SecurityAuditService
import com.gromozeka.domain.service.UserAdministrationDeniedException
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Clock
import org.springframework.stereotype.Service

@Service
class SecurityAuditApplicationService(
    private val repository: SecurityAuditRepository,
) : SecurityAuditRecorder, SecurityAuditService {
    override suspend fun record(record: SecurityAuditRecord) {
        repository.append(
            SecurityAuditEvent(
                id = SecurityAuditEvent.Id(uuid7()),
                occurredAt = Clock.System.now(),
                actorUserId = record.actorUserId,
                action = record.action,
                targetType = record.targetType,
                targetId = record.targetId,
                projectId = record.projectId,
                attributes = record.attributes.toSortedMap(),
            )
        )
    }

    override suspend fun listRecent(
        actor: User,
        limit: Int,
    ): List<SecurityAuditEvent> {
        if (actor.status != User.Status.ACTIVE || actor.role != User.Role.OWNER) {
            throw UserAdministrationDeniedException()
        }
        require(limit in 1..MAX_AUDIT_EVENTS) {
            "Security audit limit must be between 1 and $MAX_AUDIT_EVENTS"
        }
        return repository.listRecent(limit)
    }

    private companion object {
        const val MAX_AUDIT_EVENTS = 500
    }
}
