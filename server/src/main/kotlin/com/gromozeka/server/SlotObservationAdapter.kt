package com.gromozeka.server

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.slot.*
import com.gromozeka.domain.tool.*
import com.gromozeka.remote.protocol.*
import com.gromozeka.shared.uuid.uuid7
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Service
class SlotObservationAdapter(
    private val dispatches: SlotDispatchQuery,
    private val coordinator: ConversationRuntimeCoordinator,
    private val workspaces: WorkspaceDomainService,
    private val workers: ConversationRuntimeWorkerRegistry,
    private val workerAccess: WorkerAccessService,
    private val users: UserDirectoryService,
    private val projects: ProjectAccessService,
    private val requests: WorkerRequestService,
) : SlotObservationProvider {
    private val json = Json { encodeDefaults = true }

    override suspend fun activityWarnings(slot: Slot): List<String> = buildList {
        dispatches.pendingForSlot(slot.number).filter { it.origin.slotId == slot.id }.forEach {
            add("${if (it.dispatchedAt == null) "Pending" else "Unfinished"} Worker request ${it.requestId}, lease ${it.origin.leaseId}")
        }
        workspaces.findMounts(slot.workspaceId).map { it.workerId }.distinct().forEach { worker ->
            coordinator.findCommandMonitors(workerId = ConversationRuntimeWorkerId(worker))
                .filter { !it.isTerminal && it.slotOrigin?.slotId == slot.id }.forEach {
                    add("Running monitor ${it.id.value}, source lease ${it.slotOrigin!!.leaseId}; recorded state may be stale")
                }
            coordinator.findCommandTasks(workerId = ConversationRuntimeWorkerId(worker))
                .filter { !it.isTerminal && it.slotOrigin?.slotId == slot.id }.forEach {
                    add("Running command ${it.id.value}, lease ${it.slotOrigin!!.leaseId}; recorded state may be stale while Worker is offline")
                }
        }
    }

    override suspend fun inspect(slot: Slot, lease: SlotLease): SlotObservation {
        val warnings = try { activityWarnings(slot).toMutableList() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableListOf("Known command activity could not be inspected") }
        try {
            val checkout = inspectCheckout(slot, lease)
            warnings += checkout.warnings
            when (checkout.dirty) {
                true -> warnings += "Checkout has uncommitted or untracked changes"
                null -> warnings += "Checkout cleanliness is unknown"
                false -> Unit
            }
            val base = slot.baseBranch ?: checkout.defaultBranch
            if (base == null) warnings += "Base branch is unknown; configure base_branch or inspect it explicitly"
            else if (checkout.branch != base) warnings += "Current branch is ${checkout.branch ?: "unknown"}, expected base branch $base"
            if (checkout.aheadOfUpstream == null) warnings += "Upstream/push state is unknown; no network check was made"
            else if (requireNotNull(checkout.aheadOfUpstream) > 0) warnings += "${checkout.aheadOfUpstream} local commits are ahead of the locally known upstream"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { warnings += "Checkout inspection unavailable (${error::class.simpleName}); this is not a clean-state result" }
        return SlotObservation(Clock.System.now(), warnings.distinct())
    }

    private suspend fun inspectCheckout(slot: Slot, lease: SlotLease): SlotCheckoutObservation {
        val actor = requireNotNull(users.findActiveById(lease.actorUserId)) { "User unavailable" }
        projects.requirePermission(actor.id, slot.projectId, ProjectPermission.READ)
        val target = workspaces.resolveExecution(slot.mountId)
        require(target.workspace.id == slot.workspaceId && target.project.id == slot.projectId)
        val workerId = ConversationRuntimeWorkerId(target.mount.workerId)
        workerAccess.requirePermission(actor, workerId, WorkerPermission.USE, slot.projectId)
        workerAccess.requireProjectAccess(workerId, slot.projectId)
        val worker = requireNotNull(workers.find(workerId))
        require(worker.isOnline(Clock.System.now() - 30.seconds)) { "Worker offline" }
        require(worker.tools.any { it.definition.name == "grz_inspect_checkout" }) { "Worker does not support checkout inspection" }
        val call = Conversation.Message.ContentItem.ToolCall(Conversation.Message.ContentItem.ToolCall.Id(uuid7()),
            Conversation.Message.ContentItem.ToolCall.Data("grz_inspect_checkout", buildJsonObject {
                put("execution_target", buildJsonObject { put("workspace_mount_id", slot.mountId.value) })
            }))
        val context = mapOf(
            TOOL_CONTEXT_USER_ID to actor.id.value, TOOL_CONTEXT_PROJECT_ID to slot.projectId.value,
            TOOL_CONTEXT_CONVERSATION_ID to lease.conversationId.value, TOOL_CONTEXT_AGENT_DEFINITION_ID to lease.agentId.value,
            TOOL_CONTEXT_WORKER_ID to workerId.value, TOOL_CONTEXT_WORKSPACE_ID to slot.workspaceId.value,
            TOOL_CONTEXT_WORKSPACE_MOUNT_ID to slot.mountId.value, TOOL_CONTEXT_WORKSPACE_ROOT_PATH to target.mount.rootPath,
        )
        val execution = WorkerToolExecutionRequest(ConversationRuntimeTaskTarget.Worker(workerId, slot.mountId), listOf(call), context)
        val bytes = requests.execute(workerId, WorkerGatewayOperation.TOOL_EXECUTION, json.encodeToString(execution).encodeToByteArray(),
            WorkerRequestPolicy(deliveryTtlMillis = 2_000, executionTimeoutMillis = 25_000, waitTimeoutMillis = 28_000), actor.id, slot.projectId)
        val result = json.decodeFromString<WorkerToolExecutionResponse>(bytes.decodeToString()).results.single()
        require(!result.isError) { "Worker checkout inspection failed" }
        val text = result.result.filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.Text>().single().content
        return json.decodeFromString(text)
    }
}
