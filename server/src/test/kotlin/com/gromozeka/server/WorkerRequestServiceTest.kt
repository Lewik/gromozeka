package com.gromozeka.server

import com.gromozeka.domain.repository.WorkerRequestRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.PendingWorkerRequest
import com.gromozeka.domain.service.StoredWorkerRequest
import com.gromozeka.domain.service.WorkerRequestPolicy
import com.gromozeka.remote.protocol.WorkerGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

internal class TestWorkerRequestRepository : WorkerRequestRepository {
    val records = ConcurrentHashMap<String, StoredWorkerRequest>()
    override suspend fun create(request: StoredWorkerRequest) { check(records.putIfAbsent(request.id, request) == null) }
    override suspend fun find(id: String) = records[id]
    override suspend fun progress(id: String) = records[id]?.let {
        com.gromozeka.domain.service.WorkerRequestProgress(it.dispatchedAt, it.cancelRequestedAt, it.completedAt)
    }
    override suspend fun pending(workerId: ConversationRuntimeWorkerId, limit: Int) = records.values
        .filter { it.workerId == workerId && it.response == null }.take(limit).map { PendingWorkerRequest(it.id, it.cancelRequestedAt != null) }
    override suspend fun markDispatched(id: String, at: Instant): Boolean {
        var changed = false
        records.computeIfPresent(id) { _, record ->
            if (record.response != null) record else { changed = true; record.copy(dispatchedAt = record.dispatchedAt ?: at) }
        }
        return changed
    }
    override suspend fun cancel(id: String, at: Instant) {
        records.computeIfPresent(id) { _, record ->
            if (record.response != null) record else record.copy(cancelRequestedAt = record.cancelRequestedAt ?: at)
        }
    }
    override suspend fun complete(workerId: ConversationRuntimeWorkerId, id: String, response: ByteArray, at: Instant, onlyIfUndispatched: Boolean): Boolean {
        var changed = false
        records.computeIfPresent(id) { _, record ->
            if (record.workerId != workerId || record.response != null || onlyIfUndispatched && record.dispatchedAt != null) record
            else { changed = true; record.copy(response = response, completedAt = at) }
        }
        return changed
    }
}

class WorkerRequestServiceTest {
    private val workerId = ConversationRuntimeWorkerId("worker")
    private val policy = WorkerRequestPolicy(deliveryTtlMillis = 10_000, executionTimeoutMillis = 1_000)
    private fun service(repository: WorkerRequestRepository) = WorkerRequestService(repository, WorkerRequestAuthorization {})
    private fun session(process: String = "process") = WorkerGatewaySession(ConversationRuntimeWorkerIdentity(workerId, ConversationRuntimeWorkerSessionId(process)))
    private fun success(id: String) = WorkerGatewayMessage.Response(id, WorkerGatewayMessage.Response.Status.SUCCEEDED, byteArrayOf(7))

    @Test
    fun `offline submission survives server replacement and short wait does not cancel`() = runBlocking {
        val repository = TestWorkerRequestRepository()
        val requests = service(repository)
        val id = requests.submit(workerId, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(1), policy)
        assertNotNull(repository.find(id))
        assertEquals(id, assertFailsWith<WorkerRequestPendingException> { requests.await(id, 1) }.requestId)
        assertNull(repository.find(id)?.cancelRequestedAt)
        val recovered = service(repository)
        val session = session()
        val delivery = launch { recovered.deliver(session) }
        try {
            val request = withTimeout(1_000) { session.outgoingMessages().receive() } as WorkerGatewayMessage.Request
            assertEquals(id, request.id)
            assertNotNull(repository.find(id)?.dispatchedAt)
            recovered.accept(workerId, success(id))
            assertEquals(success(id), requests.await(id, 1_000))
            assertEquals(success(id), service(repository).await(id, 1_000))
        } finally { delivery.cancelAndJoin() }
    }

