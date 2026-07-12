package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MemoryOperationQueueTest {
    @Test
    fun recoversDeduplicatesAndProcessesJobsSequentially() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = MemoryOperationQueue(scope)
            val startedJobs = Channel<MemoryOperationJob>(Channel.UNLIMITED)
            val releases = Channel<Unit>(Channel.UNLIMITED)
            val recoveredJob = job("recovered")
            val nextJob = job("next")
            queue.start(listOf(recoveredJob)) { job ->
                startedJobs.send(job)
                releases.receive()
            }

            assertEquals(recoveredJob, withTimeout(5_000) { startedJobs.receive() })
            assertEquals(0, queue.enqueue(recoveredJob))
            assertEquals(1, queue.enqueue(nextJob))
            assertEquals(recoveredJob.runId, queue.status().activeJob?.runId)

            releases.send(Unit)
            assertEquals(nextJob, withTimeout(5_000) { startedJobs.receive() })
            releases.send(Unit)
            queue.awaitQueue { it.totalCompletedJobs == 2L }

            val status = queue.status()
            assertEquals(0, status.pendingJobs)
            assertNull(status.activeJob)
            assertEquals(1, status.totalEnqueuedJobs)
            assertEquals(1, status.totalRecoveredJobs)
            assertEquals(2, status.totalStartedJobs)
            assertEquals(2, status.totalCompletedJobs)
            assertEquals(0, status.totalFatallyFailedJobs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun processorFailureDoesNotBlockFollowingJobs() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = MemoryOperationQueue(scope)
            val successfulJob = CompletableDeferred<MemoryOperationJob>()
            val failedJob = job("failed")
            val nextJob = job("next")
            queue.start(emptyList()) { job ->
                if (job == failedJob) error("expected test failure")
                successfulJob.complete(job)
            }

            queue.enqueue(failedJob)
            queue.enqueue(nextJob)

            assertEquals(nextJob, withTimeout(5_000) { successfulJob.await() })
            queue.awaitQueue { it.totalStartedJobs == 2L && it.activeJob == null }
            val status = queue.status()
            assertEquals(1, status.totalCompletedJobs)
            assertEquals(1, status.totalFatallyFailedJobs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun scopeCancellationStopsWorkerWithoutRecordingJobFailure() = runBlocking {
        val supervisorJob = SupervisorJob()
        val scope = CoroutineScope(supervisorJob + Dispatchers.Default)
        val queue = MemoryOperationQueue(scope)
        val startedJob = CompletableDeferred<MemoryOperationJob>()
        queue.start(emptyList()) { job ->
            startedJob.complete(job)
            awaitCancellation()
        }

        val cancelledJob = job("cancelled")
        queue.enqueue(cancelledJob)
        assertEquals(cancelledJob, withTimeout(5_000) { startedJob.await() })

        supervisorJob.cancelAndJoin()

        val status = queue.status()
        assertNull(status.activeJob)
        assertEquals(1, status.totalStartedJobs)
        assertEquals(0, status.totalCompletedJobs)
        assertEquals(0, status.totalFatallyFailedJobs)
    }

    private suspend fun MemoryOperationQueue.awaitQueue(
        predicate: (MemoryOperationQueueStatus) -> Boolean,
    ) {
        withTimeout(5_000) {
            while (!predicate(status())) delay(10)
        }
    }

    private fun job(suffix: String): MemoryOperationJob =
        MemoryOperationJob(
            runId = MemoryRun.Id("run:$suffix"),
            operation = MemoryOperationKind.REMEMBER,
            namespace = MemoryNamespace("queue-test"),
        )
}
