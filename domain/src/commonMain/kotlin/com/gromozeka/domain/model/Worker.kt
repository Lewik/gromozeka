package com.gromozeka.domain.model

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class WorkerResource(
    val id: ConversationRuntimeWorkerId,
    val displayName: String,
    val ownerUserId: User.Id,
    val kind: Kind = Kind.EXECUTION,
    val subjectUserId: User.Id? = null,
    val runtimeWideAccess: Boolean,
    val status: Status,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(displayName.isNotBlank()) { "Worker display name must not be blank" }
        require((kind == Kind.MOBILE_DEVICE) == (subjectUserId != null)) {
            "Only mobile device Workers must have a subject user"
        }
    }

    @Serializable
    enum class Kind {
        EXECUTION,
        MOBILE_DEVICE,
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
