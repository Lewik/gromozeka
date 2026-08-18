package com.gromozeka.domain.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Append-only record of a successful identity or access change.
 *
 * Attributes may contain descriptive metadata, but never credentials or user content.
 */
@Serializable
data class SecurityAuditEvent(
    val id: Id,
    val occurredAt: Instant,
    val actorUserId: User.Id,
    val action: Action,
    val targetType: TargetType,
    val targetId: String,
    val projectId: Project.Id? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "Security audit event id must not be blank" }
        }
    }

    @Serializable
    enum class Action {
        RUNTIME_BOOTSTRAPPED,
        USER_CREATED,
        USER_UPDATED,
        USER_PASSWORD_RESET,
        PROJECT_CREATED,
        PROJECT_DELETED,
        PROJECT_MEMBERSHIP_SET,
        PROJECT_MEMBERSHIP_REMOVED,
        PERSONAL_ACCESS_TOKEN_ISSUED,
        PERSONAL_ACCESS_TOKEN_REVOKED,
        AI_USER_CREDENTIAL_CONFIGURED,
        AI_USER_CREDENTIAL_REMOVED,
        NAMED_SECRET_SAVED,
        NAMED_SECRET_DELETED,
        DEVICE_CONNECTION_APPROVED,
        DEVICE_CONNECTION_DENIED,
        DEVICE_CONNECTED,
        WORKER_ENROLLMENT_CREATED,
        WORKER_ENROLLED,
        WORKER_USER_GRANT_SET,
        WORKER_USER_GRANT_REMOVED,
        WORKER_PROJECT_GRANT_SET,
        WORKER_PROJECT_GRANT_REMOVED,
        WORKER_RUNTIME_ACCESS_UPDATED,
        WORKER_REVOKED,
    }

    @Serializable
    enum class TargetType {
        RUNTIME,
        USER,
        PROJECT,
        PERSONAL_ACCESS_TOKEN,
        AI_CONNECTION,
        NAMED_SECRET,
        DEVICE_CONNECTION,
        WORKER,
    }
}

/**
 * Intent recorded by an application workflow after its protected mutation succeeds.
 */
data class SecurityAuditRecord(
    val actorUserId: User.Id,
    val action: SecurityAuditEvent.Action,
    val targetType: SecurityAuditEvent.TargetType,
    val targetId: String,
    val projectId: Project.Id? = null,
    val attributes: Map<String, String> = emptyMap(),
)
