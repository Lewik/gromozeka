package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolExecutionTarget
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Service

sealed interface ConversationRuntimeToolRoutingResult {
    data class Accepted(
        val requirements: ConversationRuntimeTaskRequirements,
        val returnDirect: Boolean,
    ) : ConversationRuntimeToolRoutingResult

    data class Rejected(
        val errors: List<ConversationRuntimeToolRoutingError>,
    ) : ConversationRuntimeToolRoutingResult {
        init {
            require(errors.isNotEmpty()) { "Rejected tool routing must contain at least one error" }
        }
    }
}

data class ConversationRuntimeToolRoutingError(
    val toolCallId: Conversation.Message.ContentItem.ToolCall.Id,
    val toolName: String,
    val message: String,
)

@Service
class ConversationRuntimeToolRoutingService(
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val workspaceService: WorkspaceDomainService,
) {
    suspend fun route(
        conversation: Conversation,
        project: Project,
        toolCalls: List<Conversation.Message.ContentItem.ToolCall>,
        catalog: DistributedAiToolCatalogSnapshot,
        runtimeWorkerId: ConversationRuntimeWorkerId? = null,
    ): ConversationRuntimeToolRoutingResult {
        val resolved = mutableListOf<ResolvedToolCall>()
        val errors = mutableListOf<ConversationRuntimeToolRoutingError>()

        toolCalls.forEach { toolCall ->
            val entry = catalog.entries[toolCall.call.name]
            if (entry == null) {
                errors += toolCall.routingError(
                    "Tool '${toolCall.call.name}' is not advertised by any online worker."
                )
                return@forEach
            }

            val target = when (entry.descriptor.metadata.executionScope) {
                AiToolExecutionScope.CONVERSATION_RUNTIME -> resolveRuntimeWorkerTarget(
                    toolCall = toolCall,
                    entry = entry,
                    runtimeWorkerId = runtimeWorkerId,
                    errors = errors,
                )
                AiToolExecutionScope.WORKER -> resolveWorkerTarget(toolCall, entry, errors)
                AiToolExecutionScope.WORKSPACE -> resolveMountTarget(
                    conversation = conversation,
                    project = project,
                    toolCall = toolCall,
                    entry = entry,
                    errors = errors,
                )
                AiToolExecutionScope.COMMAND_TASK_OWNER -> resolveCommandTaskTarget(
                    conversation = conversation,
                    project = project,
                    toolCall = toolCall,
                    entry = entry,
                    errors = errors,
                )
            } ?: return@forEach

            resolved += ResolvedToolCall(
                target = target,
                requiredCapabilities = entry.descriptor.metadata.requiredRuntimeCapabilities,
                returnDirect = entry.descriptor.metadata.returnDirect,
            )
        }

        if (errors.isNotEmpty()) {
            val errorsByCall = errors.groupBy { it.toolCallId }
            return ConversationRuntimeToolRoutingResult.Rejected(
                toolCalls.map { toolCall ->
                    errorsByCall[toolCall.id]
                        ?.joinToString(separator = "\n") { it.message }
                        ?.let { toolCall.routingError(it) }
                        ?: toolCall.routingError(
                            "This tool call was not executed because another call in the same batch " +
                                "has an invalid execution target."
                        )
                }
            )
        }

        val workerIds = resolved.mapTo(mutableSetOf()) { it.target.workerId }
        val mountIds = resolved.mapNotNullTo(mutableSetOf()) { it.target.workspaceMountId }
        if (workerIds.size != 1 || mountIds.size > 1) {
            val message =
                "One assistant response can currently execute tools on only one exact worker/mount target. " +
                    "Issue calls for different targets in separate assistant responses."
            return ConversationRuntimeToolRoutingResult.Rejected(
                toolCalls.map { it.routingError(message) }
            )
        }

        val capabilities = buildSet {
            add(ConversationRuntimeWorkerCapability.TOOL_EXECUTION)
            resolved.forEach { addAll(it.requiredCapabilities) }
        }
        return ConversationRuntimeToolRoutingResult.Accepted(
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = capabilities,
                target = ConversationRuntimeTaskTarget(
                    workerId = workerIds.single(),
                    workspaceMountId = mountIds.singleOrNull(),
                ),
            ),
            returnDirect = resolved.all { it.returnDirect },
        )
    }

    private fun resolveRuntimeWorkerTarget(
        toolCall: Conversation.Message.ContentItem.ToolCall,
        entry: DistributedAiTool,
        runtimeWorkerId: ConversationRuntimeWorkerId?,
        errors: MutableList<ConversationRuntimeToolRoutingError>,
    ): ConversationRuntimeTaskTarget? {
        if (AI_TOOL_EXECUTION_TARGET_FIELD in toolCall.call.input.jsonObject) {
            errors += toolCall.routingError(
                "Tool '${toolCall.call.name}' executes in the current conversation runtime " +
                    "and must not declare execution_target."
            )
            return null
        }
        if (runtimeWorkerId == null) {
            errors += toolCall.routingError(
                "Current conversation runtime worker is unavailable for tool '${toolCall.call.name}'."
            )
            return null
        }
        if (entry.workers.none { it.workerId == runtimeWorkerId }) {
            errors += toolCall.routingError(
                "Current worker '${runtimeWorkerId.value}' does not advertise tool '${toolCall.call.name}'."
            )
            return null
        }
        return ConversationRuntimeTaskTarget(workerId = runtimeWorkerId)
    }

    private fun resolveWorkerTarget(
        toolCall: Conversation.Message.ContentItem.ToolCall,
        entry: DistributedAiTool,
        errors: MutableList<ConversationRuntimeToolRoutingError>,
    ): ConversationRuntimeTaskTarget? {
        val requested = parseTarget(toolCall, errors) ?: return null
        val workerId = requested.workerId
        if (workerId == null) {
            errors += toolCall.routingError(
                "Tool '${toolCall.call.name}' is worker-scoped and requires worker_id."
            )
            return null
        }
        if (entry.workers.none { it.workerId == workerId }) {
            errors += toolCall.routingError(
                "Worker '${workerId.value}' is not online or does not advertise tool '${toolCall.call.name}'."
            )
            return null
        }
        return ConversationRuntimeTaskTarget(workerId = workerId)
    }

    private suspend fun resolveMountTarget(
        conversation: Conversation,
        project: Project,
        toolCall: Conversation.Message.ContentItem.ToolCall,
        entry: DistributedAiTool,
        errors: MutableList<ConversationRuntimeToolRoutingError>,
    ): ConversationRuntimeTaskTarget? {
        val requested = parseTarget(toolCall, errors) ?: return null
        val mountId = requested.workspaceMountId
        if (mountId == null) {
            errors += toolCall.routingError(
                "Tool '${toolCall.call.name}' is workspace-scoped and requires workspace_mount_id."
            )
            return null
        }
        val mount = workspaceService.findMount(mountId)
        if (mount == null) {
            errors += toolCall.routingError("Workspace mount '${mountId.value}' does not exist.")
            return null
        }
        val advertised = entry.workers.any { worker ->
            worker.workerId.value == mount.workerId && worker.workspaceMounts.any { it.id == mount.id }
        }
        if (!advertised) {
            errors += toolCall.routingError(
                "Workspace mount '${mountId.value}' is offline or its worker does not advertise " +
                    "tool '${toolCall.call.name}'."
            )
            return null
        }
        val workspace = validateProjectMount(conversation, project, toolCall, mount, errors) ?: return null
        return ConversationRuntimeTaskTarget(
            workerId = com.gromozeka.domain.service.ConversationRuntimeWorkerId(mount.workerId),
            workspaceMountId = mount.id,
        )
    }

    private suspend fun resolveCommandTaskTarget(
        conversation: Conversation,
        project: Project,
        toolCall: Conversation.Message.ContentItem.ToolCall,
        entry: DistributedAiTool,
        errors: MutableList<ConversationRuntimeToolRoutingError>,
    ): ConversationRuntimeTaskTarget? {
        if (AI_TOOL_EXECUTION_TARGET_FIELD in toolCall.call.input.jsonObject) {
            errors += toolCall.routingError(
                "Tool '${toolCall.call.name}' routes by task_id and must not declare execution_target."
            )
            return null
        }
        val taskId = (toolCall.call.input.jsonObject["task_id"] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        if (taskId == null) {
            errors += toolCall.routingError("Tool '${toolCall.call.name}' requires a non-empty task_id.")
            return null
        }
        val commandTask = runtimeCoordinator.findCommandTask(conversation.id, CommandTask.Id(taskId))
        if (commandTask == null) {
            errors += toolCall.routingError(
                "Command task '$taskId' was not found in conversation '${conversation.id.value}'."
            )
            return null
        }
        val mount = workspaceService.findMount(commandTask.workspaceMountId)
        if (mount == null) {
            errors += toolCall.routingError(
                "Workspace mount '${commandTask.workspaceMountId.value}' for command task '$taskId' no longer exists."
            )
            return null
        }
        if (mount.workerId != commandTask.workerId.value) {
            errors += toolCall.routingError(
                "Command task '$taskId' has inconsistent worker and workspace mount ownership."
            )
            return null
        }
        if (entry.workers.none { it.workerId == commandTask.workerId }) {
            errors += toolCall.routingError(
                "Worker '${commandTask.workerId.value}' that owns command task '$taskId' is offline or does not " +
                    "advertise tool '${toolCall.call.name}'."
            )
            return null
        }
        validateProjectMount(conversation, project, toolCall, mount, errors) ?: return null
        return ConversationRuntimeTaskTarget(
            workerId = commandTask.workerId,
            workspaceMountId = commandTask.workspaceMountId,
        )
    }

    private suspend fun validateProjectMount(
        conversation: Conversation,
        project: Project,
        toolCall: Conversation.Message.ContentItem.ToolCall,
        mount: WorkspaceMount,
        errors: MutableList<ConversationRuntimeToolRoutingError>,
    ): Workspace? {
        val workspace = workspaceService.findById(mount.workspaceId)
        if (workspace == null) {
            errors += toolCall.routingError("Workspace '${mount.workspaceId.value}' does not exist.")
            return null
        }
        if (workspace.projectId != project.id || workspace.projectId != conversation.projectId) {
            errors += toolCall.routingError(
                "Workspace '${workspace.id.value}' belongs to project '${workspace.projectId.value}', " +
                    "while this conversation belongs to project '${conversation.projectId.value}'."
            )
            return null
        }
        return workspace
    }

    private fun parseTarget(
        toolCall: Conversation.Message.ContentItem.ToolCall,
        errors: MutableList<ConversationRuntimeToolRoutingError>,
    ): AiToolExecutionTarget? = runCatching { toolCall.call.input.parseExecutionTarget() }
        .getOrElse { error ->
            errors += toolCall.routingError(error.message ?: "Invalid execution target.")
            null
        }

    private data class ResolvedToolCall(
        val target: ConversationRuntimeTaskTarget,
        val requiredCapabilities: Set<ConversationRuntimeWorkerCapability>,
        val returnDirect: Boolean,
    )
}

