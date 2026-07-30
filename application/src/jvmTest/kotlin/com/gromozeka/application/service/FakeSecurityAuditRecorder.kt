package com.gromozeka.application.service

import com.gromozeka.domain.model.SecurityAuditRecord
import com.gromozeka.domain.service.SecurityAuditRecorder

internal class FakeSecurityAuditRecorder : SecurityAuditRecorder {
    val records = mutableListOf<SecurityAuditRecord>()

    override suspend fun record(record: SecurityAuditRecord) {
        records += record
    }
}
