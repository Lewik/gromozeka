package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import kotlin.time.Clock

class AiAdmissionQueueTest {
    @Test
    fun `model configurations share a group across workers and an eligible later group bypasses the head`() = test { queue, _ ->
        val first = queue.acquire(operation("first", "a"), "worker-a", "root-a")
        val blocked = async { queue.acquire(operation("second", "b"), "worker-b", "root-b") }
        queue.snapshot.first { it.waiting.size == 1 }
        val other = queue.acquire(operation("other", "c"), "worker-c", "root-c")
        assertEquals(OTHER, other.groupId)
        assertFalse(blocked.isCompleted)
        queue.release(first.operation.id)
        assertEquals(AiConcurrencyGroup.DEFAULT_ID, blocked.await().groupId)
        queue.release("second")
        queue.release("other")
    }

    @Test
    fun `cancelled waiter never starts and does not strand capacity`() = test { queue, _ ->
        queue.acquire(operation("first", "a"))
        val cancelled = async { queue.acquire(operation("cancelled", "a")) }
        queue.snapshot.first { it.waiting.isNotEmpty() }
        cancelled.cancelAndJoin()
        queue.snapshot.first { it.waiting.isEmpty() }
        queue.release("first")
        val next = queue.acquire(operation("next", "a"))
        assertEquals("next", next.operation.id)
        queue.release("next")
    }

