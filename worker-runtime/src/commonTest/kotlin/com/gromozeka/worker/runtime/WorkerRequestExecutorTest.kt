package com.gromozeka.worker.runtime

import com.gromozeka.domain.service.WorkerRequestDelivery
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

internal class TestWorkerRequestJournal : WorkerRequestJournal {
    val receipts = mutableMapOf<String, WorkerRequestReceipt>()
    override suspend fun load() = receipts.values.toList()
    override suspend fun save(receipt: WorkerRequestReceipt) { receipts[receipt.id] = receipt }
    override suspend fun delete(id: String) { receipts.remove(id) }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkerRequestExecutorTest {
    @Test
    fun `duplicate delivery runs once and saved result survives a restart`() = runTest {
        val journal = TestWorkerRequestJournal()
        val responses = Channel<WorkerGatewayMessage.Response>(Channel.UNLIMITED)
        val finish = CompletableDeferred<Unit>()
        var runs = 0
        val executor = WorkerRequestExecutor(journal, backgroundScope, WorkerRequestHandler {
            assertEquals(WorkerRequestReceipt.State.RUNNING, journal.receipts[it.id]?.state)
            runs++
            finish.await()
            success(it.id)
        }, responses::send)
        executor.initialize()
        val request = request("request")
        executor.accept(request)
        runCurrent()
        executor.accept(request)
        assertEquals(1, runs)
        finish.complete(Unit)
        assertEquals(success(request.id), responses.receive())
        assertEquals(WorkerRequestReceipt.State.COMPLETED, journal.receipts[request.id]?.state)

        val restarted = WorkerRequestExecutor(journal, backgroundScope, WorkerRequestHandler { error("Must not repeat") }, responses::send)
        restarted.initialize()
        restarted.accept(request)
        assertEquals(success(request.id), responses.receive())
        restarted.acknowledge(request.id)
        assertEquals(WorkerRequestReceipt.State.ACKNOWLEDGED, journal.receipts[request.id]?.state)
        restarted.accept(request)
        assertEquals("RESULT_ACKNOWLEDGED", responses.receive().errorCode)
    }

    @Test
    fun `process crash after start produces unknown outcome without executing again`() = runTest {
        val journal = TestWorkerRequestJournal()
        val executor = WorkerRequestExecutor(journal, backgroundScope, WorkerRequestHandler { awaitCancellation() }, {})
        executor.initialize()
        val request = request("crashed")
        executor.accept(request)
        runCurrent()
        val recoveredJournal = TestWorkerRequestJournal().also { it.receipts.putAll(journal.receipts) }
        val responses = Channel<WorkerGatewayMessage.Response>(Channel.UNLIMITED)
        val recovered = WorkerRequestExecutor(recoveredJournal, backgroundScope, WorkerRequestHandler { error("Must not execute") }, responses::send)
        recovered.initialize()
        recovered.accept(request)
        assertEquals("OUTCOME_UNKNOWN", responses.receive().errorCode)
        executor.stop()
    }

    @Test
    fun `expired and cancelled undelivered requests never start`() = runTest {
        val responses = Channel<WorkerGatewayMessage.Response>(Channel.UNLIMITED)
        val executor = WorkerRequestExecutor(TestWorkerRequestJournal(), backgroundScope, WorkerRequestHandler { error("Must not execute") }, responses::send)
        executor.initialize()
        val expired = request("expired").let { it.copy(delivery = it.delivery!!.copy(startDeadline = Clock.System.now() - 1.seconds)) }
        executor.accept(expired)
        assertEquals("EXPIRED", responses.receive().errorCode)
        val cancelled = request("cancelled").let { it.copy(delivery = it.delivery!!.copy(cancelRequested = true)) }
        executor.accept(cancelled)
        assertEquals("CANCELLED", responses.receive().errorCode)
    }

    @Test
    fun `cancellation redelivery stops the original execution without starting another`() = runTest {
        var runs = 0
        val responses = Channel<WorkerGatewayMessage.Response>(Channel.UNLIMITED)
        val executor = WorkerRequestExecutor(TestWorkerRequestJournal(), backgroundScope, WorkerRequestHandler { runs++; awaitCancellation() }, responses::send)
        executor.initialize()
        val request = request("cancel")
        executor.accept(request)
        runCurrent()
        executor.accept(request.copy(delivery = request.delivery!!.copy(cancelRequested = true)))
        assertEquals("CANCELLED", responses.receive().errorCode)
        assertEquals(1, runs)
        executor.accept(request)
        assertEquals("CANCELLED", responses.receive().errorCode)
        assertEquals(1, runs)
    }

    @Test
    fun `cancellation before execution coroutine runs still saves a terminal receipt`() = runTest {
        val responses = Channel<WorkerGatewayMessage.Response>(Channel.UNLIMITED)
        val executor = WorkerRequestExecutor(TestWorkerRequestJournal(), backgroundScope, WorkerRequestHandler { error("Must not execute") }, responses::send)
        executor.initialize()
        val request = request("cancel-before-start")
        executor.accept(request)
        executor.cancel(request.id)
        assertEquals("CANCELLED", responses.receive().errorCode)
        executor.accept(request)
        assertEquals("CANCELLED", responses.receive().errorCode)
    }

    @Test
    fun `execution timeout is separate from delivery TTL`() = runTest {
        val responses = Channel<WorkerGatewayMessage.Response>(Channel.UNLIMITED)
        val executor = WorkerRequestExecutor(TestWorkerRequestJournal(), backgroundScope, WorkerRequestHandler { awaitCancellation() }, responses::send)
        executor.initialize()
        executor.accept(request("timeout").let { it.copy(delivery = it.delivery!!.copy(executionTimeoutMillis = 25)) })
        advanceTimeBy(30)
        assertEquals("EXECUTION_TIMEOUT", responses.receive().errorCode)
    }

    @Test
    fun `different payload under the same id is rejected`() = runTest {
        val executor = WorkerRequestExecutor(TestWorkerRequestJournal(), backgroundScope, WorkerRequestHandler { success(it.id) }, {})
        executor.initialize()
        val request = request("immutable")
        executor.accept(request)
        runCurrent()
        assertFailsWith<IllegalArgumentException> { executor.accept(request.copy(payload = byteArrayOf(3))) }
    }

    @Test
    fun `offline result remains pending until server acknowledgement`() = runTest {
        val journal = TestWorkerRequestJournal()
        val executor = WorkerRequestExecutor(journal, backgroundScope, WorkerRequestHandler { success(it.id) }, {})
        executor.initialize()
        executor.accept(request("offline"))
        runCurrent()
        assertEquals(listOf(success("offline")), executor.pendingResponses())
        executor.acknowledge("offline")
        assertTrue(executor.pendingResponses().isEmpty())
    }

    private fun request(id: String) = WorkerGatewayMessage.Request(
        id, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(), WorkerRequestDelivery(Clock.System.now() + 30.seconds, 10_000),
    )
    private fun success(id: String) = WorkerGatewayMessage.Response(id, WorkerGatewayMessage.Response.Status.SUCCEEDED, byteArrayOf(7))
}
