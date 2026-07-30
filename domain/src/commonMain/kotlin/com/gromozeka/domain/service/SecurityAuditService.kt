package com.gromozeka.domain.service

import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.SecurityAuditRecord
import com.gromozeka.domain.model.User

/**
 * Records successful security-sensitive mutations from application workflows.
 */
interface SecurityAuditRecorder {
    suspend fun record(record: SecurityAuditRecord)
}

/**
 * Owner-only query surface for recent security audit events.
 */
interface SecurityAuditService {
    suspend fun listRecent(
        actor: User,
        limit: Int,
    ): List<SecurityAuditEvent>
}
