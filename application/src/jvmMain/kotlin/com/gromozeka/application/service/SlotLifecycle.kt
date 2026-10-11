package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.slot.*
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** Eligibility is observed outside the transaction; occupation and candidate choice are checked inside it. */
data class SlotRequestEligibility(val candidates: Set<Long> = emptySet(), val rejection: String? = null)

/** Pure registry transitions. Never perform I/O while holding a repository transaction. */
class SlotLifecycle(private val clock: Clock = Clock.System) {
    fun enqueue(tx: SlotQueueTransaction, request: SlotRequest): SlotRequest {
        require(request.actorUserId == tx.userId)
        val exact = request.slotNumber?.let { number ->
            require(number > 0)
            requireNotNull(tx.slots().singleOrNull { it.slot.number == number }) { "Slot unavailable" }
                .also { require(!it.slot.retired && it.slot.userId == request.actorUserId) }
        }
        require(exact != null || request.projectId != null) { "Automatic selection requires its original project" }
        tx.requestByKey(request.idempotencyKey)?.let { old ->
            require(old.slotNumber == request.slotNumber && old.projectId == request.projectId &&
                old.conversationId == request.conversationId && old.actorUserId == request.actorUserId &&
                old.agentId == request.agentId && old.access == request.access) { "Request key already used with different arguments" }
            return old
        }
        if (exact != null) {
            require(exact.activeLeases().none { it.conversationId == request.conversationId }) { "Conversation already holds this slot; inspect its existing lease" }
            require(exact.pendingRequests().none { it.conversationId == request.conversationId }) { "Conversation already has a pending request for this slot" }
        }
        tx.save(request)
        return request
    }

    /**
     * FIFO for overlapping candidates, without head-of-line blocking on unrelated slots.
     * An older waiting request fences its candidate slots for the remainder of this sweep.
     * Automatic selection takes the lowest eligible number not already held by the Conversation,
     * and never consumes that Conversation's separately requested exact slot. No reuse or upgrade.
     * New catalog entries / changed eligibility are considered on the next validated sweep.
     */
    fun grant(tx: SlotQueueTransaction, eligibility: Map<String, SlotRequestEligibility>): List<SlotLease> {
        val result = mutableListOf<SlotLease>()
        val pending = tx.pendingRequests().sortedWith(compareBy({ it.requestedAt }, { it.id }))
        val slots = tx.slots().sortedBy { it.slot.number }
        val blocked = mutableSetOf<Long>()
        val exactReservations = pending.filter { it.slotNumber != null }.groupBy { it.conversationId }
            .mapValues { (_, requests) -> requests.mapNotNull { it.slotNumber }.toMutableSet() }
        for (request in pending) {
            val checked = eligibility[request.id] ?: break
            if (checked.rejection != null) {
                reject(tx, request, checked.rejection)
                request.slotNumber?.let { exactReservations[request.conversationId]?.remove(it) }
                continue
            }
            val exact = request.slotNumber?.let { number -> slots.singleOrNull { it.slot.number == number } }
            if (request.slotNumber != null && (exact == null || exact.slot.retired || request.slotNumber !in checked.candidates)) {
                reject(tx, request, "Requested slot is no longer eligible")
                request.slotNumber?.let { exactReservations[request.conversationId]?.remove(it) }
                continue
            }
            if (exact != null && exact.activeLeases().any { it.conversationId == request.conversationId }) {
                reject(tx, request, "Conversation already holds this slot; inspect its existing lease")
                request.slotNumber?.let { exactReservations[request.conversationId]?.remove(it) }
                continue
            }
            val candidates = if (exact != null) listOf(exact) else slots.filter { candidate ->
                !candidate.slot.retired && candidate.slot.projectId == request.projectId &&
                    candidate.slot.number in checked.candidates &&
                    candidate.activeLeases().none { it.conversationId == request.conversationId } &&
                    candidate.slot.number !in exactReservations[request.conversationId].orEmpty()
            }
            val selected = candidates.firstOrNull { candidate ->
                candidate.slot.number !in blocked &&
                    (request.access == SlotAccess.READ || candidate.activeLeases().none { it.access == SlotAccess.WRITE })
            }
            if (selected == null) {
                blocked += candidates.map { it.slot.number }
                continue
            }
            val lease = SlotLease(uuid7(), selected.slot.number, request.conversationId, request.actorUserId,
                request.agentId, request.access, clock.now())
            selected.save(lease)
            tx.save(request.copy(state = SlotRequest.State.GRANTED, leaseId = lease.id, grantedSlotNumber = selected.slot.number))
            tx.emit(event(request, SlotEvent.Kind.GRANTED, lease.id, slotNumber = selected.slot.number))
            request.slotNumber?.let { exactReservations[request.conversationId]?.remove(it) }
            result += lease
        }
        return result
    }

