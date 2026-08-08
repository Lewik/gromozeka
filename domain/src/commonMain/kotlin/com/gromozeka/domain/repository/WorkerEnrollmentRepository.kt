package com.gromozeka.domain.repository

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.datetime.Instant

interface WorkerEnrollmentRepository {
    suspend fun issue(
        tokenHash: String,
        ownerUserId: User.Id,
        createdAt: Instant,
        expiresAt: Instant,
    )

    suspend fun consume(
        tokenHash: String,
        gatewayCredentialHash: String,
        workerId: ConversationRuntimeWorkerId,
        displayName: String,
        consumedAt: Instant,
        kind: WorkerResource.Kind = WorkerResource.Kind.EXECUTION,
    ): WorkerResource?

    suspend fun authenticateGatewayCredential(
        gatewayCredentialHash: String,
    ): WorkerResource?
}
