package com.gromozeka.statesync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateSyncTest {
    @Test
    fun concurrentSubscribersShareOneLoad() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        var loadCount = 0
        val source = StateSyncSource<String, String>(backgroundScope, "source") { key ->
            loadCount += 1
            loadGate.await()
            "$key-$loadCount"
        }
        val firstSubscription = source.subscribe("project")
        val secondSubscription = source.subscribe("project")

        val first = async { firstSubscription.snapshot() }
        val second = async { secondSubscription.snapshot() }
        runCurrent()

        assertEquals(1, loadCount)
        loadGate.complete(Unit)
        assertEquals("project-1", first.await().value)
        assertEquals("project-1", second.await().value)
    }

    @Test
    fun invalidationDuringLoadReloadsBeforeReturning() = runTest {
        val loadGates = mutableListOf<CompletableDeferred<Unit>>()
        var loadCount = 0
        val source = StateSyncSource<String, Int>(backgroundScope, "source") {
            val gate = CompletableDeferred<Unit>().also(loadGates::add)
            loadCount += 1
            gate.await()
            loadCount
        }
        val subscription = source.subscribe("runtime")
        val snapshot = async { subscription.snapshot() }
        runCurrent()

        source.invalidate("runtime")
        loadGates.single().complete(Unit)
        runCurrent()

        assertEquals(2, loadCount)
        loadGates.last().complete(Unit)
        assertEquals(2, snapshot.await().value)
        assertEquals(1, snapshot.await().cursor.generation)
    }

    @Test
    fun invalidationsAreConflatedForSlowConsumers() = runTest {
        val source = StateSyncSource<String, Int>(backgroundScope, "source") { 1 }
        val subscription = source.subscribe("runtime")

        source.invalidate("runtime")
        source.invalidate("runtime")
        source.invalidate("runtime")

        assertEquals(3, subscription.invalidations.first().cursor.generation)
    }

    @Test
    fun reconnectingSubscriptionGetsANewerStream() = runTest {
        val source = StateSyncSource<String, Int>(backgroundScope, "source") { 1 }
        val first = source.subscribe("runtime")
        val firstCursor = first.invalidations.first().cursor
        first.close()

        val second = source.subscribe("runtime")
        val secondCursor = second.invalidations.first().cursor

        assertTrue(secondCursor.streamEpoch > firstCursor.streamEpoch)
    }

    @Test
    fun cancelledSnapshotDoesNotRetainAnUnusedEntry() = runTest {
        val loadGate = CompletableDeferred<Unit>()
        val source = StateSyncSource<String, Int>(backgroundScope, "source") {
            loadGate.await()
            1
        }
        val first = source.subscribe("runtime")
        val firstCursor = first.invalidations.first().cursor
        val snapshot = async { first.snapshot() }
        runCurrent()

        snapshot.cancelAndJoin()
        first.close()
        loadGate.complete(Unit)
        runCurrent()

        val second = source.subscribe("runtime")
        val secondCursor = second.invalidations.first().cursor
        assertTrue(secondCursor.streamEpoch > firstCursor.streamEpoch)
    }

    @Test
    fun replicaRejectsOlderSnapshotsAndAcceptsANewerStream() {
        val replica = StateSyncReplica<String, String>("runtime")

        assertTrue(replica.apply(snapshot(stream = 1, generation = 2, value = "new")))
        assertFalse(replica.apply(snapshot(stream = 1, generation = 1, value = "old")))
        assertTrue(replica.apply(snapshot(stream = 2, generation = 0, value = "reset")))
        assertEquals("reset", replica.snapshot?.value)
    }

    @Test
    fun replicaDoesNotReturnToARetiredSource() {
        val replica = StateSyncReplica<String, String>("runtime")

        assertTrue(replica.apply(snapshot(source = "server-a", stream = 1, generation = 2, value = "a")))
        assertTrue(replica.apply(snapshot(source = "server-b", stream = 0, generation = 0, value = "b")))
        assertFalse(replica.apply(snapshot(source = "server-a", stream = 2, generation = 0, value = "late-a")))
        assertEquals("b", replica.snapshot?.value)
    }

    @Test
    fun observerRejectsSnapshotOlderThanInvalidation() = runTest {
        val invalidation = StateSyncInvalidation(
            key = "runtime",
            cursor = StateSyncCursor("source", streamEpoch = 1, generation = 2),
        )

        val failure = runCatching {
            observeStateSync(
                key = "runtime",
                invalidations = flowOf(invalidation),
                pull = { snapshot(stream = 1, generation = 1, value = "stale") },
            ).first()
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun snapshot(
        source: String = "source",
        stream: Long,
        generation: Long,
        value: String,
    ) = StateSyncSnapshot(
        key = "runtime",
        cursor = StateSyncCursor(source, stream, generation),
        value = value,
    )
}