    @Test
    fun `reconnect with another process redelivers the same immutable request`() = runBlocking {
        val requests = service(TestWorkerRequestRepository())
        requests.submit(workerId, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(1), policy)
        val first = session("first-process")
        val firstDelivery = launch { requests.deliver(first) }
        val request = withTimeout(1_000) { first.outgoingMessages().receive() }
        firstDelivery.cancelAndJoin()
        val second = session("second-process")
        val secondDelivery = launch { requests.deliver(second) }
        try { assertEquals(request, withTimeout(1_000) { second.outgoingMessages().receive() }) }
        finally { secondDelivery.cancelAndJoin() }
    }

    @Test
    fun `undispatched expiration is terminal without contacting worker`() = runBlocking {
        val requests = service(TestWorkerRequestRepository())
        val id = requests.submit(workerId, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(), policy.copy(deliveryTtlMillis = 1))
        delay(10)
        assertEquals("EXPIRED", requests.await(id, 100).errorCode)
    }

    @Test
    fun `cancellation persists and is redelivered after initial dispatch`() = runBlocking {
        val requests = service(TestWorkerRequestRepository())
        val id = requests.submit(workerId, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(), policy)
        val session = session()
        val delivery = launch { requests.deliver(session) }
        try {
            withTimeout(1_000) { session.outgoingMessages().receive() }
            requests.cancel(id)
            val cancellation = withTimeout(1_000) { session.outgoingMessages().receive() } as WorkerGatewayMessage.Request
            assertEquals(id, cancellation.id)
            assertTrue(cancellation.delivery!!.cancelRequested)
        } finally { delivery.cancelAndJoin() }
    }

    @Test
    fun `queued cancellation never dispatches and another worker cannot write result`(): Unit = runBlocking {
        val requests = service(TestWorkerRequestRepository())
        val id = requests.submit(workerId, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(), policy)
        requests.cancel(id)
        assertEquals("CANCELLED", requests.await(id, 100).errorCode)
        assertFailsWith<IllegalArgumentException> { requests.accept(ConversationRuntimeWorkerId("other"), success(id)) }
    }

    @Test
    fun `execution wait timeout preserves request but caller cancellation cancels it`() = runBlocking {
        val repository = TestWorkerRequestRepository()
        val requests = service(repository)
        val id = assertFailsWith<WorkerRequestPendingException> {
            requests.execute(workerId, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(), policy.copy(waitTimeoutMillis = 1))
        }.requestId
        assertNull(repository.find(id)?.cancelRequestedAt)
        val caller = launch { requests.execute(workerId, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(), policy) }
        withTimeout(1_000) { while (repository.records.size != 2) delay(1) }
        caller.cancelAndJoin()
        val cancelled = repository.records.values.single { it.id != id }
        assertNotNull(cancelled.cancelRequestedAt)
        assertEquals("CANCELLED", requests.await(cancelled.id, 100).errorCode)
    }

    @Test
    fun `transient authorization failure does not cancel queued request`() = runBlocking {
        var unavailable = false
        val repository = TestWorkerRequestRepository()
        val requests = WorkerRequestService(repository, WorkerRequestAuthorization {
            if (unavailable) throw java.sql.SQLException("Database temporarily unavailable")
        })
        val id = requests.submit(workerId, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(), policy)
        unavailable = true
        assertFailsWith<java.sql.SQLException> { requests.deliver(session()) }
        assertNull(repository.find(id)?.cancelRequestedAt)
        assertNull(repository.find(id)?.dispatchedAt)
        assertNull(repository.find(id)?.response)
    }

    @Test
    fun `revoked permission before delivery prevents execution`() = runBlocking {
        var allowed = true
        val requests = WorkerRequestService(TestWorkerRequestRepository(), WorkerRequestAuthorization {
            if (!allowed) throw com.gromozeka.domain.service.WorkerAccessDeniedException()
        })
        val id = requests.submit(workerId, WorkerGatewayOperation.WORKER_CONTROL, byteArrayOf(), policy)
        allowed = false
        val session = session()
        val delivery = launch { requests.deliver(session) }
        try {
            assertEquals("CANCELLED", requests.await(id, 1_000).errorCode)
            assertTrue(session.outgoingMessages().tryReceive().isFailure)
        } finally { delivery.cancelAndJoin() }
    }
}
