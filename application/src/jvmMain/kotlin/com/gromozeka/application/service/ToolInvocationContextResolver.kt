package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.springframework.stereotype.Service

/** Prepared invocation, not a permission grant or a durable snapshot. No tool has run yet. */
data class ResolvedToolInvocation(
    val input: AiToolInvocationInput,
    val context: ToolExecutionContext,
    val target: ConversationRuntimeTaskTarget,
)

/**
 * Server-side context selection for an authenticated entry point without a pre-bound Conversation.
 * Authenticated user, call identity and memory namespace come from trusted transport/runtime bindings.
 * Resource selection is read-only. Operation authorization, agent/tool policy, approval and dispatch
 * remain mandatory at the invoking entry point; this resolver does not expose a new HTTP catalog.
 */
@Service
class ToolInvocationContextResolver(
    private val users: UserDirectoryService,
    private val conversations: ConversationRepository,
    private val projects: ProjectAccessService,
    private val agents: AgentDomainService,
    private val workspaces: WorkspaceDomainService,
    private val workers: WorkerAccessService,
    private val coordinator: ConversationRuntimeCoordinator,
) {
    suspend fun resolve(
        descriptor: AiToolDescriptor,
        arguments: JsonElement,
        authenticatedUserId: User.Id,
        bindings: ToolExecutionContext = ToolExecutionContext(),
    ): ResolvedToolInvocation {
        bindings.cancellationSignal?.throwIfCancellationRequested()
        val actor = users.findActiveById(authenticatedUserId) ?: error("Active authenticated user required")
        var context = bindings
        fun bind(key: String, value: String) {
            context.getString(key)?.let { require(it == value) { "Invocation context conflicts with trusted binding" } }
            context = context.withValue(key, value)
        }
        bind(TOOL_CONTEXT_USER_ID, actor.id.value)
        require(descriptor.metadata.accessPolicy != AiToolAccessPolicy.SERVER_OWNER || actor.role == User.Role.OWNER) {
            "Server owner permission is required"
        }
        val input = descriptor.metadata.parseInvocationInput(arguments, context)
        input.selectors.forEach { (field, value) -> bind(field.contextKey, value) }
        val fields = descriptor.metadata.contextRequirements.fields
        val needsConversation = AiToolContextField.CONVERSATION in fields || AiToolContextField.THREAD in fields
        val conversation = context.getString(TOOL_CONTEXT_CONVERSATION_ID)?.takeIf { needsConversation }?.let { id ->
            val value = conversations.findById(Conversation.Id(id)) ?: error("Conversation unavailable")
            require(Conversation.Participant.User(actor.id) in value.participants) { "Conversation unavailable" }
            bind(TOOL_CONTEXT_PROJECT_ID, value.projectId.value)
            bind(TOOL_CONTEXT_THREAD_ID, value.currentThread.value)
            value
        }
        fun businessId(name: String): String {
            val value = input.arguments[name] as? JsonPrimitive
            require(value != null && value.isString && value.content.isNotBlank()) { "$name is required" }
            return value.content
        }
        var selectedMount: WorkspaceExecutionContext? = null
        val target = when (descriptor.metadata.executionScope) {
            AiToolExecutionScope.SERVER -> ConversationRuntimeTaskTarget.Server
            AiToolExecutionScope.WORKER -> ConversationRuntimeTaskTarget.Worker(
                checkNotNull(input.executionTarget?.workerId), requestPolicy = input.executionTarget?.requestPolicy)
            AiToolExecutionScope.WORKSPACE -> {
                val mount = workspaces.resolveExecution(checkNotNull(input.executionTarget?.workspaceMountId))
                selectedMount = mount
                ConversationRuntimeTaskTarget.Worker(ConversationRuntimeWorkerId(mount.mount.workerId), mount.mount.id,
                    requestPolicy = input.executionTarget?.requestPolicy)
            }
            AiToolExecutionScope.COMMAND_TASK_OWNER -> {
                val task = coordinator.findCommandTask(checkNotNull(conversation).id, CommandTask.Id(businessId("task_id")))
                    ?: error("Command task unavailable")
                ConversationRuntimeTaskTarget.Worker(task.workerId, task.workspaceMountId)
            }
            AiToolExecutionScope.COMMAND_MONITOR_OWNER -> {
                val monitor = coordinator.findCommandMonitor(checkNotNull(conversation).id, CommandMonitor.Id(businessId("monitor_id")))
                    ?: error("Command monitor unavailable")
                ConversationRuntimeTaskTarget.Worker(monitor.workerId, monitor.workspaceMountId)
            }
        }
        if (target is ConversationRuntimeTaskTarget.Worker) {
            bind(TOOL_CONTEXT_WORKER_ID, target.workerId.value)
            target.workspaceMountId?.let { id ->
                val execution = selectedMount ?: workspaces.resolveExecution(id)
                require(execution.mount.workerId == target.workerId.value) { "Workspace Worker mismatch" }
                bind(TOOL_CONTEXT_PROJECT_ID, execution.project.id.value)
                bind(TOOL_CONTEXT_WORKSPACE_ID, execution.workspace.id.value)
                bind(TOOL_CONTEXT_WORKSPACE_MOUNT_ID, execution.mount.id.value)
                bind(TOOL_CONTEXT_WORKSPACE_ROOT_PATH, execution.mount.rootPath)
            }
        }
        val projectId = context.getString(TOOL_CONTEXT_PROJECT_ID)
            ?.takeIf { AiToolContextField.PROJECT in fields || conversation != null || target is ConversationRuntimeTaskTarget.Worker }
            ?.let(Project::Id)
        projectId?.let {
            projects.requirePermission(actor.id, it,
                if (target is ConversationRuntimeTaskTarget.Worker) ProjectPermission.WRITE else ProjectPermission.READ)
        }
        if (target is ConversationRuntimeTaskTarget.Worker) {
            workers.requirePermission(actor, target.workerId, WorkerPermission.USE, projectId)
            projectId?.let { workers.requireProjectAccess(target.workerId, it) }
        }
        context.getString(TOOL_CONTEXT_AGENT_DEFINITION_ID)?.takeIf { AiToolContextField.AGENT in fields }?.let { id ->
            val agent = agents.findById(AgentDefinition.Id(id)) ?: error("Agent unavailable")
            require(agent.type is AgentDefinition.Type.Global || agent.projectId == projectId) { "Agent unavailable" }
            require(conversation == null || Conversation.Participant.Agent(agent.id) in conversation.participants) { "Agent unavailable" }
        }
        // No guessed agent, workspace, target message, memory bank or random idempotency identity.
        descriptor.metadata.requireInvocationContext(context)
        context.cancellationSignal?.throwIfCancellationRequested()
        return ResolvedToolInvocation(input, context, target)
    }
}
