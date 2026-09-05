package com.gromozeka.worker.runtime

import com.gromozeka.domain.model.DeviceStateEvent
import com.gromozeka.remote.protocol.WorkerEventBatchResponse
import com.gromozeka.remote.protocol.WorkerEventInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

internal class TestWorkerEventStore : WorkerEventOutboxStore {
    var persisted = Json.encodeToString(WorkerEventOutboxState("stream"))
    var failWrite = false
    private val mutex = Mutex()
    override suspend fun read() = mutex.withLock { Json.decodeFromString<WorkerEventOutboxState>(persisted) }
    override suspend fun update(transform: (WorkerEventOutboxState) -> WorkerEventOutboxState) = mutex.withLock {
        val next = transform(Json.decodeFromString(persisted))
        check(!failWrite) { "Disk unavailable" }
        persisted = Json.encodeToString(next)
        next
    }
}

class WorkerEventOutboxTest {
    private val time = Instant.parse("2026-09-05T00:00:00Z")
    private val store = TestWorkerEventStore()
    private val synchronization = Mutex()
    private fun outbox(limits: WorkerEventOutboxLimits = WorkerEventOutboxLimits()) = WorkerEventOutbox("stream", store, synchronization, limits)
    private fun event(id: String) = WorkerEventInput(id, time, DeviceStateEvent.CustomTrigger(id))
    private fun ack(events: List<WorkerEventInput>) = WorkerEventBatchResponse(events.mapTo(linkedSetOf()) { it.id }, emptySet(), time)

    @Test
    fun `lost acknowledgement survives restart and resends unchanged IDs`() = runTest {
        val events = listOf(event("a"), event("b"))
        outbox().append(events)
        assertFailsWith<IllegalStateException> {
            outbox().synchronize(WorkerEventBatchSender { batch, _ -> assertEquals(events, batch); error("Response lost") })
        }
        assertEquals(events, store.read().pending)
        assertEquals(2, outbox().synchronize(WorkerEventBatchSender { batch, _ ->
            assertEquals(events, batch)
            WorkerEventBatchResponse(emptySet(), batch.mapTo(linkedSetOf()) { it.id }, time)
        }))
        assertTrue(store.read().pending.isEmpty())
        assertEquals(time, store.read().lastAcknowledgedAt)
    }

    @Test
    fun `recording continues during network wait and acknowledgement removes only sent events`() = runTest {
        outbox().append(listOf(event("first")))
        val sent = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sending = async {
            outbox().synchronize(WorkerEventBatchSender { batch, _ -> sent.complete(Unit); release.await(); ack(batch) }, maxBatches = 1)
        }
        sent.await()
        outbox().append(listOf(event("second")))
        assertEquals(2, store.read().pending.size)
        release.complete(Unit)
        assertEquals(1, sending.await())
        assertEquals(listOf(event("second")), store.read().pending)
    }

    @Test
    fun `cancellation and failed local acknowledgement leave pending events durable`() = runTest {
        outbox().append(listOf(event("first")))
        val started = CompletableDeferred<Unit>()
        val sending = launch { outbox().synchronize(WorkerEventBatchSender { _, _ -> started.complete(Unit); awaitCancellation() }) }
        started.await()
        sending.cancelAndJoin()
        store.failWrite = true
        assertFailsWith<IllegalStateException> { outbox().synchronize(WorkerEventBatchSender { batch, _ -> ack(batch) }) }
        assertEquals(listOf(event("first")), store.read().pending)
    }

    @Test
    fun `foreign partial and overlapping acknowledgements cannot delete events`() = runTest {
        outbox().append(listOf(event("a"), event("b")))
        for (response in listOf(
            WorkerEventBatchResponse(setOf("other"), emptySet(), time),
            WorkerEventBatchResponse(setOf("a"), emptySet(), time),
            WorkerEventBatchResponse(setOf("a", "b"), setOf("a"), time),
        )) {
            assertFailsWith<IllegalArgumentException> { outbox().synchronize(WorkerEventBatchSender { _, _ -> response }) }
            assertEquals(2, store.read().pending.size)
        }
    }

    @Test
    fun `old server response cannot mutate a replacement enrollment`() = runTest {
        outbox().append(listOf(event("old")))
        val replacement = WorkerEventOutboxState("new-stream", listOf(event("new")))
        assertFailsWith<WorkerEventOutboxReplacedException> {
            outbox().synchronize(WorkerEventBatchSender { batch, _ -> store.update { replacement }; ack(batch) })
        }
        assertEquals(replacement, store.read())
    }

    @Test
    fun `capacity failure preserves all existing events and rejects append atomically`() = runTest {
        val bounded = outbox(WorkerEventOutboxLimits(maxEvents = 2))
        bounded.append(listOf(event("first")))
        assertFailsWith<WorkerEventOutboxFullException> { bounded.append(listOf(event("second"), event("third"))) }
        assertEquals(listOf(event("first")), store.read().pending)
        assertFailsWith<WorkerEventOutboxFullException> {
            outbox(WorkerEventOutboxLimits(maxStoredBytes = 10)).append(listOf(event("second")))
        }
        assertEquals(listOf(event("first")), store.read().pending)
    }

    @Test
    fun `unchanged state is deduplicated but location samples and older observations are retained`() = runTest {
        val battery = WorkerEventInput("battery", time, DeviceStateEvent.Battery(50, false))
        assertEquals(1, outbox().append(listOf(battery)))
        assertEquals(0, outbox().append(listOf(battery.copy(id = "same-value"))))
        val older = battery.copy(id = "older", observedAt = Instant.parse("2026-09-04T00:00:00Z"), payload = DeviceStateEvent.Battery(40, false))
        outbox().append(listOf(older))
        assertEquals(battery, store.read().latest["battery"])
        val location = WorkerEventInput("location", time, DeviceStateEvent.Location(1.0, 2.0, 3.0, cause = com.gromozeka.domain.model.LocationCause.CURRENT))
        outbox().append(listOf(location, location.copy(id = "same-location")))
        assertEquals(4, store.read().pending.size)
        assertFailsWith<IllegalArgumentException> { outbox().append(listOf(battery.copy(payload = DeviceStateEvent.Battery(20, false)))) }
    }

    @Test
    fun `batches respect byte and count bounds and a single synchronization is bounded`() = runTest {
        val events = (1..8).map { event("event-$it") }
        val size = Json { encodeDefaults = true }.encodeToString(events.first()).encodeToByteArray().size
        val bounded = outbox(WorkerEventOutboxLimits(maxBatchEvents = 3, maxBatchEventBytes = size * 2, maxEventBytes = size))
        bounded.append(events)
        val batches = mutableListOf<List<WorkerEventInput>>()
        assertEquals(4, bounded.synchronize(WorkerEventBatchSender { batch, _ -> batches += batch; ack(batch) }, maxBatches = 2))
        assertEquals(listOf(2, 2), batches.map { it.size })
        assertEquals(events.drop(4), store.read().pending)
    }
}
