package com.gromozeka.domain.slot

import com.gromozeka.domain.model.*
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Personal registry entry. Filesystem identity remains owned by Workspace/Mount. */
@Serializable
data class Slot(
    val id: String,
    val number: Long,
    val userId: User.Id,
    val projectId: Project.Id,
    val workspaceId: Workspace.Id,
    val mountId: WorkspaceMount.Id,
    val baseBranch: String? = null,
    val retired: Boolean = false,
    val createdAt: Instant,
)

/** READ may coexist with WRITE. These are cooperative leases, not a filesystem sandbox. */
@Serializable enum class SlotAccess { READ, WRITE }

@Serializable
data class SlotLease(
    val id: String,
    val slotNumber: Long,
    val conversationId: Conversation.Id,
    val actorUserId: User.Id,
    val agentId: AgentDefinition.Id,
    val access: SlotAccess,
    val acquiredAt: Instant,
    val releasedAt: Instant? = null,
    val releaseConfirmation: SlotReleaseConfirmation? = null,
    val reclaimConfirmation: SlotReclaimConfirmation? = null,
) {
    val active: Boolean get() = releasedAt == null
}

@Serializable
data class SlotReleaseConfirmation(
    val id: String,
    val observation: SlotObservation,
    val expiresAt: Instant,
    val consumedAt: Instant? = null,
)

/** Only the authenticated client confirmation endpoint may consume this receipt. */
@Serializable
data class SlotReclaimConfirmation(
    val id: String,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val consumedAt: Instant? = null,
)

@Serializable
data class SlotRequest(
    val id: String,
    val idempotencyKey: String,
    val slotNumber: Long,
    val conversationId: Conversation.Id,
    val actorUserId: User.Id,
    val agentId: AgentDefinition.Id,
    val access: SlotAccess,
    val requestedAt: Instant,
    val state: State = State.PENDING,
    val leaseId: String? = null,
    val reason: String? = null,
) {
    @Serializable enum class State { PENDING, GRANTED, CANCELLED, REJECTED }
}

@Serializable
data class SlotObservation(val observedAt: Instant, val warnings: List<String>)

@Serializable
data class SlotReleaseResult(
    val leaseId: String,
    val released: Boolean,
    val confirmationId: String? = null,
    val observation: SlotObservation? = null,
)

/** Immutable launch provenance, not an assertion about every affected path. */
@Serializable
data class SlotCommandOrigin(val slotId: String, val slotNumber: Long, val leaseId: String)

/** Transactional outbox entry. The Runtime task uses id as its idempotency key. */
@Serializable
data class SlotEvent(
    val id: String,
    val slotNumber: Long,
    val requestId: String?,
    val leaseId: String?,
    val conversationId: Conversation.Id,
    val actorUserId: User.Id,
    val agentId: AgentDefinition.Id,
    val kind: Kind,
    val createdAt: Instant,
    val detail: String? = null,
    val sequence: Long = 0,
) {
    @Serializable enum class Kind { GRANTED, REQUEST_REJECTED, RECLAIMED }
}

@Serializable
data class SlotSnapshot(val slot: Slot, val leases: List<SlotLease>, val requests: List<SlotRequest>)

/** Client snapshot: paths are resolved from mounts, titles from current Conversations. */
@Serializable
data class SlotView(
    val snapshot: SlotSnapshot,
    val workerId: String?,
    val rootPath: String?,
    val conversationTitles: Map<String, String> = emptyMap(),
)

interface SlotService {
    fun observe(): kotlinx.coroutines.flow.Flow<List<SlotView>>
    suspend fun list(): List<SlotView>
    suspend fun cancelRequest(requestId: String): SlotRequest
    suspend fun prepareReclaim(leaseId: String): SlotLease
    suspend fun confirmReclaim(leaseId: String, confirmationId: String): SlotLease
}

/** Read-only best-effort observations; implementation may not repair Git or kill processes. */
interface SlotObservationProvider {
    suspend fun inspect(slot: Slot, lease: SlotLease): SlotObservation
    suspend fun activityWarnings(slot: Slot): List<String>
}

/** Metadata-only pending dispatch lookup; never exposes encrypted command arguments. */
interface SlotDispatchQuery {
    suspend fun pendingForSlot(number: Long): List<SlotPendingDispatch>
}

@Serializable
data class SlotPendingDispatch(
    val requestId: String,
    val origin: SlotCommandOrigin,
    val createdAt: Instant,
    val dispatchedAt: Instant?,
)

@Serializable
data class SlotCheckoutObservation(
    val observedAt: Instant,
    val dirty: Boolean? = null,
    val branch: String? = null,
    val defaultBranch: String? = null,
    val aheadOfUpstream: Long? = null,
    val warnings: List<String> = emptyList(),
)
