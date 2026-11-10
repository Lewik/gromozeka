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
    fun `connections share a group across workers and an eligible later group bypasses the head`() = test { queue, _ ->
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
    fun `queued operation follows an explicit connection reassignment without moving an active grant`() = test { queue, initial ->
        queue.acquire(operation("first", "a"))
        val waiting = async { queue.acquire(operation("second", "b")) }
        queue.snapshot.first { it.waiting.isNotEmpty() }
        queue.updateCatalog(initial.copy(connections = initial.connections.map {
            if (it.id.value == "b") it.withConcurrencyGroupId(OTHER) else it
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
        queue.updateCatalog(initial.copy(connections = initial.connections.filterNot { it.id.value == "b" }), 1)
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
        AiAdmissionOperation(id, AiAdmissionTarget.Connection(AiConnection.Id(connection)), "TEST")

    private fun catalog(limit: Int = 1): AiCatalog {
        val connections = listOf("a", "b", "c").map { id -> AiConnection.OpenAiApi(
            AiConnection.Id(id), id, enabled = true,
            concurrencyGroupId = if (id == "c") OTHER else AiConcurrencyGroup.DEFAULT_ID,
        ) }
        val model = AiModelConfiguration(AiModelConfiguration.Id("model"), connections.first().id, "model", "Model")
        val spec = AiModelSpec("model", AiProvider.OPENAI, AiModelCapability.entries.toSet(),
            AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(32_000), AiModelSpec.Limits.Embeddings(dimensions = 2)))
        return AiCatalog(connections, listOf(spec), listOf(model),
            AiRuntimeAssignment.Purpose.entries.filter { it.requiresExplicitAssignment }.map {
                AiRuntimeAssignment(it, AiRuntimeSelection(model.id))
            }, AgentDefinition.Id("test-agent"),
            concurrencyGroups = listOf(AiConcurrencyGroup.DEFAULT.copy(maxConcurrentCalls = limit), AiConcurrencyGroup(OTHER, "Other", 1)))
    }

    private companion object { val OTHER = AiConcurrencyGroup.Id("other") }
}
