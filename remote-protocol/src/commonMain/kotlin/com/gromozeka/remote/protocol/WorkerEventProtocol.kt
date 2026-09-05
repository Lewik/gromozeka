package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.DeviceObservation
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.WorkerAppState
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class WorkerEventBatchRequest(
    val events: List<WorkerEventInput>,
    val contact: WorkerContactMetadata? = null,
) {
    init {
        require(events.isNotEmpty()) { "Worker event batch must not be empty" }
        require(events.size <= MAX_WORKER_EVENT_BATCH_SIZE) {
            "Worker event batch exceeds $MAX_WORKER_EVENT_BATCH_SIZE events"
        }
        require(events.map { it.id }.distinct().size == events.size) {
            "Worker event batch contains duplicate IDs"
        }
        require(contact == null || contact.pendingEventCount >= events.size) {
            "Worker pending event count must include the submitted events"
        }
    }
}

@Serializable
data class WorkerHeartbeatRequest(
    val contact: WorkerContactMetadata,
)

@Serializable
data class WorkerContactMetadata(
    val requestId: String,
    val sentAt: Instant,
    val appState: WorkerAppState,
    val appVersion: String,
    val pendingEventCount: Int,
) {
    init {
        require(requestId.matches(workerRequestIdPattern)) {
            "Worker request ID must contain 1-128 letters, digits, dots, dashes, or underscores"
        }
        require(appVersion.isNotBlank() && appVersion.length <= 255) {
            "Worker version must be non-blank and at most 255 characters"
        }
        require(pendingEventCount >= 0) { "Worker pending event count must not be negative" }
    }
}

@Serializable
data class WorkerEventInput(
    val id: String,
    val observedAt: Instant,
    val payload: DeviceStateEvent,
) {
    init { DeviceObservation(id, observedAt, payload) }
    fun toObservation(): DeviceObservation = DeviceObservation(id, observedAt, payload)
}

@Serializable
data class WorkerEventBatchResponse(
    val acceptedEventIds: Set<String>,
    val duplicateEventIds: Set<String>,
    val serverReceivedAt: Instant,
)

@Serializable
data class WorkerHeartbeatResponse(
    val serverReceivedAt: Instant,
)

const val MAX_WORKER_EVENT_BATCH_SIZE = 100

private val workerRequestIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
