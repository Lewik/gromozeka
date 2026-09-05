package com.gromozeka.worker.runtime

import com.gromozeka.remote.protocol.WorkerGatewayMessage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class SnapshotWorkerRequestJournalTest {
    private class Store : WorkerRequestSnapshotStore {
        var persisted: String? = null
        var failWrite = false
        override suspend fun read() = persisted
        override suspend fun write(snapshot: String) {
            check(!failWrite) { "Disk unavailable" }
            persisted = snapshot
        }
    }

    @Test
    fun `receipts and responses survive recreation while failed writes leave committed state`() = runTest {
        val store = Store()
        val journal = SnapshotWorkerRequestJournal(store)
        val received = WorkerRequestReceipt("request", "fingerprint", Clock.System.now() + 1.minutes, WorkerRequestReceipt.State.RECEIVED)
        journal.save(received)
        assertEquals(listOf(received), SnapshotWorkerRequestJournal(store).load())
        store.failWrite = true
        assertFailsWith<IllegalStateException> { journal.save(received.copy(state = WorkerRequestReceipt.State.RUNNING)) }
        assertEquals(listOf(received), journal.load())
        store.failWrite = false
        val completed = received.copy(state = WorkerRequestReceipt.State.COMPLETED,
            response = WorkerGatewayMessage.Response("request", WorkerGatewayMessage.Response.Status.SUCCEEDED, byteArrayOf(1, 2, 3)))
        journal.save(completed)
        assertEquals(listOf(completed), SnapshotWorkerRequestJournal(store).load())
        SnapshotWorkerRequestJournal(store).delete(received.id)
        assertTrue(journal.load().isEmpty())
    }

    @Test
    fun `storage bound rejects an uncommitted receipt and corrupt snapshots fail closed`() = runTest {
        val store = Store()
        val receipt = WorkerRequestReceipt("request", "fingerprint", Clock.System.now(), WorkerRequestReceipt.State.RECEIVED)
        assertFailsWith<IllegalStateException> { SnapshotWorkerRequestJournal(store, 8).save(receipt) }
        assertEquals(null, store.persisted)
        store.persisted = "not-json"
        assertFailsWith<IllegalArgumentException> { SnapshotWorkerRequestJournal(store).load() }
    }

    @Test
    fun `recovery marks interrupted execution unknown and keeps saved results`() = runTest {
        val store = Store()
        val journal = SnapshotWorkerRequestJournal(store)
        val running = WorkerRequestReceipt("running", "fingerprint", Clock.System.now() + 1.minutes, WorkerRequestReceipt.State.RUNNING)
        journal.save(running)
        val response = WorkerGatewayMessage.Response("done", WorkerGatewayMessage.Response.Status.SUCCEEDED, byteArrayOf(5))
        journal.save(running.copy(id = "done", state = WorkerRequestReceipt.State.COMPLETED, response = response))
        val executor = WorkerRequestExecutor(SnapshotWorkerRequestJournal(store), this, WorkerRequestHandler { error("Must not execute") }, {})
        executor.initialize()
        assertEquals("OUTCOME_UNKNOWN", executor.pendingResponses().single { it.requestId == "running" }.errorCode)
        assertEquals(response, executor.pendingResponses().single { it.requestId == "done" })
        executor.acknowledge("done")
        assertEquals(WorkerRequestReceipt.State.ACKNOWLEDGED, SnapshotWorkerRequestJournal(store).load().single { it.id == "done" }.state)
    }
}
