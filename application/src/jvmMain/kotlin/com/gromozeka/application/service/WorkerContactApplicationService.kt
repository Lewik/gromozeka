package com.gromozeka.application.service

import com.gromozeka.domain.model.WorkerAppState
import com.gromozeka.domain.model.WorkerContactKind
import com.gromozeka.domain.model.WorkerContactObservation
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.repository.WorkerContactRepository
import kotlin.time.Clock
import kotlin.time.Instant
import org.springframework.stereotype.Service

@Service
class WorkerContactApplicationService(
    private val repository: WorkerContactRepository,
) {
    suspend fun record(
        worker: WorkerResource,
        requestId: String,
        kind: WorkerContactKind,
        appState: WorkerAppState,
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
            WorkerContactObservation(
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
