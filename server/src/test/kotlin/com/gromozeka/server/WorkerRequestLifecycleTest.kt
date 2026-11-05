package com.gromozeka.server

import com.gromozeka.domain.repository.WorkerRequestRepository
import com.gromozeka.domain.service.*
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class WorkerRequestLifecycleTest {
    private val worker = ConversationRuntimeWorkerId("worker")
    private val policy = WorkerRequestPolicy(deliveryTtlMillis = 300_000, executionTimeoutMillis = 1_000)
    private fun success(id: String) = WorkerGatewayMessage.Response(id, WorkerGatewayMessage.Response.Status.SUCCEEDED, byteArrayOf(1))
    private fun saved(repo: TestWorkerRequestRepository, id: String) =
        WorkerGatewayCodec.decode(requireNotNull(repo.records[id]?.response)) as WorkerGatewayMessage.Response

    @Test
    fun `dispatched timeout uses latest possible start not dispatch time and late success cannot replace it`() = runBlocking {
        val repo = TestWorkerRequestRepository()
        val projected = mutableListOf<WorkerGatewayMessage.Response>()
        val service = WorkerRequestService(repo, WorkerRequestAuthorization {}, listOf(WorkerRequestCompletionObserver { _, response -> projected += response }))
        val id = service.submit(worker, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(), policy)
        val original = requireNotNull(repo.find(id))
        repo.markDispatched(id, original.createdAt)
        service.maintain(original.createdAt + 1_001.milliseconds)
        assertNull(repo.find(id)?.response)
        val deadline = requireNotNull(original.executionDeadline)
        service.maintain(deadline - 1.milliseconds)
        assertNull(repo.find(id)?.response)
        service.maintain(deadline)
        assertEquals("OUTCOME_UNKNOWN", saved(repo, id).errorCode)
        assertTrue(service.accept(worker, success(id)))
        service.maintain(deadline)
        assertEquals(listOf(saved(repo, id)), projected)
        assertEquals("OUTCOME_UNKNOWN", service.await(id, 1).errorCode)
    }

    @Test
    fun `maintenance backfills exact old policy expires offline delivery and retains pending projection`() = runBlocking {
        val repo = TestWorkerRequestRepository()
        var fail = true
        val durableProjection = mutableSetOf<String>()
        fun service() = WorkerRequestService(repo, WorkerRequestAuthorization {}, listOf(WorkerRequestCompletionObserver { record, _ ->
            durableProjection += record.id
            if (fail) error("crash after idempotent publication")
        }))
        val first = service()
        val id = first.submit(worker, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(), policy)
        val original = requireNotNull(repo.find(id))
        repo.records[id] = original.copy(executionDeadline = null)
        first.maintain(original.createdAt)
        assertEquals(original.executionDeadline, repo.find(id)?.executionDeadline)
        first.maintain(original.startDeadline)
        assertEquals("EXPIRED", saved(repo, id).errorCode)
        assertNull(repo.find(id)?.completionProjectedAt)
        first.maintain(original.startDeadline + 2.hours)
        assertNotNull(repo.find(id))
        fail = false
        service().maintain(original.startDeadline + 2.hours)
        assertEquals(setOf(id), durableProjection)
        assertNull(repo.find(id))
        assertFalse(first.accept(worker, success(id)))
    }

    @Test
    fun `retention starts at completion and never deletes live requests`() = runBlocking {
        val repo = TestWorkerRequestRepository()
        val service = WorkerRequestService(repo, WorkerRequestAuthorization {})
        val done = service.submit(worker, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(), policy)
        val at = requireNotNull(repo.find(done)).createdAt
        repo.markDispatched(done, at)
        repo.complete(worker, done, WorkerGatewayCodec.encode(success(done)), at)
        val live = service.submit(worker, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(), WorkerRequestPolicy(executionTimeoutMillis = 86_400_000))
        repo.markDispatched(live, at)
        service.maintain(at + 1.hours)
        assertNotNull(repo.find(done))
        assertNotNull(repo.find(live))
        service.maintain(at + 1.hours + 1.milliseconds)
        assertNull(repo.find(done))
        assertNull(repo.find(live)?.response)
    }

    @Test
    fun `concurrent success and timeout project only the canonical terminal result`() = runBlocking {
        repeat(20) {
            val repo = TestWorkerRequestRepository()
            val projected = java.util.concurrent.CopyOnWriteArrayList<WorkerGatewayMessage.Response>()
            val service = WorkerRequestService(repo, WorkerRequestAuthorization {}, listOf(WorkerRequestCompletionObserver { _, response -> projected += response }))
            val id = service.submit(worker, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(), policy)
            val record = requireNotNull(repo.find(id))
            repo.markDispatched(id, record.createdAt)
            listOf(async(Dispatchers.Default) { service.accept(worker, success(id)) },
                async(Dispatchers.Default) { service.maintain(requireNotNull(record.executionDeadline)) }).awaitAll()
            service.maintain(requireNotNull(record.executionDeadline))
            assertEquals(listOf(saved(repo, id)), projected)
        }
    }

    @Test
    fun `GC between progress and result read reports unavailable without resubmission`() = runBlocking {
        val repo = TestWorkerRequestRepository()
        val racing = object : WorkerRequestRepository by repo {
            override suspend fun progress(id: String): WorkerRequestProgress? = repo.progress(id).also {
                if (it?.completedAt != null) repo.records.remove(id)
            }
        }
        val service = WorkerRequestService(racing, WorkerRequestAuthorization {})
        val id = service.submit(worker, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(), policy)
        repo.markDispatched(id, requireNotNull(repo.find(id)).createdAt)
        service.accept(worker, success(id))
        assertEquals(id, assertFailsWith<WorkerRequestResultUnavailableException> { service.await(id, 1) }.requestId)
        assertTrue(repo.records.isEmpty())
    }
}
