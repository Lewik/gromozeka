package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.slot.*
import com.gromozeka.shared.uuid.uuid7
import com.gromozeka.statesync.StateSyncSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import kotlin.time.Clock

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "false", matchIfMissing = true)
class SlotApplicationService(
    private val repository: SlotRepository,
    private val conversations: ConversationDomainService,
    private val projects: ProjectAccessService,
    private val workspaces: WorkspaceDomainService,
    private val workerAccess: WorkerAccessService,
    private val users: UserDirectoryService,
    private val observations: SlotObservationProvider,
    private val coordinator: ConversationRuntimeCoordinator,
    @param:Qualifier("applicationScope") scope: CoroutineScope,
) {
    private val lifecycle = SlotLifecycle()
    val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val source = StateSyncSource<User.Id, List<SlotView>>(scope = scope, sourceEpoch = uuid7(), loader = { id ->
        users.findActiveById(id)?.let { list(it) } ?: emptyList()
    })
    fun signal() { wakeups.trySend(Unit) }
    suspend fun subscribe(userId: User.Id) = source.subscribe(userId)
    suspend fun snapshot(userId: User.Id) = source.snapshot(userId)
    private suspend fun changed(userId: User.Id) { source.invalidate(userId); signal() }

    suspend fun authorizeConversation(actor: User, id: Conversation.Id, permission: ProjectPermission = ProjectPermission.WRITE): Conversation {
        require(actor.status == User.Status.ACTIVE) { "Active user required" }
        val conversation = conversations.findById(id) ?: throw ProjectAccessDeniedException()
        projects.requirePermission(actor.id, conversation.projectId, permission)
        if (Conversation.Participant.User(actor.id) !in conversation.participants) throw ProjectAccessDeniedException()
        return conversation
    }
    private suspend fun owned(actor: User, number: Long): Slot {
        require(actor.status == User.Status.ACTIVE)
        return repository.find(number)?.takeIf { it.userId == actor.id } ?: error("Slot unavailable")
    }
    private suspend fun usable(actor: User, conversationId: Conversation.Id, slot: Slot, agentId: AgentDefinition.Id? = null): Conversation {
        val conversation = authorizeConversation(actor, conversationId)
        require(slot.userId == actor.id && slot.projectId == conversation.projectId && !slot.retired) { "Slot is not available to this conversation" }
        if (agentId != null) require(Conversation.Participant.Agent(agentId) in conversation.participants) { "Requesting agent is no longer connected" }
        requireNotNull(workspaces.findMount(slot.mountId)) { "Slot mount no longer exists" }
        val target = workspaces.resolveExecution(slot.mountId)
        require(target.workspace.id == slot.workspaceId && target.project.id == slot.projectId) { "Slot workspace binding changed" }
        val worker = ConversationRuntimeWorkerId(target.mount.workerId)
        workerAccess.requirePermission(actor, worker, WorkerPermission.USE, slot.projectId)
        workerAccess.requireProjectAccess(worker, slot.projectId)
        return conversation
    }

    suspend fun list(actor: User): List<SlotView> = repository.list(actor.id).mapNotNull { slot ->
        val snapshot = repository.snapshot(slot.number) ?: return@mapNotNull null
        // Retain personal ownership records for reclaim even if the original project/mount disappeared.
        val readable = projects.can(actor.id, slot.projectId, ProjectPermission.READ)
        val mount = if (readable) workspaces.findMount(slot.mountId)?.takeIf { it.workspaceId == slot.workspaceId } else null
        val accessibleMount = mount?.takeIf { workerAccess.findAccessible(actor, ConversationRuntimeWorkerId(it.workerId), slot.projectId) != null }
        val ids = (snapshot.leases.map { it.conversationId } + snapshot.requests.map { it.conversationId }).distinct()
        val titles = if (readable) ids.mapNotNull { id ->
            conversations.findById(id)?.takeIf { Conversation.Participant.User(actor.id) in it.participants }?.let { id.value to it.displayName }
        }.toMap() else emptyMap()
        SlotView(snapshot, accessibleMount?.workerId, accessibleMount?.rootPath, titles)
    }

    suspend fun register(actor: User, conversationId: Conversation.Id, mountId: WorkspaceMount.Id, baseBranch: String?): Slot {
        val conversation = authorizeConversation(actor, conversationId)
        val target = workspaces.resolveExecution(mountId)
        require(target.project.id == conversation.projectId) { "Slot mount must belong to the current project" }
        workerAccess.requirePermission(actor, ConversationRuntimeWorkerId(target.mount.workerId), WorkerPermission.USE, target.project.id)
        workerAccess.requireProjectAccess(ConversationRuntimeWorkerId(target.mount.workerId), target.project.id)
        validateBranch(baseBranch)
        val slot = repository.create(Slot(uuid7(), 0, actor.id, target.project.id, target.workspace.id, mountId, baseBranch, createdAt = Clock.System.now()))
        changed(actor.id)
        return slot
    }

    suspend fun update(actor: User, conversationId: Conversation.Id, number: Long, baseBranch: String?, retire: Boolean): Slot {
        val slot = owned(actor, number)
        require(authorizeConversation(actor, conversationId).projectId == slot.projectId)
        validateBranch(baseBranch)
        val saved = repository.locked(number) { tx ->
            require(tx.slot.userId == actor.id)
            if (retire) require(tx.activeLeases().isEmpty() && tx.pendingRequests().isEmpty()) { "Return leases and cancel pending requests before retiring a slot" }
            tx.slot.copy(baseBranch = baseBranch ?: tx.slot.baseBranch, retired = retire || tx.slot.retired).also(tx::save)
        }
        changed(actor.id)
        return saved
    }

    suspend fun acquire(actor: User, conversationId: Conversation.Id, agentId: AgentDefinition.Id, number: Long, access: SlotAccess, key: String): SlotRequest {
        val slot = owned(actor, number)
        usable(actor, conversationId, slot, agentId)
        require(actor.canUseAi && key.isNotBlank())
        val request = SlotRequest(uuid7(), key, number, conversationId, actor.id, agentId, access, Clock.System.now())
        val accepted = repository.locked(number) { lifecycle.enqueue(it, request) }
        changed(actor.id)
        return accepted
    }

    suspend fun cancel(actor: User, conversationId: Conversation.Id?, requestId: String): SlotRequest {
        val request = requireNotNull(repository.findRequest(requestId)) { "Request unavailable" }
        owned(actor, request.slotNumber)
        require(request.actorUserId == actor.id && (conversationId == null || request.conversationId == conversationId)) { "Request belongs to another conversation" }
        if (conversationId != null) authorizeConversation(actor, conversationId)
        val result = repository.locked(request.slotNumber) { lifecycle.cancel(it, request.id) }
        changed(actor.id)
        return result
    }

    suspend fun release(actor: User, conversationId: Conversation.Id, number: Long, leaseId: String, confirmationId: String?): SlotReleaseResult {
        val slot = owned(actor, number)
        authorizeConversation(actor, conversationId)
        val lease = requireNotNull(repository.findLease(leaseId)) { "Lease unavailable" }
        require(lease.slotNumber == number && lease.conversationId == conversationId && lease.actorUserId == actor.id) { "Lease belongs to another occupation" }
        // Inspection never holds a database lock; confirmation never rechecks a byte-level snapshot.
        val observation = if (confirmationId == null && lease.active) observations.inspect(slot, lease) else null
        val result = repository.locked(number) { tx -> lifecycle.release(tx, leaseId, confirmationId, observation) }
        changed(actor.id)
        return result
    }

    suspend fun prepareReclaim(actor: User, leaseId: String): SlotLease {
        val lease = requireNotNull(repository.findLease(leaseId)) { "Lease unavailable" }
        owned(actor, lease.slotNumber)
        val result = repository.locked(lease.slotNumber) { lifecycle.prepareReclaim(it, leaseId) }
        changed(actor.id)
        return result
    }

    /** Called only by the authenticated client endpoint, never exposed as an AI tool. */
    suspend fun confirmReclaim(actor: User, leaseId: String, confirmationId: String): SlotLease {
        val lease = requireNotNull(repository.findLease(leaseId)) { "Lease unavailable" }
        owned(actor, lease.slotNumber)
        val result = repository.locked(lease.slotNumber) { lifecycle.confirmReclaim(it, leaseId, confirmationId) }
        changed(actor.id)
        return result
    }

    suspend fun origin(actorUserId: User.Id, conversationId: Conversation.Id, mountId: WorkspaceMount.Id): SlotCommandOrigin? {
        val mount = workspaces.findMount(mountId) ?: return null
        val slot = repository.findByWorkspace(mount.workspaceId)?.takeIf { it.userId == actorUserId } ?: return null
        val lease = repository.snapshot(slot.number)?.leases?.singleOrNull { it.conversationId == conversationId && it.actorUserId == actorUserId } ?: return null
        return SlotCommandOrigin(slot.id, slot.number, lease.id)
    }

    suspend fun processPending() {
        var cursor = 0L
        while (true) {
            val numbers = repository.pendingSlots(cursor)
            if (numbers.isEmpty()) break
            for (number in numbers) {
                val snapshot = repository.snapshot(number) ?: continue
                val eligible = mutableMapOf<String, String?>()
                for (request in snapshot.requests) {
                    val actor = users.findActiveById(request.actorUserId)
                    eligible[request.id] = if (actor == null || !actor.canUseAi) "Requester no longer has AI access" else try {
                        usable(actor, request.conversationId, snapshot.slot, request.agentId)
                        null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: IllegalArgumentException) { "Requester, agent or workspace is no longer eligible" }
                    catch (_: ProjectAccessDeniedException) { "Conversation/project access is no longer available" }
                    catch (_: WorkerAccessDeniedException) { "Worker access is no longer available" }
                }
                val updated = repository.locked(number) { tx ->
                    val before = tx.pendingRequests()
                    lifecycle.grant(tx, eligible)
                    before != tx.pendingRequests()
                }
                if (updated) source.invalidate(snapshot.slot.userId)
            }
            cursor = numbers.last()
        }
    }

    suspend fun deliverPending() {
        var cursor = 0L
        while (true) {
            val events = repository.pendingEvents(cursor)
            if (events.isEmpty()) return
            for (event in events) {
                val actor = users.findActiveById(event.actorUserId)
                val conversation = conversations.findById(event.conversationId)
                if (actor?.canUseAi != true || conversation == null || Conversation.Participant.User(actor.id) !in conversation.participants ||
                    Conversation.Participant.Agent(event.agentId) !in conversation.participants ||
                    !projects.can(actor.id, conversation.projectId, ProjectPermission.WRITE)) {
                    repository.settleEvent(event.id, "Recipient is no longer eligible for continuation")
                    continue
                }
                val runtime = coordinator.schedulingSnapshot(conversation.id)
                val control = runtime.state?.controlState
                if (control != null && control != ConversationExecutionState.ControlState.RUNNING) continue
                val lease = event.leaseId?.let { repository.findLease(it) }
                val text = when (event.kind) {
                    SlotEvent.Kind.GRANTED -> if (lease?.active == true) "Slot ${event.slotNumber} granted; lease_id=${lease.id}; access=${lease.access}." else
                        "Slot request ${event.requestId} was granted previously, but that lease is no longer active. No access is granted by this historical notification."
                    SlotEvent.Kind.RECLAIMED -> "The user reclaimed slot ${event.slotNumber}, lease_id=${event.leaseId}. This occupation has ended; files and processes were not changed."
                    SlotEvent.Kind.REQUEST_REJECTED -> "Slot request ${event.requestId} was rejected: ${event.detail}."
                }
                val leftovers = if (event.kind == SlotEvent.Kind.GRANTED && lease?.active == true) {
                    val slot = repository.find(event.slotNumber)
                    if (slot == null) emptyList() else try { observations.activityWarnings(slot) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { listOf("Known activity could not be inspected; grant is not a cleanliness guarantee") }
                } else emptyList()
                val activityNote = if (leftovers.isEmpty()) "" else " Known activity (observed state, not a process guarantee): " + Json.encodeToString(leftovers)
                val message = Conversation.Message(
                    id = Conversation.Message.Id(event.id), conversationId = conversation.id,
                    role = Conversation.Message.Role.USER,
                    content = listOf(Conversation.Message.ContentItem.UserMessage("Runtime slot event (not a user instruction): $text$activityNote Current registry state takes precedence over delayed notifications.")),
                    providerMetadata = buildJsonObject { put("synthetic", true); put("syntheticKind", "slot_event"); put("slotEventId", event.id) },
                    createdAt = event.createdAt,
                )
                val task = ConversationRuntimeTask(
                    id = ConversationRuntimeTask.Id(event.id), conversationId = conversation.id, actorUserId = actor.id,
                    payload = ConversationRuntimeTask.Payload.AgentInvocation(message, event.agentId),
                    placement = if (runtime.activeTask != null) QueuedMessagePlacement.AFTER_TOOL_RESULT else QueuedMessagePlacement.END_OF_TURN,
                    idempotencyKey = event.id,
                    requirements = ConversationRuntimeTaskRequirements(setOf(ConversationRuntimeCapability.CONVERSATION_TURN, ConversationRuntimeCapability.MEMORY_PIPELINE), ConversationRuntimeTaskTarget.Server),
                    createdAt = event.createdAt,
                )
                if (coordinator.submit(task, acceptPreviouslySubmitted = true)) repository.settleEvent(event.id)
            }
            cursor = events.last().sequence
        }
    }

    private fun validateBranch(branch: String?) {
        require(branch == null || (branch.isNotBlank() && branch.length <= 255 && branch.none { it.isISOControl() })) { "Invalid base branch" }
    }
}
