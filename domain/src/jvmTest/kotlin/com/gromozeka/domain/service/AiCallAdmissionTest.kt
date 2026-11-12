package com.gromozeka.domain.service

import com.gromozeka.domain.model.ai.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class AiCallAdmissionTest {
    @Test
    fun `result is preserved and cleanup precedes lease completion`() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val admission = RecordingAdmission(events)
        val result = admission.execute(connection) {
            try { events += "body"; 42 } finally { events += "cleanup" }
        }
        assertEquals(42, result)
        assertEquals(listOf("acquire", "body", "cleanup", "finish:SUCCEEDED"), events.toList())
    }

    @Test
    fun `failure and cancellation complete exactly one lease`() = runBlocking {
        val admission = RecordingAdmission()
        val expected = object : IllegalArgumentException("synthetic failure") {}
        assertSame(expected, assertFailsWith<IllegalArgumentException> { admission.execute(connection) { throw expected } })
        val entered = CompletableDeferred<Unit>()
        val running = launch { admission.execute(connection) { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        running.cancelAndJoin()
        assertEquals(listOf(AiAdmissionOutcome.FAILED, AiAdmissionOutcome.CANCELLED), admission.outcomes.toList())
        assertEquals(2, admission.acquired.get())
    }

    @Test
    fun `nested execution is rejected rather than borrowing or waiting on the parent permit`() = runBlocking {
        val admission = RecordingAdmission()
        assertFailsWith<IllegalStateException> {
            admission.execute(connection) { admission.execute(connection) { error("Must not execute") } }
        }
        assertEquals(1, admission.acquired.get())
        assertEquals(listOf(AiAdmissionOutcome.FAILED), admission.outcomes.toList())
    }

    @Test
    fun `managed session stays admitted until explicit transport completion and completes once`() = runBlocking {
        val admission = RecordingAdmission()
        val session = admission.openSession(connection, "SPEECH_TO_TEXT", "capture")
        assertTrue(admission.outcomes.isEmpty())
        listOf(async { session.finish() }, async { session.finish() }).awaitAll()
        assertEquals(1, admission.acquired.get())
        assertEquals(listOf(AiAdmissionOutcome.SUCCEEDED), admission.outcomes.toList())
    }

    @Test
    fun `completion failure is visible and a managed session can retry its idempotent completion`() = runBlocking {
        val attempts = AtomicInteger()
        val admission = object : RecordingAdmission() {
            override suspend fun finish(lease: AiAdmissionLease, outcome: AiAdmissionOutcome) {
                if (attempts.incrementAndGet() == 1) error("Synthetic completion transport failure")
                super.finish(lease, outcome)
            }
        }
        val session = admission.openSession(connection, "SPEECH_TO_TEXT")
        assertFailsWith<AiAdmissionException> { session.finish() }
        session.finish()
        session.finish()
        assertEquals(2, attempts.get())
        assertEquals(1, admission.outcomes.size)
    }

    @Test
    fun `an expired deadline never acquires or executes`() = runBlocking {
        val admission = RecordingAdmission()
        val error = assertFailsWith<AiCallException> {
            admission.execute(connection, deadline = Clock.System.now() - 1.seconds) { error("Must not execute") }
        }
        assertEquals(AiCallFailure.Outcome.NOT_STARTED, error.failure.outcome)
        assertEquals(0, admission.acquired.get())
    }

    private open class RecordingAdmission(private val events: MutableList<String> = CopyOnWriteArrayList()) : AiCallAdmission() {
        val acquired = AtomicInteger()
        val outcomes = CopyOnWriteArrayList<AiAdmissionOutcome>()
        override suspend fun acquire(operation: AiAdmissionOperation): AiAdmissionLease {
            acquired.incrementAndGet()
            events += "acquire"
            return AiAdmissionLease(operation, AiConcurrencyGroup.DEFAULT_ID, Clock.System.now())
        }
        override suspend fun finish(lease: AiAdmissionLease, outcome: AiAdmissionOutcome) {
            events += "finish:$outcome"
            outcomes += outcome
        }
    }

    private companion object {
        val connection = AiConnection.OpenAiApi(AiConnection.Id("test"), "Test")
    }
}