    private fun reject(tx: SlotRequestTransaction, request: SlotRequest, reason: String) {
        tx.save(request.copy(state = SlotRequest.State.REJECTED, reason = reason))
        tx.emit(event(request, SlotEvent.Kind.REQUEST_REJECTED, detail = reason))
    }

    fun cancel(tx: SlotRequestTransaction, id: String): SlotRequest {
        val request = requireNotNull(tx.request(id)) { "Request not found" }
        // A grant won the race: report it, never convert cancellation into implicit release.
        if (request.state != SlotRequest.State.PENDING) return request
        return request.copy(state = SlotRequest.State.CANCELLED).also(tx::save)
    }

    fun release(tx: SlotTransaction, leaseId: String, confirmationId: String?, observation: SlotObservation?): SlotReleaseResult {
        val lease = requireNotNull(tx.lease(leaseId)) { "Lease not found in this slot" }
        val confirmation = lease.releaseConfirmation
        if (confirmationId != null) {
            require(confirmation?.id == confirmationId) { "Confirmation does not belong to this lease" }
            if (confirmation.consumedAt != null) return SlotReleaseResult(lease.id, true, observation = confirmation.observation)
            require(confirmation.expiresAt > clock.now()) { "Confirmation expired; request a fresh observation" }
        }
        if (!lease.active) return SlotReleaseResult(lease.id, true)
        if (confirmationId != null) {
            val now = clock.now()
            tx.save(lease.copy(releasedAt = now, releaseConfirmation = requireNotNull(confirmation).copy(consumedAt = now)))
            return SlotReleaseResult(lease.id, true, observation = confirmation.observation)
        }
        val checked = requireNotNull(observation) { "Release requires an observation or issued confirmation" }
        if (checked.warnings.isNotEmpty()) {
            val issued = SlotReleaseConfirmation(uuid7(), checked, clock.now() + 15.minutes)
            tx.save(lease.copy(releaseConfirmation = issued))
            return SlotReleaseResult(lease.id, false, issued.id, checked)
        }
        tx.save(lease.copy(releasedAt = clock.now()))
        return SlotReleaseResult(lease.id, true, observation = checked)
    }

    fun prepareReclaim(tx: SlotTransaction, leaseId: String): SlotLease {
        val lease = requireNotNull(tx.lease(leaseId)) { "Lease not found" }
        if (!lease.active) return lease
        val previous = lease.reclaimConfirmation
        if (previous != null && previous.expiresAt > clock.now()) return lease
        return lease.copy(reclaimConfirmation = SlotReclaimConfirmation(uuid7(), clock.now(), clock.now() + 15.minutes)).also(tx::save)
    }

    /** Authorized client endpoint only. No agent-facing tool may call this method. */
    fun confirmReclaim(tx: SlotTransaction, leaseId: String, confirmationId: String): SlotLease {
        val lease = requireNotNull(tx.lease(leaseId)) { "Lease not found" }
        val confirmation = requireNotNull(lease.reclaimConfirmation) { "Prepare human confirmation first" }
        require(confirmation.id == confirmationId) { "Confirmation belongs to another lease" }
        if (confirmation.consumedAt != null || !lease.active) return lease
        require(confirmation.expiresAt > clock.now()) { "Human confirmation expired" }
        val now = clock.now()
        val updated = lease.copy(releasedAt = now, reclaimConfirmation = confirmation.copy(consumedAt = now))
        tx.save(updated)
        tx.emit(SlotEvent("slot:${lease.id}:reclaimed", tx.slot.number, null, lease.id, lease.conversationId,
            lease.actorUserId, lease.agentId, SlotEvent.Kind.RECLAIMED, now))
        return updated
    }

    private fun event(request: SlotRequest, kind: SlotEvent.Kind, leaseId: String? = null, detail: String? = null, slotNumber: Long? = request.slotNumber) =
        SlotEvent("slot:${request.id}:${kind.name}", slotNumber, request.id, leaseId, request.conversationId,
            request.actorUserId, request.agentId, kind, clock.now(), detail)
}
