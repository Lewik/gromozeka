package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.model.memory.MemoryRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MemoryOperationQueueTest {
    @Test
    fun discoversAndProcessesJobsSequentially() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = MemoryOperationQueue(scope)
            val startedJobs = Channel<MemoryOperationJob>(Channel.UNLIMITED)
            val completedJobs = Channel<MemoryOperationJob>(Channel.UNLIMITED)
            val releases = Channel<Unit>(Channel.UNLIMITED)
            val recoveredJob = job("recovered")
            val nextJob = job("next")
            queue.start(jobSource = { listOf(recoveredJob) }) { job ->
                startedJobs.send(job)
                releases.receive()
                completedJobs.send(job)
            }

            assertEquals(recoveredJob, withTimeout(5_000) { startedJobs.receive() })
            queue.enqueue(nextJob)

            releases.send(Unit)
            assertEquals(nextJob, withTimeout(5_000) { startedJobs.receive() })
            assertEquals(recoveredJob, withTimeout(5_000) { completedJobs.receive() })
            releases.send(Unit)
            assertEquals(nextJob, withTimeout(5_000) { completedJobs.receive() })
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
            queue.start(jobSource = { listOf(failedJob, nextJob) }) { job ->
                if (job == failedJob) error("expected test failure")
                successfulJob.complete(job)
            }

            assertEquals(nextJob, withTimeout(5_000) { successfulJob.await() })
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
        val processorStopped = CompletableDeferred<Unit>()
        val cancelledJob = job("cancelled")
        queue.start(jobSource = { listOf(cancelledJob) }) { job ->
            try {
                startedJob.complete(job)
                awaitCancellation()
            } finally {
                processorStopped.complete(Unit)
            }
        }

        assertEquals(cancelledJob, withTimeout(5_000) { startedJob.await() })

        supervisorJob.cancelAndJoin()
        withTimeout(5_000) { processorStopped.await() }
    }

    private fun job(suffix: String): MemoryOperationJob =
        MemoryOperationJob(
            runId = MemoryRun.Id("run:$suffix"),
            operation = MemoryOperationKind.REMEMBER,
            namespace = MemoryNamespace("queue-test"),
        )
}
