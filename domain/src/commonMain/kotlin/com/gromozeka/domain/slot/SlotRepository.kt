package com.gromozeka.domain.slot

import com.gromozeka.domain.model.*

/** Short database transactions serialize one slot, never an entire occupation. */
interface SlotRepository {
    /** Allocate a never-reused Runtime-wide number. Reject another live registration of this Workspace. */
    suspend fun create(slot: Slot): Slot
    suspend fun list(userId: User.Id): List<Slot>
    suspend fun find(number: Long): Slot?
    suspend fun findByWorkspace(workspaceId: Workspace.Id): Slot?
    suspend fun findLease(id: String): SlotLease?
    suspend fun findRequest(id: String): SlotRequest?
    suspend fun snapshot(number: Long): SlotSnapshot?
    suspend fun pendingSlots(afterNumber: Long = 0, limit: Int = 100): List<Long>
    suspend fun pendingEvents(afterSequence: Long = 0, limit: Int = 100): List<SlotEvent>
    suspend fun settleEvent(id: String, error: String? = null)
    suspend fun <T> locked(number: Long, action: (SlotTransaction) -> T): T
}

/** Repository primitives only; compatibility and lifecycle decisions belong to application code. */
interface SlotTransaction {
    val slot: Slot
    fun activeLeases(): List<SlotLease>
    fun pendingRequests(): List<SlotRequest>
    fun lease(id: String): SlotLease?
    fun request(id: String): SlotRequest?
    fun requestByKey(key: String): SlotRequest?
    fun save(slot: Slot)
    fun save(lease: SlotLease)
    fun save(request: SlotRequest)
    fun emit(event: SlotEvent)
}
