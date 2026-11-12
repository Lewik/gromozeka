package com.gromozeka.application.service

import com.gromozeka.domain.model.ai.*
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Owned exclusively by the admission actor. No IO and no provider-specific header names. */
internal class AiTpmLedger(
    groups: List<AiTpmGroup>,
    restored: List<AiTpmState>,
    private val nanoTime: () -> Long = System::nanoTime,
    private val wallTime: () -> Instant = { Clock.System.now() },
) {
    private data class Balance(var group: AiTpmGroup, var local: Double, var remote: Double,
        var total: Long, var measured: Long, var cooldown: Long)
    private val balances = linkedMapOf<AiTpmGroup.Id, Balance>()

    init {
        require(restored.map { it.groupId }.distinct().size == restored.size) { "Duplicate TPM state" }
        groups.forEach { group ->
            val saved = restored.find { it.groupId == group.id }
            val cooldown = saved?.cooldownUntil?.let { (it - wallTime()).inWholeMilliseconds.coerceAtLeast(0) } ?: 0
            balances[group.id] = Balance(group, saved?.localRemaining ?: capacity(group),
                saved?.observedRemaining ?: group.tokenLimit.toDouble(), saved?.cumulativeAdmitted ?: 0,
                nanoTime(), nanoTime() + cooldown.coerceAtMost(86_400_000) * 1_000_000)
        }
        require(restored.all { it.groupId in balances }) { "Persisted TPM state references an unknown group" }
    }

    fun update(groups: List<AiTpmGroup>, activeGroupIds: Set<AiTpmGroup.Id> = emptySet()) {
        groups.forEach { group ->
            val balance = balances[group.id]
            if (balance == null) balances[group.id] = Balance(group, capacity(group), group.tokenLimit.toDouble(), 0, nanoTime(), nanoTime())
            else {
                refill(balance)
                balance.group = group
                balance.local = min(balance.local, capacity(group))
                balance.remote = min(balance.remote, group.tokenLimit.toDouble())
            }
        }
        balances.keys.retainAll(groups.map { it.id }.toSet() + activeGroupIds)
    }

    fun requireFits(reservation: AiTpmReservation) {
        val balance = requireNotNull(balances[reservation.groupId]) { "TPM group is unavailable" }
        require(reservation.estimatedUnits <= capacity(balance.group)) { "Request estimate exceeds usable TPM group capacity" }
    }

    fun eligible(reservation: AiTpmReservation, active: List<AiAdmissionLease>): Boolean {
        requireFits(reservation)
        val balance = balances.getValue(reservation.groupId)
        refill(balance)
        if (!balance.group.allowParallelRequests && active.any { it.operation.tpm?.groupId == reservation.groupId }) return false
        return nanoTime() >= balance.cooldown && reservation.estimatedUnits <= min(balance.local, balance.remote)
    }

    fun reserve(reservation: AiTpmReservation, active: List<AiAdmissionLease>): AiTpmReservation {
        val balance = balances.getValue(reservation.groupId)
        check(eligible(reservation, active)) { "TPM reservation was not eligible" }
        val outstanding = active.mapNotNull { it.operation.tpm }.filter { it.groupId == reservation.groupId }
        if (outstanding.isEmpty()) balance.total = 0
        val admitted = reservation.copy(cumulativeBefore = balance.total, outstandingBefore = outstanding.sumOf { it.estimatedUnits })
        balance.total = Math.addExact(balance.total, reservation.estimatedUnits)
        balance.local -= reservation.estimatedUnits
        balance.remote -= reservation.estimatedUnits
        return admitted
    }

    fun refundUndelivered(reservation: AiTpmReservation) {
        val balance = balances.getValue(reservation.groupId)
        refill(balance)
        balance.local = min(capacity(balance.group), balance.local + reservation.estimatedUnits)
        balance.remote = min(balance.group.tokenLimit.toDouble(), balance.remote + reservation.estimatedUnits)
    }

    fun observe(reservation: AiTpmReservation, feedback: AiTpmFeedback) {
        val balance = balances.getValue(reservation.groupId)
        refill(balance)
        feedback.remaining?.let { remaining ->
            val later = balance.total - reservation.cumulativeBefore - reservation.estimatedUnits
            check(later >= 0) { "TPM admission sequence went backwards" }
            val possiblyUnreflected = Math.addExact(reservation.outstandingBefore, later)
            val observed = min(balance.group.tokenLimit.toDouble(), remaining - possiblyUnreflected)
            // A concurrent response cannot prove that another call's reservation was already debited.
            balance.remote = if (possiblyUnreflected == 0L) observed else min(balance.remote, observed)
        }
        feedback.retryAfterMillis?.let { delay ->
            balance.cooldown = maxOf(balance.cooldown, nanoTime() + delay * 1_000_000)
        }
    }

    fun snapshot(): List<AiTpmState> = balances.values.map { balance ->
        refill(balance)
        val delay = ((balance.cooldown - nanoTime()).coerceAtLeast(0) + 999_999) / 1_000_000
        AiTpmState(balance.group.id, balance.local, balance.remote, balance.total,
            if (delay == 0L) null else wallTime() + delay.milliseconds)
    }

    private fun refill(balance: Balance) {
        val now = nanoTime()
        val elapsed = (now - balance.measured).coerceAtLeast(0) / 1_000_000_000.0
        val rate = balance.group.tokenLimit.toDouble() / balance.group.renewalPeriodSeconds
        balance.local = min(capacity(balance.group), balance.local + elapsed * rate * balance.group.quotaUsageFraction)
        balance.remote = min(balance.group.tokenLimit.toDouble(), balance.remote + elapsed * rate)
        balance.measured = now
    }

    private fun capacity(group: AiTpmGroup) = group.tokenLimit * group.quotaUsageFraction
}
