package com.gromozeka.domain.model

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class WorkerResource(
    val id: ConversationRuntimeWorkerId,
    val displayName: String,
    val ownerUserId: User.Id,
    val platform: String? = null,
    val subjectUserId: User.Id? = null,
    val runtimeWideAccess: Boolean,
    val status: Status,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(displayName.isNotBlank()) { "Worker display name must not be blank" }
        require(platform == null || platform.isNotBlank() && platform.length <= 64) {
            "Worker platform must contain between 1 and 64 characters"
        }
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
