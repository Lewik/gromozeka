package com.gromozeka.client

import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.remote.protocol.ListSecurityAuditEventsRequest
import com.gromozeka.remote.protocol.SecurityAuditEventsResponse

class RemoteSecurityAuditService internal constructor(
    private val client: GromozekaWsClient,
) {
    suspend fun listRecent(limit: Int = 100): List<SecurityAuditEvent> =
        client.requestTyped<ListSecurityAuditEventsRequest, SecurityAuditEventsResponse>(
            ListSecurityAuditEventsRequest(limit)
        ).events
}
