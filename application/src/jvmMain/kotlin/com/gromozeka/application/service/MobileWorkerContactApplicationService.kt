package com.gromozeka.application.service

import com.gromozeka.domain.model.MobileWorkerAppState
import com.gromozeka.domain.model.MobileWorkerContactKind
import com.gromozeka.domain.model.MobileWorkerContactObservation
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.repository.MobileWorkerContactRepository
import kotlin.time.Clock
import kotlin.time.Instant
import org.springframework.stereotype.Service

@Service
class MobileWorkerContactApplicationService(
    private val repository: MobileWorkerContactRepository,
) {
    suspend fun record(
        worker: WorkerResource,
        requestId: String,
        kind: MobileWorkerContactKind,
        appState: MobileWorkerAppState,
        appVersion: String?,
        workerSentAt: Instant?,
        eventCount: Int,
        pendingEventCount: Int?,
        receivedAt: Instant = Clock.System.now(),
    ): Instant {
        require(worker.subjectUserId != null) {
            "Worker must be bound to a user to report contact observations"
        }
        repository.record(
            MobileWorkerContactObservation(
                requestId = requestId,
                workerId = worker.id,
                subjectUserId = requireNotNull(worker.subjectUserId),
                kind = kind,
                appState = appState,
                appVersion = appVersion,
                workerSentAt = workerSentAt,
                receivedAt = receivedAt,
                eventCount = eventCount,
                pendingEventCount = pendingEventCount,
            )
        )
        return receivedAt
    }
}
