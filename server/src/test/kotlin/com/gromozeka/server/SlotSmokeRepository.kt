package com.gromozeka.server

import com.gromozeka.domain.model.*
import com.gromozeka.domain.slot.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Test-local transaction store; never connects to the running Server or its slot registry. */
internal class SlotSmokeRepository : SlotRepository {
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
    override suspend fun list(userId: User.Id) = mutex.withLock { slots.values.filter { it.userId == userId && !it.retired }.sortedBy { it.number } }
    override suspend fun find(number: Long) = mutex.withLock { slots[number] }
    override suspend fun findByWorkspace(workspaceId: Workspace.Id) = mutex.withLock { slots.values.singleOrNull { it.workspaceId == workspaceId && !it.retired } }
    override suspend fun findLease(id: String) = mutex.withLock { leases[id] }
    override suspend fun findRequest(id: String) = mutex.withLock { requests[id] }
    override suspend fun snapshot(number: Long) = mutex.withLock { slots[number]?.let { slot ->
        SlotSnapshot(slot, leases.values.filter { it.slotNumber == number && it.active }, requests.values.filter { it.slotNumber == number && it.state == SlotRequest.State.PENDING })
    } }
    override suspend fun pendingRequests(userId: User.Id) = mutex.withLock {
        requests.values.filter { it.actorUserId == userId && it.state == SlotRequest.State.PENDING }.sortedWith(compareBy({ it.requestedAt }, { it.id }))
    }
    override suspend fun pendingUsers(afterUserId: User.Id?, limit: Int) = mutex.withLock {
        requests.values.filter { it.state == SlotRequest.State.PENDING && it.actorUserId.value > afterUserId?.value.orEmpty() }
            .map { it.actorUserId.value }.distinct().sorted().take(limit).map { User.Id(it) }
    }
    override suspend fun pendingEvents(afterSequence: Long, limit: Int) = mutex.withLock {
        events.values.filter { it.sequence > afterSequence && it.id !in finished }.sortedBy { it.sequence }.take(limit)
    }
    override suspend fun settleEvent(id: String, error: String?) { mutex.withLock { finished += id } }
    override suspend fun <T> locked(number: Long, action: (SlotTransaction) -> T): T = mutex.withLock {
        transaction { state -> requireNotNull(state.slots[number]); action(state.session(number)) }
    }
    override suspend fun <T> lockedQueue(userId: User.Id, action: (SlotQueueTransaction) -> T): T = mutex.withLock {
        transaction { state -> action(object : SlotQueueTransaction {
            override val userId = userId
            override fun slots(): List<SlotTransaction> = state.slots.values.filter { it.userId == userId }.sortedBy { it.number }.map { state.session(it.number) }
            override fun pendingRequests() = state.requests.values.filter { it.actorUserId == userId && it.state == SlotRequest.State.PENDING }.sortedWith(compareBy({ it.requestedAt }, { it.id }))
            override fun request(id: String) = state.requests[id]?.takeIf { it.actorUserId == userId }
            override fun requestByKey(key: String) = state.requests.values.singleOrNull { it.idempotencyKey == key }
            override fun save(request: SlotRequest) {
                require(request.actorUserId == userId)
                require(request.slotNumber == null || state.slots[request.slotNumber]?.userId == userId)
                state.requests[request.id] = request
            }
            override fun emit(event: SlotEvent) { require(event.actorUserId == userId); state.emit(event) }
        }) }
    }
    private fun <T> transaction(action: (State) -> T): T {
        val state = State(slots.toMutableMap(), leases.toMutableMap(), requests.toMutableMap(), events.toMutableMap())
        val result = action(state)
        slots.clear(); slots.putAll(state.slots)
        leases.clear(); leases.putAll(state.leases)
        requests.clear(); requests.putAll(state.requests)
        events.clear(); events.putAll(state.events)
        return result
    }
    private class State(
        val slots: MutableMap<Long, Slot>, val leases: MutableMap<String, SlotLease>,
        val requests: MutableMap<String, SlotRequest>, val events: MutableMap<String, SlotEvent>,
    ) {
        fun emit(event: SlotEvent) { events.putIfAbsent(event.id, event.copy(sequence = events.size.toLong() + 1)) }
        fun session(number: Long) = object : SlotTransaction {
            override val slot: Slot get() = slots.getValue(number)
            override fun activeLeases() = leases.values.filter { it.slotNumber == number && it.active }
            override fun pendingRequests() = requests.values.filter { it.slotNumber == number && it.state == SlotRequest.State.PENDING }.sortedWith(compareBy({ it.requestedAt }, { it.id }))
            override fun lease(id: String) = leases[id]?.takeIf { it.slotNumber == number }
            override fun request(id: String) = requests[id]?.takeIf { it.slotNumber == number }
            override fun requestByKey(key: String) = requests.values.singleOrNull { it.idempotencyKey == key }
            override fun save(slot: Slot) {
                val old = this.slot
                require(slot.number == number && slot.id == old.id && slot.userId == old.userId && slot.projectId == old.projectId && slot.workspaceId == old.workspaceId && slot.mountId == old.mountId)
                slots[number] = slot
            }
            override fun save(lease: SlotLease) {
                require(lease.slotNumber == number)
                if (lease.active) require(activeLeases().none {
                    it.id != lease.id && (it.conversationId == lease.conversationId || it.access == SlotAccess.WRITE && lease.access == SlotAccess.WRITE)
                })
                leases[lease.id] = lease
            }
            override fun save(request: SlotRequest) { require(request.slotNumber == number && request.actorUserId == slot.userId); requests[request.id] = request }
            override fun emit(event: SlotEvent) { require(event.slotNumber == number); this@State.emit(event) }
        }
    }
}
