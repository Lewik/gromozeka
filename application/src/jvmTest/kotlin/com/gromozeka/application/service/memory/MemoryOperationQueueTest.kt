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
            val scans = Channel<List<MemoryOperationJob>>(Channel.UNLIMITED)
            val recoveredJob = job("recovered")
            val nextJob = job("next")
            queue.start(jobSource = { scans.receive() }) { job ->
                startedJobs.send(job)
                releases.receive()
                completedJobs.send(job)
            }
            scans.send(listOf(recoveredJob))

            assertEquals(recoveredJob, withTimeout(5_000) { startedJobs.receive() })
            scans.send(listOf(nextJob))

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
            val scans = Channel<List<MemoryOperationJob>>(Channel.UNLIMITED)
            queue.start(jobSource = { scans.receive() }) { job ->
                if (job == failedJob) error("expected test failure")
                successfulJob.complete(job)
            }

            scans.send(listOf(failedJob, nextJob))

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
        val scans = Channel<List<MemoryOperationJob>>(Channel.UNLIMITED)
        queue.start(jobSource = { scans.receive() }) { job ->
            try {
                startedJob.complete(job)
                awaitCancellation()
            } finally {
                processorStopped.complete(Unit)
            }
        }

        val cancelledJob = job("cancelled")
        scans.send(listOf(cancelledJob))
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
