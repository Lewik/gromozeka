package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.DeviceObservation
import com.gromozeka.domain.model.DeviceStateEvent
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class MobileWorkerEventBatchRequest(
    val events: List<MobileWorkerEventInput>,
) {
    init {
        require(events.size <= MAX_MOBILE_WORKER_EVENT_BATCH_SIZE) {
            "Mobile Worker event batch exceeds $MAX_MOBILE_WORKER_EVENT_BATCH_SIZE events"
        }
        require(events.map { it.id }.distinct().size == events.size) {
            "Mobile Worker event batch contains duplicate IDs"
        }
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

const val MAX_MOBILE_WORKER_EVENT_BATCH_SIZE = 100
