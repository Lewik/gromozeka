package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.*
import kotlin.test.*
import kotlin.time.Instant

class AiTpmLedgerTest {
    @Test fun `newly configured group works with a negative monotonic clock origin`() {
        val group = AiTpmGroup(AiTpmGroup.Id("negative-clock"), "negative-clock", "Quota", 1000)
        val ledger = AiTpmLedger(emptyList(), emptyList(), nanoTime = { -900_000_000_000L })
        ledger.update(listOf(group))
        assertTrue(ledger.eligible(AiTpmReservation(group.id, 1,
            AiTpmFeedbackPolicy.Fields(AiQuotaValueSource.Header("quota"))), emptyList()))
    }

    private val id = AiTpmGroup.Id("quota")
    private val feedback = AiTpmFeedbackPolicy.Fields(AiQuotaValueSource.Header("custom-remaining"))
    private fun group(parallel: Boolean = true, fraction: Double = 1.0) = AiTpmGroup(id, "account/model", "Quota", 1000, 100, parallel, fraction)
    private fun reservation(units: Long) = AiTpmReservation(id, units, feedback)
    private fun lease(name: String, reservation: AiTpmReservation) = AiAdmissionLease(
        AiAdmissionOperation(name, AiAdmissionTarget.LocalInference, "TEST", tpm = reservation),
        AiConcurrencyGroup.DEFAULT_ID, Instant.fromEpochMilliseconds(0))

    @Test fun `parallel observations never credit possibly unreflected calls and last completion remains concurrent`() {
        val ledger = AiTpmLedger(listOf(group()), emptyList(), { 0L })
        val a = ledger.reserve(reservation(200), emptyList())
        val b = ledger.reserve(reservation(100), listOf(lease("a", a)))
        ledger.observe(a, AiTpmFeedback(600.0))
        assertEquals(500.0, ledger.snapshot().single().observedRemaining)
        ledger.observe(b, AiTpmFeedback(900.0))
        assertEquals(500.0, ledger.snapshot().single().observedRemaining)
        val isolated = ledger.reserve(reservation(100), emptyList())
        ledger.observe(isolated, AiTpmFeedback(800.0))
        assertEquals(800.0, ledger.snapshot().single().observedRemaining)
        assertEquals(600.0, ledger.snapshot().single().localRemaining)
    }

    @Test fun `debt is retained and a delayed wake accounts elapsed time once`() {
        var now = 0L
        val ledger = AiTpmLedger(listOf(group()), emptyList(), { now })
        val a = ledger.reserve(reservation(100), emptyList())
        ledger.observe(a, AiTpmFeedback(-200.0))
        now = 35_000_000_000L
        assertEquals(150.0, ledger.snapshot().single().observedRemaining)
        assertEquals(150.0, ledger.snapshot().single().observedRemaining)
        assertTrue(ledger.eligible(reservation(150), emptyList()))
        assertFalse(ledger.eligible(reservation(151), emptyList()))
    }

    @Test fun `serial group waits for completion independently of available tokens`() {
        val ledger = AiTpmLedger(listOf(group(parallel = false)), emptyList(), { 0L })
        val a = ledger.reserve(reservation(10), emptyList())
        assertFalse(ledger.eligible(reservation(10), listOf(lease("a", a))))
        assertTrue(ledger.eligible(reservation(10), emptyList()))
    }

    @Test fun `usage fraction limits local refill and provider feedback cannot undo it`() {
        var now = 0L
        val ledger = AiTpmLedger(listOf(group(fraction = 0.5)), emptyList(), { now })
        val a = ledger.reserve(reservation(500), emptyList())
        ledger.observe(a, AiTpmFeedback(1000.0))
        assertFalse(ledger.eligible(reservation(1), emptyList()))
        now = 10_000_000_000L
        assertEquals(50.0, ledger.snapshot().single().localRemaining)
        assertFailsWith<IllegalArgumentException> { ledger.requireFits(reservation(501)) }
    }

    @Test fun `restart preserves debt and does not invent offline refill`() {
        val saved = AiTpmState(id, 100.0, -500.0, 500)
        val ledger = AiTpmLedger(listOf(group()), listOf(saved), { 9_000_000_000L })
        assertEquals(-500.0, ledger.snapshot().single().observedRemaining)
        assertFalse(ledger.eligible(reservation(1), emptyList()))
    }

    @Test fun `retry cooldown survives otherwise sufficient capacity`() {
        var now = 0L
        val ledger = AiTpmLedger(listOf(group()), emptyList(), { now }, { Instant.fromEpochMilliseconds(0) })
        val a = ledger.reserve(reservation(1), emptyList())
        ledger.observe(a, AiTpmFeedback(999.0, 2000))
        assertFalse(ledger.eligible(reservation(1), emptyList()))
        now = 2_000_000_000L
        assertTrue(ledger.eligible(reservation(1), emptyList()))
    }
}
