package com.gromozeka.domain.repository

import com.gromozeka.domain.model.SecurityAuditEvent

/**
 * Append-only persistence boundary for security audit events.
 */
interface SecurityAuditRepository {
    suspend fun append(event: SecurityAuditEvent): SecurityAuditEvent
    suspend fun listRecent(limit: Int): List<SecurityAuditEvent>
}
