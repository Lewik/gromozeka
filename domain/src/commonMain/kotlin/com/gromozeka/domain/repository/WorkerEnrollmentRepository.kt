package com.gromozeka.domain.repository

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlin.time.Instant

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
        platform: String? = null,
        bindToUser: Boolean = false,
    ): WorkerResource?

    suspend fun authenticateGatewayCredential(
        gatewayCredentialHash: String,
    ): WorkerResource?
}
