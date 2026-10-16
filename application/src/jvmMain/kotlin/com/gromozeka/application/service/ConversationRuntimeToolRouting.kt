package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.service.WorkerAccessDeniedException
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolExecutionTarget
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_THREAD_ID
import com.gromozeka.domain.tool.parseInvocationInput
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.springframework.stereotype.Service

sealed interface ConversationRuntimeToolRoutingResult {
    data class Accepted(
        val executionTargetsByCallId: Map<String, ConversationRuntimeTaskTarget>,
        val returnDirect: Boolean,
        val executionToolNamesByCallId: Map<String, String> = emptyMap(),
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
    private val workerAccessService: WorkerAccessService,
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
                    "Tool '${toolCall.call.name}' is not available in the current runtime."
                )
                return@forEach
            }

            try {
                entry.descriptor.metadata.parseInvocationInput(toolCall.call.input, ToolExecutionContext(mapOf(
                    TOOL_CONTEXT_CONVERSATION_ID to conversation.id.value,
                    TOOL_CONTEXT_PROJECT_ID to project.id.value,
                    TOOL_CONTEXT_THREAD_ID to conversation.currentThread.value,
                )))
            } catch (error: IllegalArgumentException) {
                errors += toolCall.routingError(error.message ?: "Invalid invocation context")
                return@forEach
            } catch (error: IllegalStateException) {
                errors += toolCall.routingError(error.message ?: "Invalid invocation context")
                return@forEach
            }
            val target = when (entry.descriptor.metadata.executionScope) {
                AiToolExecutionScope.SERVER -> ConversationRuntimeTaskTarget.Server
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
                AiToolExecutionScope.COMMAND_MONITOR_OWNER -> resolveCommandMonitorTarget(
                    conversation = conversation,
                    project = project,
                    toolCall = toolCall,
                    entry = entry,
                    errors = errors,
                )
            } ?: return@forEach

            if (target is ConversationRuntimeTaskTarget.Worker) {
                try {
                    workerAccessService.requireProjectAccess(target.workerId, project.id)
                } catch (_: WorkerAccessDeniedException) {
                    errors += toolCall.routingError(
                        "Worker '${target.workerId.value}' is not available to project '${project.id.value}'."
                    )
                    return@forEach
                }
            }

            resolved += ResolvedToolCall(
                toolCallId = toolCall.id,
                modelName = toolCall.call.name,
                executionName = entry.executionName,
                target = target,
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

        return ConversationRuntimeToolRoutingResult.Accepted(
            executionTargetsByCallId = resolved.associate { it.toolCallId.value to it.target },
            returnDirect = resolved.all { it.returnDirect },
            executionToolNamesByCallId = resolved
                .filter { it.modelName != it.executionName }
                .associate { it.toolCallId.value to it.executionName },
        )
    }

    private fun resolveWorkerTarget(
        toolCall: Conversation.Message.ContentItem.ToolCall,
        entry: DistributedAiTool,
        errors: MutableList<ConversationRuntimeToolRoutingError>,
    ): ConversationRuntimeTaskTarget? {
        val requested = parseTarget(toolCall, entry, errors) ?: return null
        val workerId = requested.workerId
        if (workerId == null) {
            errors += toolCall.routingError(
                "Tool '${toolCall.call.name}' is worker-scoped and requires worker_id."
            )
            return null
        }
        if (entry.workers.none { it.workerId == workerId }) {
            errors += toolCall.routingError(
                "Worker '${workerId.value}' does not advertise tool '${toolCall.call.name}'."
            )
            return null
        }
        return ConversationRuntimeTaskTarget.Worker(workerId = workerId, requestPolicy = requested.requestPolicy)
    }

    private suspend fun resolveMountTarget(
        conversation: Conversation,
        project: Project,
        toolCall: Conversation.Message.ContentItem.ToolCall,
        entry: DistributedAiTool,
        errors: MutableList<ConversationRuntimeToolRoutingError>,
    ): ConversationRuntimeTaskTarget? {
        val requested = parseTarget(toolCall, entry, errors) ?: return null
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
                "Workspace mount '${mountId.value}' does not advertise " +
                    "tool '${toolCall.call.name}'."
            )
            return null
        }
        val workspace = validateProjectMount(conversation, project, toolCall, mount, errors) ?: return null
        return ConversationRuntimeTaskTarget.Worker(
            workerId = com.gromozeka.domain.service.ConversationRuntimeWorkerId(mount.workerId),
            workspaceMountId = mount.id,
            requestPolicy = requested.requestPolicy,
        )
    }

    private suspend fun resolveCommandTaskTarget(
        conversation: Conversation,
        project: Project,
        toolCall: Conversation.Message.ContentItem.ToolCall,
        entry: DistributedAiTool,
        errors: MutableList<ConversationRuntimeToolRoutingError>,
    ): ConversationRuntimeTaskTarget? {
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
                "Worker '${commandTask.workerId.value}' that owns command task '$taskId' is unavailable or does not " +
                    "advertise tool '${toolCall.call.name}'."
            )
            return null
        }
        validateProjectMount(conversation, project, toolCall, mount, errors) ?: return null
        return ConversationRuntimeTaskTarget.Worker(
            workerId = commandTask.workerId,
            workspaceMountId = commandTask.workspaceMountId,
        )
    }

    private suspend fun resolveCommandMonitorTarget(
        conversation: Conversation,
        project: Project,
        toolCall: Conversation.Message.ContentItem.ToolCall,
        entry: DistributedAiTool,
        errors: MutableList<ConversationRuntimeToolRoutingError>,
    ): ConversationRuntimeTaskTarget? {
        val monitorId = (toolCall.call.input.jsonObject["monitor_id"] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        if (monitorId == null) {
            errors += toolCall.routingError("Tool '${toolCall.call.name}' requires a non-empty monitor_id.")
            return null
        }
        val monitor = runtimeCoordinator.findCommandMonitor(conversation.id, CommandMonitor.Id(monitorId))
        if (monitor == null) {
            errors += toolCall.routingError(
                "Command monitor '$monitorId' was not found in conversation '${conversation.id.value}'."
            )
            return null
        }
        val mount = workspaceService.findMount(monitor.workspaceMountId)
        if (mount == null) {
            errors += toolCall.routingError(
                "Workspace mount '${monitor.workspaceMountId.value}' for command monitor '$monitorId' no longer exists."
            )
            return null
        }
        if (mount.workerId != monitor.workerId.value) {
            errors += toolCall.routingError(
                "Command monitor '$monitorId' has inconsistent worker and workspace mount ownership."
            )
            return null
        }
        if (entry.workers.none { it.workerId == monitor.workerId }) {
            errors += toolCall.routingError(
                "Worker '${monitor.workerId.value}' that owns command monitor '$monitorId' is unavailable or does not " +
                    "advertise tool '${toolCall.call.name}'."
            )
            return null
        }
        validateProjectMount(conversation, project, toolCall, mount, errors) ?: return null
        return ConversationRuntimeTaskTarget.Worker(
            workerId = monitor.workerId,
            workspaceMountId = monitor.workspaceMountId,
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
        entry: DistributedAiTool,
        errors: MutableList<ConversationRuntimeToolRoutingError>,
    ): AiToolExecutionTarget? = runCatching { entry.descriptor.metadata.parseInvocationInput(toolCall.call.input).executionTarget }
        .getOrElse { error ->
            errors += toolCall.routingError(error.message ?: "Invalid execution target.")
            null
        }

    private data class ResolvedToolCall(
        val toolCallId: Conversation.Message.ContentItem.ToolCall.Id,
        val modelName: String,
        val executionName: String,
        val target: ConversationRuntimeTaskTarget,
        val returnDirect: Boolean,
    )
}

private fun Conversation.Message.ContentItem.ToolCall.routingError(
    message: String,
): ConversationRuntimeToolRoutingError =
    ConversationRuntimeToolRoutingError(
        toolCallId = id,
        toolName = call.name,
        message = message,
    )