    @Test
    fun `cancellation after grant but before consumer resumes returns capacity without starting work`() = test { queue, _ ->
        val continuations = ArrayDeque<Runnable>()
        val pausedConsumer = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                continuations.addLast(block)
            }
        }
        var started = false
        val consumer = async(pausedConsumer, start = CoroutineStart.UNDISPATCHED) {
            queue.acquire(operation("racing", "a"))
            started = true
        }
        queue.snapshot.first { it.active.any { lease -> lease.operation.id == "racing" } }
        assertTrue(continuations.isNotEmpty())
        consumer.cancel()
        while (continuations.isNotEmpty()) continuations.removeFirst().run()
        consumer.join()
        queue.snapshot.first { it.waiting.isEmpty() && it.active.isEmpty() }
        assertFalse(started)
        queue.acquire(operation("after-race", "a"))
        queue.release("after-race")
    }

    @Test
    fun `a cancelled duplicate cannot release another callers grant`() = test { queue, _ ->
        queue.acquire(operation("same", "a"))
        val duplicate = async(start = CoroutineStart.UNDISPATCHED) { queue.acquire(operation("same", "a")) }
        duplicate.cancelAndJoin()
        queue.updateCatalog(catalog(), 1)
        assertEquals(listOf("same"), queue.snapshot.first { it.waiting.isEmpty() }.active.map { it.operation.id })
        queue.release("same")
    }

    @Test
    fun `lowering a limit drains existing work while raising it wakes waiting work`() = test(limit = 2) { queue, initial ->
        queue.acquire(operation("a1", "a"))
        queue.acquire(operation("a2", "b"))
        val reduced = initial.copy(concurrencyGroups = initial.concurrencyGroups.map {
            if (it.id == AiConcurrencyGroup.DEFAULT_ID) it.copy(maxConcurrentCalls = 1) else it
        })
        queue.updateCatalog(reduced, 1)
        val waiting = async { queue.acquire(operation("a3", "a")) }
        queue.snapshot.first { it.waiting.isNotEmpty() }
        queue.release("a1")
        assertFalse(waiting.isCompleted)
        queue.updateCatalog(initial, 2)
        assertEquals("a3", waiting.await().operation.id)
        queue.release("a2")
        queue.release("a3")
    }

    @Test
    fun `queued operation follows an explicit model configuration reassignment without moving an active grant`() = test { queue, initial ->
        queue.acquire(operation("first", "a"))
        val waiting = async { queue.acquire(operation("second", "b")) }
        queue.snapshot.first { it.waiting.isNotEmpty() }
        queue.updateCatalog(initial.copy(modelConfigurations = initial.modelConfigurations.map {
            if (it.id.value == "model-b") it.copy(concurrencyGroupId = OTHER) else it
        }), 1)
        assertEquals(OTHER, waiting.await().groupId)
        assertEquals(AiConcurrencyGroup.DEFAULT_ID, queue.snapshot.value.active.single { it.operation.id == "first" }.groupId)
        queue.release("first")
        queue.release("second")
    }

    @Test
    fun `removed connection fails its queued operation rather than using default`() = test { queue, initial ->
        queue.acquire(operation("first", "a"))
        val waiting = async { queue.acquire(operation("second", "b")) }
        queue.snapshot.first { it.waiting.isNotEmpty() }
        queue.updateCatalog(initial.copy(connections = initial.connections.filterNot { it.id.value == "b" },
            modelConfigurations = initial.modelConfigurations.filterNot { it.id.value == "model-b" }), 1)
        assertFailsWith<IllegalArgumentException> { waiting.await() }
        queue.release("first")
    }

    @Test
    fun `restored remote leases retain capacity and successful start does not finish background capture`() = runBlocking {
        withTimeout(10_000) {
            supervisorScope {
                val lease = AiAdmissionLease(operation("capture", "a").copy(backgroundSessionId = "capture-session"),
                    AiConcurrencyGroup.DEFAULT_ID, Clock.System.now(), "worker", "start-request", accepted = true)
                val queue = AiAdmissionQueue(this, catalog(), listOf(lease))
                try {
                    val waiting = async { queue.acquire(operation("next", "b")) }
                    queue.snapshot.first { it.waiting.isNotEmpty() }
                    queue.finishWorkerRequest("worker", "start-request")
                    assertFalse(waiting.isCompleted)
                    queue.release("capture")
                    assertEquals("next", waiting.await().operation.id)
                    queue.release("next")
                } finally { queue.close() }
            }
        }
    }

    @Test
    fun `late catalog notifications cannot roll back a newer concurrency limit`() = test { queue, initial ->
        queue.acquire(operation("first", "a"))
        val waiting = async { queue.acquire(operation("second", "a")) }
        queue.snapshot.first { it.waiting.isNotEmpty() }
        queue.updateCatalog(initial, 2)
        queue.updateCatalog(catalog(2), 1)
        assertFalse(waiting.isCompleted)
        queue.release("first")
        waiting.await()
        queue.release("second")
    }

    @Test
    fun `TPM FIFO prevents small requests overtaking while independent groups proceed`() = runBlocking {
        withTimeout(5_000) {
            supervisorScope {
                val quota = AiTpmGroup(AiTpmGroup.Id("quota"), "quota", "Quota", 100, allowParallelRequests = true)
                val other = quota.copy(id = AiTpmGroup.Id("independent"), key = "independent")
                val catalog = catalog(10).copy(tpmGroups = listOf(quota, other))
                var clock = 0L
                val queue = AiAdmissionQueue(this, catalog, nanoTime = { clock })
                fun op(id: String, units: Long, group: AiTpmGroup = quota) = operation(id, "a").copy(tpm =
                    AiTpmReservation(group.id, units, AiTpmFeedbackPolicy.Fields(AiQuotaValueSource.Header("quota"))))
                try {
                    queue.acquire(op("spent", 80))
                    queue.release("spent")
                    val large = async { queue.acquire(op("large", 70)) }
                    queue.snapshot.first { it.waiting.size == 1 }
                    val small = async { queue.acquire(op("small", 10)) }
                    queue.snapshot.first { it.waiting.size == 2 }
                    val independent = queue.acquire(op("independent", 10, other))
                    assertFalse(large.isCompleted); assertFalse(small.isCompleted)
                    queue.release(independent.operation.id)
                    clock = 30_000_000_000
                    queue.updateCatalog(catalog, 1)
                    assertEquals("large", large.await().operation.id)
                    assertFalse(small.isCompleted)
                    clock = 36_000_000_000
                    queue.updateCatalog(catalog, 2)
                    assertEquals("small", small.await().operation.id)
                    queue.release("large"); queue.release("small")
                } finally { queue.close() }
            }
        }
    }

    @Test
    fun `closing coordinator fails quota waiters rather than leaving them suspended`() = runBlocking<Unit> {
        withTimeout(5_000) {
            supervisorScope {
                val quota = AiTpmGroup(AiTpmGroup.Id("quota"), "quota", "Quota", 100)
                val queue = AiAdmissionQueue(this, catalog(10).copy(tpmGroups = listOf(quota)))
                val reservation = AiTpmReservation(quota.id, 100, AiTpmFeedbackPolicy.Fields(AiQuotaValueSource.Header("quota")))
                queue.acquire(operation("spent", "a").copy(tpm = reservation))
                val waiting = async { queue.acquire(operation("waiting", "a").copy(tpm = reservation)) }
                queue.snapshot.first { it.waiting.isNotEmpty() }
                queue.close()
                assertFailsWith<IllegalStateException> { waiting.await() }
                assertFailsWith<IllegalStateException> { queue.acquire(operation("late", "a")) }
            }
        }
    }

    @Test
    fun `one scheduled recheck refills elapsed time without an external event`() = runBlocking {
        withTimeout(15_000) {
            val quota = AiTpmGroup(AiTpmGroup.Id("quota"), "quota", "Quota", 100)
            var clock = 0L
            val queue = AiAdmissionQueue(this, catalog(10).copy(tpmGroups = listOf(quota)), nanoTime = { clock })
            val reservation = AiTpmReservation(quota.id, 100, AiTpmFeedbackPolicy.Fields(AiQuotaValueSource.Header("quota")))
            try {
                queue.acquire(operation("spent", "a").copy(tpm = reservation)); queue.release("spent")
                val waiting = async { queue.acquire(operation("waiting", "a").copy(tpm = reservation)) }
                queue.snapshot.first { it.waiting.isNotEmpty() }
                clock = 90_000_000_000
                assertEquals("waiting", waiting.await().operation.id)
                assertEquals(0.0, queue.snapshot.value.tpmGroups.single().localRemaining)
                queue.release("waiting")
            } finally { queue.close() }
        }
    }

    @Test
    fun `restored TPM reservation without its durable balance fails before actor startup`() = runBlocking<Unit> {
        val group = AiTpmGroup(AiTpmGroup.Id("quota"), "quota", "Quota", 100)
        val reservation = AiTpmReservation(group.id, 10, AiTpmFeedbackPolicy.Fields(AiQuotaValueSource.Header("quota")))
        val lease = AiAdmissionLease(operation("restored", "a").copy(tpm = reservation), AiConcurrencyGroup.DEFAULT_ID,
            Clock.System.now(), "worker", "root")
        assertFailsWith<IllegalArgumentException> {
            AiAdmissionQueue(this, catalog().copy(tpmGroups = listOf(group)), initialLeases = listOf(lease))
        }
    }

    private fun test(limit: Int = 1, block: suspend CoroutineScope.(AiAdmissionQueue, AiCatalog) -> Unit) = runBlocking {
        withTimeout(10_000) {
            supervisorScope {
                val catalog = catalog(limit)
                val queue = AiAdmissionQueue(this, catalog)
                try { block(queue, catalog) } finally { queue.close() }
            }
        }
    }

    private fun operation(id: String, connection: String) =
        AiAdmissionOperation(id, AiAdmissionTarget.ModelConfiguration(AiModelConfiguration.Id("model-$connection"), AiConnection.Id(connection)), "TEST")

    private fun catalog(limit: Int = 1): AiCatalog {
        val connections = listOf("a", "b", "c").map { id -> AiConnection.OpenAiApi(
            AiConnection.Id(id), id, enabled = true,
        ) }
        val models = connections.map { connection -> AiModelConfiguration(AiModelConfiguration.Id("model-${connection.id.value}"), connection.id, "model", "Model",
            concurrencyGroupId = if (connection.id.value == "c") OTHER else AiConcurrencyGroup.DEFAULT_ID) }
        val model = models.first()
        val spec = AiModelSpec("model", AiProvider.OPENAI, AiModelCapability.entries.toSet(),
            AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(32_000), AiModelSpec.Limits.Embeddings(dimensions = 2)))
        return AiCatalog(connections, listOf(spec), models,
            AiRuntimeAssignment.Purpose.entries.filter { it.requiresExplicitAssignment }.map {
                AiRuntimeAssignment(it, AiRuntimeSelection(model.id))
            }, AgentDefinition.Id("test-agent"),
            concurrencyGroups = listOf(AiConcurrencyGroup.DEFAULT.copy(maxConcurrentCalls = limit), AiConcurrencyGroup(OTHER, "Other", 1)))
    }

    private companion object { val OTHER = AiConcurrencyGroup.Id("other") }
}
