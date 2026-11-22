package com.gromozeka.shared.concurrency

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicConcurrencyGateTest {
    @Test
    fun `limit one serializes callers`() = runBlocking {
        val gate = DynamicConcurrencyGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch {
            gate.withPermit(1) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            gate.withPermit(1) { secondEntered.complete(Unit) }
        }
        yield()
        assertFalse(secondEntered.isCompleted)

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun `cancelled caller releases its permit`() = runBlocking {
        val gate = DynamicConcurrencyGate()
        val entered = CompletableDeferred<Unit>()

        val cancelled = launch {
            gate.withPermit(1) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        cancelled.cancelAndJoin()

        var nextEntered = false
        gate.withPermit(1) { nextEntered = true }
        assertTrue(nextEntered)
    }

    @Test
    fun `higher limit admits callers concurrently`() = runBlocking {
        val gate = DynamicConcurrencyGate()
        val release = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch {
            gate.withPermit(2) {
                firstEntered.complete(Unit)
                release.await()
            }
        }
        val second = launch {
            gate.withPermit(2) {
                secondEntered.complete(Unit)
                release.await()
            }
        }
        firstEntered.await()
        secondEntered.await()

        assertTrue(firstEntered.isCompleted)
        assertTrue(secondEntered.isCompleted)
        release.complete(Unit)
        first.join()
        second.join()
    }
}
