package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.SecurityAuditRepository
import com.gromozeka.infrastructure.db.persistence.tables.SecurityAuditEvents
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Service

@Service
class ExposedSecurityAuditRepository : SecurityAuditRepository {
    override suspend fun append(event: SecurityAuditEvent): SecurityAuditEvent = dbQuery {
        SecurityAuditEvents.insert {
            it[id] = event.id.value
            it[occurredAt] = event.occurredAt.toKotlin()
            it[actorUserId] = event.actorUserId.value
            it[action] = event.action.name
            it[targetType] = event.targetType.name
            it[targetId] = event.targetId
            it[projectId] = event.projectId?.value
            it[attributesJson] = auditJson.encodeToString(event.attributes)
        }
        event
    }

    override suspend fun listRecent(limit: Int): List<SecurityAuditEvent> = dbQuery {
        SecurityAuditEvents.selectAll()
            .orderBy(
                SecurityAuditEvents.occurredAt to SortOrder.DESC,
                SecurityAuditEvents.id to SortOrder.DESC,
            )
            .limit(limit)
            .map { it.toSecurityAuditEvent() }
    }

    private fun ResultRow.toSecurityAuditEvent(): SecurityAuditEvent =
        SecurityAuditEvent(
            id = SecurityAuditEvent.Id(this[SecurityAuditEvents.id]),
            occurredAt = this[SecurityAuditEvents.occurredAt].toKotlinx(),
            actorUserId = User.Id(this[SecurityAuditEvents.actorUserId]),
            action = SecurityAuditEvent.Action.valueOf(this[SecurityAuditEvents.action]),
            targetType = SecurityAuditEvent.TargetType.valueOf(this[SecurityAuditEvents.targetType]),
            targetId = this[SecurityAuditEvents.targetId],
            projectId = this[SecurityAuditEvents.projectId]?.let(Project::Id),
            attributes = auditJson.decodeFromString(this[SecurityAuditEvents.attributesJson]),
        )
}

private val auditJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}
