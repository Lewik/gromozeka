package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
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

            val target = runCatching { toolCall.call.input.parseExecutionTarget() }
                .getOrElse { error ->
                    errors += toolCall.routingError(error.message ?: "Invalid execution target.")
                    return@forEach
                }
            val worker = entry.workers.singleOrNull { it.workerId == target.workerId }
            if (worker == null) {
                errors += toolCall.routingError(
                    "Worker '${target.workerId.value}' is not online or does not advertise " +
                        "tool '${toolCall.call.name}'."
                )
                return@forEach
            }

            val workspace = validateWorkspaceTarget(
                conversation = conversation,
                project = project,
                toolCall = toolCall,
                target = target,
                scope = entry.descriptor.metadata.executionScope,
                advertisedWorkspaceIds = worker.workspaceIds,
                errors = errors,
            ) ?: if (entry.descriptor.metadata.executionScope == AiToolExecutionScope.WORKER) {
                null
            } else {
                return@forEach
            }

            if (entry.descriptor.metadata.executionScope == AiToolExecutionScope.COMMAND_TASK_OWNER) {
                val taskId = (toolCall.call.input.jsonObject["task_id"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                if (taskId == null) {
                    errors += toolCall.routingError(
                        "Tool '${toolCall.call.name}' requires a non-empty task_id."
                    )
                    return@forEach
                }
                val commandTask = runtimeCoordinator.findCommandTask(
                    conversation.id,
                    CommandTask.Id(taskId),
                )
                if (commandTask == null) {
                    errors += toolCall.routingError(
                        "Command task '$taskId' was not found in conversation '${conversation.id.value}'."
                    )
                    return@forEach
                }
                if (commandTask.workerId != target.workerId || commandTask.workspaceId != workspace?.id) {
                    errors += toolCall.routingError(
                        "Command task '$taskId' belongs to worker '${commandTask.workerId.value}' " +
                            "and workspace '${commandTask.workspaceId.value}', not to the requested target."
                    )
                    return@forEach
                }
            }

            resolved += ResolvedToolCall(
                target = target,
                workspace = workspace,
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
        val workspaceIds = resolved.mapNotNullTo(mutableSetOf()) { it.workspace?.id }
        if (workerIds.size != 1 || workspaceIds.size > 1) {
            val message =
                "One assistant response can currently execute tools on only one exact worker/workspace target. " +
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
                    workspaceId = workspaceIds.singleOrNull(),
                ),
            ),
            returnDirect = resolved.all { it.returnDirect },
        )
    }

    private suspend fun validateWorkspaceTarget(
        conversation: Conversation,
        project: Project,
        toolCall: Conversation.Message.ContentItem.ToolCall,
        target: AiToolExecutionTarget,
        scope: AiToolExecutionScope,
        advertisedWorkspaceIds: Set<Workspace.Id>,
        errors: MutableList<ConversationRuntimeToolRoutingError>,
    ): Workspace? {
        if (scope == AiToolExecutionScope.WORKER) {
            if (target.workspaceId != null) {
                errors += toolCall.routingError(
                    "Tool '${toolCall.call.name}' is worker-scoped and must not declare workspace_id."
                )
            }
            return null
        }

        val workspaceId = target.workspaceId
        if (workspaceId == null) {
            errors += toolCall.routingError(
                "Tool '${toolCall.call.name}' requires an exact filesystem workspace_id."
            )
            return null
        }
        if (workspaceId !in advertisedWorkspaceIds) {
            errors += toolCall.routingError(
                "Worker '${target.workerId.value}' does not mount workspace '${workspaceId.value}'."
            )
            return null
        }
        val workspace = workspaceService.findById(workspaceId)
        if (workspace == null) {
            errors += toolCall.routingError("Workspace '${workspaceId.value}' does not exist.")
            return null
        }
        if (workspace.projectId != project.id || workspace.projectId != conversation.projectId) {
            errors += toolCall.routingError(
                "Workspace '${workspaceId.value}' belongs to project '${workspace.projectId.value}', " +
                    "while this conversation belongs to project '${conversation.projectId.value}'."
            )
            return null
        }
        return workspace
    }

    private data class ResolvedToolCall(
        val target: AiToolExecutionTarget,
        val workspace: Workspace?,
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
        ?: throw IllegalArgumentException(
            "'$AI_TOOL_EXECUTION_TARGET_FIELD.$AI_TOOL_EXECUTION_WORKER_ID_FIELD' must be non-empty."
        )
    val workspaceId = target[AI_TOOL_EXECUTION_WORKSPACE_ID_FIELD]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(Workspace::Id)
    return AiToolExecutionTarget(
        workerId = ConversationRuntimeWorkerId(workerId),
        workspaceId = workspaceId,
    )
}

fun JsonElement.withoutExecutionTarget(): JsonObject {
    val input = this as? JsonObject
        ?: throw IllegalArgumentException("Tool input must be a JSON object.")
    require(AI_TOOL_EXECUTION_TARGET_FIELD in input) {
        "Tool input is missing '$AI_TOOL_EXECUTION_TARGET_FIELD'."
    }
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
