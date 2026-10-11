package com.gromozeka.domain.slot

import com.gromozeka.domain.model.*

/** Short transactions serialize selection within one personal catalog, never an entire occupation. */
interface SlotRepository {
    /** Allocate a never-reused Runtime-wide number. Reject another live registration of this Workspace. */
    suspend fun create(slot: Slot): Slot
    suspend fun list(userId: User.Id): List<Slot>
    suspend fun find(number: Long): Slot?
    suspend fun findByWorkspace(workspaceId: Workspace.Id): Slot?
    suspend fun findLease(id: String): SlotLease?
    suspend fun findRequest(id: String): SlotRequest?
    suspend fun snapshot(number: Long): SlotSnapshot?
    suspend fun pendingRequests(userId: User.Id): List<SlotRequest>
    suspend fun pendingUsers(afterUserId: User.Id? = null, limit: Int = 100): List<User.Id>
    suspend fun pendingEvents(afterSequence: Long = 0, limit: Int = 100): List<SlotEvent>
    suspend fun settleEvent(id: String, error: String? = null)
    /** Shares the catalog lock with lockedQueue, including return/reclaim/retirement. */
    suspend fun <T> locked(number: Long, action: (SlotTransaction) -> T): T
    /** Atomic choose-and-grant across existing slots and both exact and automatic requests. */
    suspend fun <T> lockedQueue(userId: User.Id, action: (SlotQueueTransaction) -> T): T
}

/** Repository primitives only; compatibility, candidate selection and fairness belong to application code. */
interface SlotRequestTransaction {
    fun pendingRequests(): List<SlotRequest>
    fun request(id: String): SlotRequest?
    fun requestByKey(key: String): SlotRequest?
    fun save(request: SlotRequest)
    fun emit(event: SlotEvent)
}

interface SlotQueueTransaction : SlotRequestTransaction {
    val userId: User.Id
    /** All entries in the personal catalog, including retired entries needed for old exact requests. */
    fun slots(): List<SlotTransaction>
}

interface SlotTransaction : SlotRequestTransaction {
    val slot: Slot
    fun activeLeases(): List<SlotLease>
    fun lease(id: String): SlotLease?
    fun save(slot: Slot)
    fun save(lease: SlotLease)
}
