package com.gromozeka.presentation.services

import com.gromozeka.client.RemoteDistributionService
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerCatalogEntry
import com.gromozeka.domain.service.WorkerCatalogService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LocalWorkerStatus(
    val supported: Boolean,
    val installed: Boolean = false,
    val running: Boolean = false,
    val workerId: ConversationRuntimeWorkerId? = null,
    val serverStatus: WorkerCatalogEntry.Status? = null,
    val permissions: LocalWorkerPermissions = LocalWorkerPermissions(),
    val operation: LocalWorkerOperation? = null,
    val failure: String? = null,
)

data class LocalWorkerPermissions(
    val screenRecording: LocalWorkerPermissionState = LocalWorkerPermissionState.UNKNOWN,
    val accessibility: LocalWorkerPermissionState = LocalWorkerPermissionState.UNKNOWN,
)

enum class LocalWorkerPermissionState {
    GRANTED,
    NOT_GRANTED,
    UNKNOWN,
}

enum class LocalWorkerOperation {
    STARTING,
    STOPPING,
    ENROLLING,
    REQUESTING_PERMISSIONS,
    REFRESHING,
}

interface LocalWorkerController : AutoCloseable {
    val status: StateFlow<LocalWorkerStatus>

    suspend fun initialize()
    suspend fun refresh(workerCatalogService: WorkerCatalogService? = null)
    suspend fun enable(distributionService: RemoteDistributionService, workerCatalogService: WorkerCatalogService)
    suspend fun disable()
    suspend fun start()
    suspend fun stop()
    suspend fun requestComputerUsePermissions()
    suspend fun stopForApplicationExit()

    override fun close() = Unit
}

object UnsupportedLocalWorkerController : LocalWorkerController {
    override val status: StateFlow<LocalWorkerStatus> = MutableStateFlow(LocalWorkerStatus(supported = false))

    override suspend fun initialize() = Unit
    override suspend fun refresh(workerCatalogService: WorkerCatalogService?) = Unit
    override suspend fun enable(
        distributionService: RemoteDistributionService,
        workerCatalogService: WorkerCatalogService,
    ) = Unit
    override suspend fun disable() = Unit
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun requestComputerUsePermissions() = Unit
    override suspend fun stopForApplicationExit() = Unit
}
