package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.repository.AsyncToolOperationRepository
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.shared.uuid.uuid7
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service
import kotlin.time.Clock

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = ["gromozeka.runtime.server.enabled"], havingValue = "true", matchIfMissing = true)
class AsyncToolAdmissionService(
    private val operations: AsyncToolOperationRepository,
    private val scopes: AsyncToolScopeAccess,
    private val conversations: ConversationDomainService,
    private val agents: AgentDomainService,
    private val catalog: DistributedAiToolCatalog,
    private val contexts: ToolInvocationContextResolver,
    private val slots: SlotApplicationService,
    private val approvals: ToolApprovalService,
    private val runtime: ConversationRuntimeCoordinator,
    private val workers: AsyncWorkerToolExecutionClient,
    private val collaboration: AgentCollaborationService? = null,
) {
    data class Admission(val operationId: AsyncToolOperation.Id?, val receipt: ContentItem.ToolResult)
    data class Prepared(val call: ContentItem.ToolCall, val context: ToolExecutionContext)

    suspend fun admit(
        task: ConversationRuntimeTask,
        executor: ConversationRuntimeExecutorIdentity,
        payload: ConversationRuntimeTask.Payload.ToolExecution,
        modelCall: ContentItem.ToolCall,
        executionCall: ContentItem.ToolCall,
        target: ConversationRuntimeTaskTarget,
        context: ToolExecutionContext,
    ): Admission {
        require(task.actorUserId != null && task.actorUserId == context.requiredUserId()) { "Async admission requires its authenticated source actor" }
        require(modelCall.id == executionCall.id && payload.toolCalls.any { it.id == modelCall.id }) { "Async call is outside its source task" }
        check(runtime.confirmActiveTaskOwner(task.conversationId, task.id, executor)) { "Async admission lost its source task owner" }
        val existing = operations.findBySource(task.conversationId, task.id, executionCall.id)
        if (existing != null) {
            val origin = existing.invocation.origin
            require(origin.actorUserId == task.actorUserId && origin.agentDefinitionId == payload.agentDefinitionId &&
                origin.threadId.value == context.getString(TOOL_CONTEXT_THREAD_ID) && origin.projectId == context.requiredProjectId() &&
                origin.externalChannel == task.externalChannel && existing.invocation.modelToolName == modelCall.call.name &&
                existing.invocation.toolCall == executionCall && sameTarget(existing.invocation.target, target)) {
                "Async source call cannot be rebound after admission"
            }
            scopes.requireExecution(origin)
            return Admission(existing.id, receipt(existing))
        }
        val sourceConversation = requireNotNull(conversations.findById(task.conversationId)) { "Async conversation unavailable" }
        val source = AgentContextRef(task.conversationId, sourceConversation.currentThread, payload.agentDefinitionId)
        val mode = conversations.loadCurrentMessages(task.conversationId)
            .filter { it.author !is Conversation.Message.Author.Agent }.flatMap { it.instructions }
            .filterIsInstance<Conversation.Message.Instruction.UserInstruction>()
            .lastOrNull { it.id == "mode_readonly" || it.id == "mode_writable" }
        val inheritedReadOnly = collaboration?.readOnlyOrigin(source, checkNotNull(task.actorUserId), payload.rootUserMessageId) == true
        val readOnly = mode?.id == "mode_readonly" || inheritedReadOnly
        val origin = AsyncToolOrigin(
            actorUserId = checkNotNull(task.actorUserId), projectId = context.requiredProjectId(), conversationId = task.conversationId,
            threadId = Conversation.Thread.Id(requireNotNull(context.getString(TOOL_CONTEXT_THREAD_ID))),
            agentDefinitionId = payload.agentDefinitionId, sourceTaskId = task.id, sourceToolCallId = executionCall.id,
            acknowledgementMessageId = Conversation.Message.Id("${task.id.value}:result"), readOnly = readOnly,
            slotOrigin = (target as? ConversationRuntimeTaskTarget.Worker)?.workspaceMountId?.let {
                slots.captureAsyncOrigin(checkNotNull(task.actorUserId), task.conversationId, it)
            }, externalChannel = task.externalChannel,
        )
        scopes.requireExecution(origin)
        val entry = entry(origin, modelCall.call.name)
        entry.descriptor.metadata.inlineOnlyReason?.let { reason ->
            return Admission(null, ContentItem.ToolResult(modelCall.id, modelCall.call.name,
                listOf(ContentItem.ToolResult.Data.Text("Async invocation is unsupported: $reason. This tool was not executed.")), isError = true))
        }
        val executionDescriptor = catalog.executionDescriptor(entry, target)
        require(entry.executionName == executionCall.call.name) { "Async tool execution name differs from its approved contract" }
        val binding = context.withValue("toolCallId", executionCall.id.value)
        val resolved = contexts.resolve(entry.descriptor, executionCall.call.input, origin.actorUserId, binding)
        require(resolved.input.async && sameTarget(resolved.target, target)) { "Async requested route differs from its durable target" }
        val approval = approvals.approve(listOf(modelCall))
        if (approval is ApprovalResult.Rejected) return Admission(null, ContentItem.ToolResult(
            toolUseId = modelCall.id, toolName = modelCall.call.name,
            result = listOf(ContentItem.ToolResult.Data.Text("Tool rejected: ${approval.reason}")), isError = true))
        val captured = when (target) {
            ConversationRuntimeTaskTarget.Server -> target
            is ConversationRuntimeTaskTarget.Worker -> workers.captureTarget(target)
        }
        val values = resolved.context.asMap().mapValues { (key, value) ->
            require(value is String) { "Async tool context '$key' is not a durable string binding" }
            value
        }
        require(TOOL_CONTEXT_SECRET_ENVIRONMENT !in values) { "Async admission stores secret references, not resolved secret environments" }
        val invocation = AsyncToolInvocation(origin, modelCall.call.name, executionDescriptor.contractFingerprint(), executionCall, captured, values)
        check(runtime.confirmActiveTaskOwner(task.conversationId, task.id, executor)) { "Async admission lost its source task owner" }
        val operation = operations.admit(AsyncToolOperation.Id(uuid7()), invocation, Clock.System.now())
        return Admission(operation.id, receipt(operation))
    }

    suspend fun prepare(operation: AsyncToolOperation): Prepared {
        val invocation = operation.invocation
        val origin = invocation.origin
        scopes.requireExecution(origin)
        val entry = entry(origin, invocation.modelToolName)
        require(catalog.executionDescriptor(entry, invocation.target).contractFingerprint() == invocation.contractFingerprint && entry.executionName == invocation.toolCall.call.name) {
            "Async approved tool revision is no longer available"
        }
        val original = ToolExecutionContext(invocation.context, asyncOrigin = origin)
        val resolved = contexts.resolve(entry.descriptor, invocation.toolCall.call.input, origin.actorUserId, original)
        require(resolved.input.async && sameTarget(resolved.target, invocation.target)) { "Async execution target changed" }
        val capturedSlot = (invocation.target as? ConversationRuntimeTaskTarget.Worker)?.workspaceMountId?.let {
            slots.captureAsyncOrigin(origin.actorUserId, origin.conversationId, it)
        }
        require(capturedSlot == origin.slotOrigin) { "Async source slot occupation changed before execution" }
        val fingerprints = buildJsonObject { put(origin.sourceToolCallId.value, invocation.contractFingerprint) }.toString()
        val context = resolved.context.withValue(EXPECTED_TOOL_FINGERPRINTS_CONTEXT_KEY, fingerprints).copy(asyncOrigin = origin)
        return Prepared(invocation.toolCall.copy(call = invocation.toolCall.call.copy(input = resolved.input.arguments)), context)
    }

    suspend fun verifyWorkerDispatch(request: StoredWorkerRequest) {
        val reference = checkNotNull(request.asyncTool)
        val operation = checkNotNull(operations.find(reference.operationId)) { "Async Worker parent is missing" }
        val invocation = operation.invocation
        val origin = invocation.origin
        val target = invocation.target as? ConversationRuntimeTaskTarget.Worker ?: error("Async Worker request has a Server parent")
        check(request.id == operation.id.value && request.workerId == target.workerId && request.actorUserId == origin.actorUserId &&
            request.projectId == origin.projectId && request.slotOrigin == origin.slotOrigin) { "Async child differs from its immutable parent" }
        if (operation.phase.isTerminal || operation.cancelRequestedAt != null) throw AsyncToolScopeDeniedException("OPERATION_ENDED_OR_CANCELLED")
        scopes.requireExecution(origin)
        val contract = entry(origin, invocation.modelToolName)
        if (catalog.executionDescriptor(contract, target).contractFingerprint() != invocation.contractFingerprint || contract.executionName != invocation.toolCall.call.name) {
            throw AsyncToolScopeDeniedException("TOOL_REVISION_CHANGED")
        }
        val worker = contract.workers.singleOrNull { it.workerId == target.workerId }
            ?: throw AsyncToolScopeDeniedException("WORKER_CONTRACT_UNAVAILABLE")
        target.workspaceMountId?.let { id ->
            val mount = worker.workspaceMounts.singleOrNull { it.id == id }
                ?: throw AsyncToolScopeDeniedException("MOUNT_UNAVAILABLE")
            if (mount.rootPath != invocation.context[TOOL_CONTEXT_WORKSPACE_ROOT_PATH] ||
                mount.workspaceId.value != invocation.context[TOOL_CONTEXT_WORKSPACE_ID] ||
                !slots.sameAsyncOrigin(origin.actorUserId, origin.conversationId, id, origin.slotOrigin)) {
                throw AsyncToolScopeDeniedException("MOUNT_OR_LEASE_CHANGED")
            }
        }
    }

    private suspend fun entry(origin: AsyncToolOrigin, name: String): DistributedAiTool {
        val agent = agents.findById(origin.agentDefinitionId) ?: throw AsyncToolScopeDeniedException("AGENT_UNAVAILABLE")
        val project = conversations.getProject(origin.conversationId)
        require(project.id == origin.projectId) { "Async source project changed" }
        return catalog.snapshot(project, agent.toolAccess, checkNotNull(conversations.findById(origin.conversationId))).entries[name] ?: throw AsyncToolScopeDeniedException("TOOL_ACCESS_DENIED")
    }

    internal suspend fun recoverReceipt(source: ConversationRuntimeTask, agentId: AgentDefinition.Id,
        modelCall: ContentItem.ToolCall, threadId: Conversation.Thread.Id): ContentItem.ToolResult? {
        val operation = operations.findBySource(source.conversationId, source.id, modelCall.id) ?: return null
        val origin = operation.invocation.origin
        check(origin.actorUserId == source.actorUserId && origin.agentDefinitionId == agentId &&
            operation.invocation.modelToolName == modelCall.call.name && operation.invocation.toolCall.call.input == modelCall.call.input) {
            "Recovered async acknowledgement differs from its durable source"
        }
        if (origin.threadId != threadId || origin.externalChannel != source.externalChannel) return null
        return receipt(operation)
    }

    private fun receipt(operation: AsyncToolOperation): ContentItem.ToolResult = ContentItem.ToolResult(
        toolUseId = operation.invocation.origin.sourceToolCallId, toolName = operation.invocation.modelToolName,
        result = listOf(ContentItem.ToolResult.Data.Text(buildJsonObject {
            put("status", "ACCEPTED"); put("operation_id", operation.id.value)
            put("message", "Durably accepted, not completed. The source tool call is closed by this receipt. A correlated Runtime notification will report the outcome. Inspect or cancel this operation by ID; do not repeat the action to retrieve its result.")
        }.toString())),
    )

    private fun sameTarget(left: ConversationRuntimeTaskTarget, right: ConversationRuntimeTaskTarget): Boolean = when {
        left == ConversationRuntimeTaskTarget.Server && right == ConversationRuntimeTaskTarget.Server -> true
        left is ConversationRuntimeTaskTarget.Worker && right is ConversationRuntimeTaskTarget.Worker ->
            left.workerId == right.workerId && left.workspaceMountId == right.workspaceMountId
        else -> false
    }
}
