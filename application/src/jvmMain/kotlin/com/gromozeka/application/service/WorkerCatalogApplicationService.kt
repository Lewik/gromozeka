package com.gromozeka.application.service

import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import com.gromozeka.domain.service.WorkerCatalogEntry
import com.gromozeka.domain.service.WorkerCatalogService
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service

@Service
class WorkerCatalogApplicationService(
    private val workerRegistry: ConversationRuntimeWorkerRegistry,
) : WorkerCatalogService {
    override suspend fun listWorkers(): List<WorkerCatalogEntry> {
        val staleBefore = Clock.System.now() - ConversationRuntimeTiming.workerRegistrationStaleAfter
        return workerRegistry.list()
            .sortedBy { it.identity.workerId.value }
            .map { registration ->
                WorkerCatalogEntry(
                    workerId = registration.identity.workerId,
                    status = if (registration.isOnline(staleBefore)) {
                        WorkerCatalogEntry.Status.ONLINE
                    } else {
                        WorkerCatalogEntry.Status.OFFLINE
                    },
                    version = registration.version,
                    startedAt = registration.startedAt,
                    lastHeartbeatAt = registration.lastHeartbeatAt,
                    environmentProfile = registration.environmentProfile,
                )
            }
    }
}
