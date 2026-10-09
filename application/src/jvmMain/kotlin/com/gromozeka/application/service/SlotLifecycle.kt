package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.slot.*
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** Pure registry transitions. Call inside SlotRepository.locked; never perform I/O in a transition. */
class SlotLifecycle(private val clock: Clock = Clock.System) {
    fun enqueue(tx: SlotTransaction, request: SlotRequest): SlotRequest {
        require(!tx.slot.retired && request.slotNumber == tx.slot.number && request.actorUserId == tx.slot.userId)
        tx.requestByKey(request.idempotencyKey)?.let { old ->
            require(old.slotNumber == request.slotNumber && old.conversationId == request.conversationId &&
                old.actorUserId == request.actorUserId && old.agentId == request.agentId && old.access == request.access) { "Request key already used with different arguments" }
            return old
        }
        require(tx.activeLeases().none { it.conversationId == request.conversationId }) { "Conversation already holds this slot; inspect its existing lease" }
        require(tx.pendingRequests().none { it.conversationId == request.conversationId }) { "Conversation already has a pending request for this slot" }
        tx.save(request)
        return request
    }

    /** FIFO per slot. A new request not in this validated batch waits for the next sweep. */
    fun grant(tx: SlotTransaction, eligibility: Map<String, String?>): List<SlotLease> {
        val result = mutableListOf<SlotLease>()
        for (request in tx.pendingRequests()) {
            if (!eligibility.containsKey(request.id)) break
            val rejection = eligibility[request.id] ?: if (tx.slot.retired) "Slot was retired" else null
            if (rejection != null) {
                tx.save(request.copy(state = SlotRequest.State.REJECTED, reason = rejection))
                tx.emit(event(request, SlotEvent.Kind.REQUEST_REJECTED, detail = rejection))
                continue
            }
            if (request.access == SlotAccess.WRITE && tx.activeLeases().any { it.access == SlotAccess.WRITE }) break
            check(tx.activeLeases().none { it.conversationId == request.conversationId }) { "Duplicate occupation" }
            val lease = SlotLease(uuid7(), tx.slot.number, request.conversationId, request.actorUserId,
                request.agentId, request.access, clock.now())
            tx.save(lease)
            tx.save(request.copy(state = SlotRequest.State.GRANTED, leaseId = lease.id))
            tx.emit(event(request, SlotEvent.Kind.GRANTED, lease.id))
            result += lease
        }
        return result
    }

    fun cancel(tx: SlotTransaction, id: String): SlotRequest {
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

    private fun event(request: SlotRequest, kind: SlotEvent.Kind, leaseId: String? = null, detail: String? = null) =
        SlotEvent("slot:${request.id}:${kind.name}", request.slotNumber, request.id, leaseId, request.conversationId,
            request.actorUserId, request.agentId, kind, clock.now(), detail)
}
