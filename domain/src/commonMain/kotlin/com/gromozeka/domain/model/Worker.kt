package com.gromozeka.domain.model

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class WorkerResource(
    val id: ConversationRuntimeWorkerId,
    val displayName: String,
    val ownerUserId: User.Id,
    val organizationAccess: Boolean,
    val status: Status,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(displayName.isNotBlank()) { "Worker display name must not be blank" }
    }

    @Serializable
    enum class Status {
        ACTIVE,
        REVOKED,
    }
}

@Serializable
data class WorkerUserGrant(
    val workerId: ConversationRuntimeWorkerId,
    val userId: User.Id,
    val createdAt: Instant,
    val createdByUserId: User.Id,
)

@Serializable
data class WorkerProjectGrant(
    val workerId: ConversationRuntimeWorkerId,
    val projectId: Project.Id,
    val createdAt: Instant,
    val createdByUserId: User.Id,
)

enum class WorkerPermission {
    USE,
    MANAGE,
}
