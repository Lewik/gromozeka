package com.gromozeka.domain.service

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class WorkerCatalogEntry(
    val workerId: ConversationRuntimeWorkerId,
    val status: Status,
    val version: String,
    val startedAt: Instant,
    val lastHeartbeatAt: Instant,
    val environmentProfile: WorkerEnvironmentProfile,
) {
    @Serializable
    enum class Status {
        ONLINE,
        OFFLINE,
    }
}

interface WorkerCatalogService {
    suspend fun listWorkers(): List<WorkerCatalogEntry>
}
