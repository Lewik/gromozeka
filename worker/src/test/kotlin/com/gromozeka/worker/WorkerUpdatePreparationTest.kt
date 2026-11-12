package com.gromozeka.worker

import com.gromozeka.domain.service.WorkerUpdateParticipant
import com.gromozeka.worker.runtime.WorkerUpdateGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WorkerUpdatePreparationTest {
    @Test fun `busy managed work reopens admission and successful preparation closes all participants`(): Unit = runBlocking {
        val requests = WorkerUpdateGate()
        val first = Participant()
        val second = Participant().apply { busy = true }
        val preparation = WorkerUpdatePreparation(requests, listOf(first, second))
        assertFalse(preparation.prepare())
        assertFalse(first.prepared)
        assertTrue(requests.enter())
        requests.leave()
        second.busy = false
        assertTrue(preparation.prepare())
        assertTrue(first.prepared && second.prepared)
        assertFalse(requests.enter())
        preparation.resume()
        assertFalse(first.prepared || second.prepared)
        assertTrue(requests.enter())
        requests.leave()
    }

    @Test fun `cancelled preparation releases participants and request admission`(): Unit = runBlocking {
        val requests = WorkerUpdateGate()
        val first = Participant()
        val second = Participant().apply { failure = CancellationException("cancel") }
        val preparation = WorkerUpdatePreparation(requests, listOf(first, second))
        assertFailsWith<CancellationException> { preparation.prepare() }
        assertFalse(first.prepared)
        assertTrue(requests.enter())
        requests.leave()
    }

    private class Participant : WorkerUpdateParticipant {
        var busy = false
        var prepared = false
        var failure: Throwable? = null
        var resumeFailure: Throwable? = null
        override suspend fun prepareForUpdate(): Boolean {
            failure?.let { throw it }
            if (busy) return false
            prepared = true
            return true
        }
        override suspend fun resumeAfterUpdate() { prepared = false; resumeFailure?.let { throw it } }
    }

    @Test fun `failed cleanup still releases remaining participants and admission`(): Unit = runBlocking {
        val requests = WorkerUpdateGate()
        val first = Participant()
        val second = Participant().apply { resumeFailure = IllegalStateException("cleanup failed") }
        val preparation = WorkerUpdatePreparation(requests, listOf(first, second))
        assertTrue(preparation.prepare())
        assertFailsWith<IllegalStateException> { preparation.resume() }
        assertFalse(first.prepared || second.prepared)
        assertTrue(requests.enter())
        requests.leave()
    }
}
