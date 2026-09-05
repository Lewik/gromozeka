package com.gromozeka.worker.runtime

import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.domain.model.projectionKey
import com.gromozeka.remote.protocol.MAX_WORKER_EVENT_BATCH_SIZE
import com.gromozeka.remote.protocol.WorkerEventBatchResponse
import com.gromozeka.remote.protocol.WorkerEventInput
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

interface WorkerEventOutboxStore {
    suspend fun read(): WorkerEventOutboxState
    suspend fun update(transform: (WorkerEventOutboxState) -> WorkerEventOutboxState): WorkerEventOutboxState
}

fun interface WorkerEventBatchSender {
    suspend fun send(events: List<WorkerEventInput>, pendingCount: Int): WorkerEventBatchResponse
}

@Serializable
data class WorkerEventOutboxState(
    val streamId: String,
    val pending: List<WorkerEventInput> = emptyList(),
    val latest: Map<String, WorkerEventInput> = emptyMap(),
    val lastAcknowledgedAt: Instant? = null,
) {
    init { require(streamId.isNotBlank()) }
}

data class WorkerEventOutboxLimits(
    val maxEvents: Int = 10_000,
    val maxStoredBytes: Int = 8 * 1024 * 1024,
    val maxEventBytes: Int = 64 * 1024,
    val maxBatchEventBytes: Int = 192 * 1024,
    val maxBatchEvents: Int = MAX_WORKER_EVENT_BATCH_SIZE,
    val maxLatestValues: Int = 256,
) {
    init {
        require(maxEvents > 0 && maxStoredBytes > 0 && maxLatestValues > 0)
        require(maxEventBytes in 1..maxBatchEventBytes && maxBatchEventBytes <= 192 * 1024)
        require(maxBatchEvents in 1..MAX_WORKER_EVENT_BATCH_SIZE)
    }
}

class WorkerEventOutboxFullException : IllegalStateException("Worker event outbox is full; existing events were preserved")
class WorkerEventOutboxReplacedException : IllegalStateException("Worker event stream was replaced")

class WorkerEventOutbox(
    private val streamId: String,
    private val store: WorkerEventOutboxStore,
    private val synchronization: Mutex,
    private val limits: WorkerEventOutboxLimits = WorkerEventOutboxLimits(),
) {
    suspend fun append(events: List<WorkerEventInput>, suppressUnchanged: Boolean = true): Int {
        require(events.map { it.id }.distinct().size == events.size) { "Event IDs must be unique" }
        events.forEach { require(encodedSize(it) <= limits.maxEventBytes) { "Worker event exceeds the size limit" } }
        var appended = 0
        store.update { initial ->
            requireStream(initial)
            val pending = initial.pending.toMutableList()
            val latest = initial.latest.toMutableMap()
            val existing = pending.associateBy { it.id }.toMutableMap()
            for (event in events) {
                val duplicate = existing[event.id]
                if (duplicate != null) {
                    require(duplicate == event) { "Worker event ID was reused with different content" }
                    continue
                }
                val key = event.payload.projectionKey()
                val previous = latest[key]
                if (suppressUnchanged && event.payload !is DeviceStateEvent.Location &&
                    previous?.payload == event.payload && event.observedAt >= previous.observedAt
                ) continue
                pending += event
                existing[event.id] = event
                if (key != null && (previous == null || event.observedAt >= previous.observedAt)) latest[key] = event
            }
            val retainedLatest = latest.entries.sortedByDescending { it.value.observedAt }.take(limits.maxLatestValues)
                .associate { it.key to it.value }
            val updated = initial.copy(pending = pending, latest = retainedLatest)
            if (pending.size > limits.maxEvents || json.encodeToString(updated).encodeToByteArray().size > limits.maxStoredBytes) {
                throw WorkerEventOutboxFullException()
            }
            appended = pending.size - initial.pending.size
            updated
        }
        return appended
    }

    suspend fun synchronize(sender: WorkerEventBatchSender, maxBatches: Int = 5): Int = synchronization.withLock {
        require(maxBatches > 0)
        var acknowledged = 0
        repeat(maxBatches) {
            val snapshot = store.read().also(::requireStream)
            if (snapshot.pending.isEmpty()) return@withLock acknowledged
            var bytes = 0
            val batch = snapshot.pending.take(limits.maxBatchEvents).takeWhile {
                bytes += encodedSize(it)
                bytes <= limits.maxBatchEventBytes
            }
            check(batch.isNotEmpty()) { "Stored Worker event exceeds the batch size limit" }
            val response = sender.send(batch, snapshot.pending.size)
            val ids = batch.mapTo(linkedSetOf()) { it.id }
            require(response.acceptedEventIds.intersect(response.duplicateEventIds).isEmpty() &&
                response.acceptedEventIds + response.duplicateEventIds == ids
            ) { "Server acknowledgement does not match the submitted Worker batch" }
            store.update { current ->
                requireStream(current)
                check(current.pending.filter { it.id in ids } == batch) { "In-flight Worker events were modified" }
                current.copy(
                    pending = current.pending.filterNot { it.id in ids },
                    lastAcknowledgedAt = response.serverReceivedAt,
                )
            }
            acknowledged += batch.size
        }
        acknowledged
    }

    private fun requireStream(state: WorkerEventOutboxState) {
        if (state.streamId != streamId) throw WorkerEventOutboxReplacedException()
    }

    private fun encodedSize(event: WorkerEventInput) = json.encodeToString(event).encodeToByteArray().size

    private companion object {
        val json = Json { encodeDefaults = true }
    }
}
