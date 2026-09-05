package com.gromozeka.domain.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class WorkerRequestDelivery(
    val startDeadline: Instant,
    val executionTimeoutMillis: Long,
    val cancelRequested: Boolean = false,
) {
    init {
        require(executionTimeoutMillis in 1..86_400_000) { "Worker execution timeout must be between 1 ms and 24 hours" }
    }
}

@Serializable
data class WorkerRequestPolicy(
    val deliveryTtlMillis: Long = 30_000,
    val executionTimeoutMillis: Long = 1_800_000,
    val waitTimeoutMillis: Long = minOf(deliveryTtlMillis + executionTimeoutMillis, 604_800_000),
) {
    init {
        require(deliveryTtlMillis in 1..604_800_000) { "Worker delivery TTL must be between 1 ms and 7 days" }
        require(executionTimeoutMillis in 1..86_400_000) { "Worker execution timeout must be between 1 ms and 24 hours" }
        require(waitTimeoutMillis in 1..604_800_000) { "Worker response wait must be between 1 ms and 7 days" }
    }
}

data class StoredWorkerRequest(
    val id: String,
    val workerId: ConversationRuntimeWorkerId,
    val request: ByteArray,
    val createdAt: Instant,
    val startDeadline: Instant,
    val actorUserId: User.Id? = null,
    val projectId: Project.Id? = null,
    val dispatchedAt: Instant? = null,
    val cancelRequestedAt: Instant? = null,
    val response: ByteArray? = null,
    val completedAt: Instant? = null,
)

data class PendingWorkerRequest(val id: String, val cancelRequested: Boolean)

data class WorkerRequestProgress(val dispatchedAt: Instant?, val cancelRequestedAt: Instant?, val completedAt: Instant?)
