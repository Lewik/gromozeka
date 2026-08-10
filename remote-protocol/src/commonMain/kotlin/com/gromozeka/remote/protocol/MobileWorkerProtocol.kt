package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.DeviceObservation
import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.MobileWorkerAppState
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class MobileWorkerEventBatchRequest(
    val events: List<MobileWorkerEventInput>,
    val contact: MobileWorkerContactMetadata? = null,
) {
    init {
        require(events.isNotEmpty()) { "Mobile Worker event batch must not be empty" }
        require(events.size <= MAX_MOBILE_WORKER_EVENT_BATCH_SIZE) {
            "Mobile Worker event batch exceeds $MAX_MOBILE_WORKER_EVENT_BATCH_SIZE events"
        }
        require(events.map { it.id }.distinct().size == events.size) {
            "Mobile Worker event batch contains duplicate IDs"
        }
        require(contact == null || contact.pendingEventCount >= events.size) {
            "Mobile Worker pending event count must include the submitted events"
        }
    }
}

@Serializable
data class MobileWorkerHeartbeatRequest(
    val contact: MobileWorkerContactMetadata,
)

@Serializable
data class MobileWorkerContactMetadata(
    val requestId: String,
    val sentAt: Instant,
    val appState: MobileWorkerAppState,
    val appVersion: String,
    val pendingEventCount: Int,
) {
    init {
        require(requestId.matches(mobileWorkerRequestIdPattern)) {
            "Mobile Worker request ID must contain 1-128 letters, digits, dots, dashes, or underscores"
        }
        require(appVersion.isNotBlank() && appVersion.length <= 255) {
            "Mobile Worker version must be non-blank and at most 255 characters"
        }
        require(pendingEventCount >= 0) { "Mobile Worker pending event count must not be negative" }
    }
}

@Serializable
data class MobileWorkerEventInput(
    val id: String,
    val observedAt: Instant,
    val payload: DeviceStateEvent,
) {
    fun toObservation(): DeviceObservation = DeviceObservation(id, observedAt, payload)
}

@Serializable
data class MobileWorkerEventBatchResponse(
    val acceptedEventIds: Set<String>,
    val duplicateEventIds: Set<String>,
    val serverReceivedAt: Instant,
)

@Serializable
data class MobileWorkerHeartbeatResponse(
    val serverReceivedAt: Instant,
)

const val MAX_MOBILE_WORKER_EVENT_BATCH_SIZE = 100

private val mobileWorkerRequestIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