fun JsonElement.parseExecutionTarget(): AiToolExecutionTarget {
    val input = this as? JsonObject
        ?: throw IllegalArgumentException("Tool input must be a JSON object.")
    val target = input[AI_TOOL_EXECUTION_TARGET_FIELD] as? JsonObject
        ?: throw IllegalArgumentException(
            "Tool input must contain object '$AI_TOOL_EXECUTION_TARGET_FIELD'."
        )
    val workerId = target[AI_TOOL_EXECUTION_WORKER_ID_FIELD]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(::ConversationRuntimeWorkerId)
    val workspaceMountId = target[AI_TOOL_EXECUTION_WORKSPACE_MOUNT_ID_FIELD]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(WorkspaceMount::Id)
    return AiToolExecutionTarget(
        workerId = workerId,
        workspaceMountId = workspaceMountId,
    )
}

fun JsonElement.withoutExecutionTarget(): JsonObject {
    val input = this as? JsonObject
        ?: throw IllegalArgumentException("Tool input must be a JSON object.")
    return JsonObject(input - AI_TOOL_EXECUTION_TARGET_FIELD)
}

private fun Conversation.Message.ContentItem.ToolCall.routingError(
    message: String,
): ConversationRuntimeToolRoutingError =
    ConversationRuntimeToolRoutingError(
        toolCallId = id,
        toolName = call.name,
        message = message,
    )
