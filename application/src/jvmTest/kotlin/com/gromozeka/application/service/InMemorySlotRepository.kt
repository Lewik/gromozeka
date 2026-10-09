package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.slot.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class InMemorySlotRepository : SlotRepository {
    private val mutex = Mutex()
    private val slots = linkedMapOf<Long, Slot>()
    private val leases = linkedMapOf<String, SlotLease>()
    private val requests = linkedMapOf<String, SlotRequest>()
    private val events = linkedMapOf<String, SlotEvent>()
    private val finished = mutableSetOf<String>()
    private var nextNumber = 1L
    override suspend fun create(slot: Slot) = mutex.withLock {
        require(slots.values.none { !it.retired && it.workspaceId == slot.workspaceId })
        slot.copy(number = nextNumber++).also { slots[it.number] = it }
    }
    override suspend fun list(userId: User.Id) = mutex.withLock { slots.values.filter { it.userId == userId && !it.retired } }
    override suspend fun find(number: Long) = mutex.withLock { slots[number] }
    override suspend fun findByWorkspace(workspaceId: Workspace.Id) = mutex.withLock { slots.values.singleOrNull { it.workspaceId == workspaceId && !it.retired } }
    override suspend fun findLease(id: String) = mutex.withLock { leases[id] }
    override suspend fun findRequest(id: String) = mutex.withLock { requests[id] }
    override suspend fun snapshot(number: Long) = mutex.withLock { slots[number]?.let { slot ->
        SlotSnapshot(slot, leases.values.filter { it.slotNumber == number && it.active }, requests.values.filter { it.slotNumber == number && it.state == SlotRequest.State.PENDING })
    } }
    override suspend fun pendingSlots(afterNumber: Long, limit: Int) = mutex.withLock {
        requests.values.filter { it.state == SlotRequest.State.PENDING && it.slotNumber > afterNumber }.map { it.slotNumber }.distinct().sorted().take(limit)
    }
    override suspend fun pendingEvents(afterSequence: Long, limit: Int) = mutex.withLock {
        events.values.filter { it.sequence > afterSequence && it.id !in finished }.sortedBy { it.sequence }.take(limit)
    }
    override suspend fun settleEvent(id: String, error: String?) { mutex.withLock { finished += id } }
    override suspend fun <T> locked(number: Long, action: (SlotTransaction) -> T): T = mutex.withLock {
        var currentSlot = requireNotNull(slots[number])
        val nextLeases = leases.toMutableMap(); val nextRequests = requests.toMutableMap(); val nextEvents = events.toMutableMap()
        val result = action(object : SlotTransaction {
            override val slot: Slot get() = currentSlot
            override fun activeLeases() = nextLeases.values.filter { it.slotNumber == number && it.active }
            override fun pendingRequests() = nextRequests.values.filter { it.slotNumber == number && it.state == SlotRequest.State.PENDING }.sortedWith(compareBy({ it.requestedAt }, { it.id }))
            override fun lease(id: String) = nextLeases[id]?.takeIf { it.slotNumber == number }
            override fun request(id: String) = nextRequests[id]?.takeIf { it.slotNumber == number }
            override fun requestByKey(key: String) = nextRequests.values.singleOrNull { it.idempotencyKey == key }
            override fun save(slot: Slot) { require(slot.number == number); currentSlot = slot }
            override fun save(lease: SlotLease) { nextLeases[lease.id] = lease }
            override fun save(request: SlotRequest) { nextRequests[request.id] = request }
            override fun emit(event: SlotEvent) { nextEvents.putIfAbsent(event.id, event.copy(sequence = nextEvents.size.toLong() + 1)) }
        })
        slots[number] = currentSlot
        leases.clear(); leases.putAll(nextLeases); requests.clear(); requests.putAll(nextRequests); events.clear(); events.putAll(nextEvents)
        result
    }
}
