package com.gromozeka.domain.service

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

    fun observeWorkers(): Flow<List<WorkerCatalogEntry>> = flow {
        emit(listWorkers())
    }
}
