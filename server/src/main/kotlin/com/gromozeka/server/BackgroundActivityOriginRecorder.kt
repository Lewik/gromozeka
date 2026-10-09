package com.gromozeka.server

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.repository.BackgroundActivityOrigin
import com.gromozeka.domain.repository.BackgroundActivityOriginRepository
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.StoredWorkerRequest
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.domain.tool.filesystem.GRZ_EXECUTE_COMMAND_TOOL_NAME
import com.gromozeka.remote.protocol.WorkerGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerToolExecutionRequest
import com.gromozeka.remote.protocol.WorkerToolExecutionResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.springframework.stereotype.Service

/** Bind authority from a durable Server request, never from Worker-provided user fields. */
@Service
class BackgroundActivityOriginRecorder(
    private val origins: BackgroundActivityOriginRepository,
    private val coordinator: ConversationRuntimeCoordinator,
    private val conversations: ConversationRepository,
) : WorkerRequestCompletionObserver {
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun completed(record: StoredWorkerRequest, response: WorkerGatewayMessage.Response) {
        if (response.status != WorkerGatewayMessage.Response.Status.SUCCEEDED) return
        val actor = record.actorUserId ?: return
        val request = WorkerGatewayCodec.decode(record.request) as WorkerGatewayMessage.Request
        if (request.operation != WorkerGatewayOperation.TOOL_EXECUTION) return
        val toolRequest = json.decodeFromString<WorkerToolExecutionRequest>(request.payload.decodeToString())
        if (toolRequest.toolCalls.none { it.call.name in setOf(GRZ_EXECUTE_COMMAND_TOOL_NAME, "grz_monitor_command") }) return
        val context = toolRequest.toolContext
        val agent = context[TOOL_CONTEXT_AGENT_DEFINITION_ID]?.let(AgentDefinition::Id) ?: return
        val conversationId = context[TOOL_CONTEXT_CONVERSATION_ID]?.let(Conversation::Id) ?: return
        val conversation = conversations.findById(conversationId) ?: return // Deleted while executing.
        require(record.projectId == conversation.projectId && context[TOOL_CONTEXT_PROJECT_ID] == conversation.projectId.value) {
            "Background activity request belongs to another project"
        }
        require(context[TOOL_CONTEXT_USER_ID] == actor.value && toolRequest.executionTarget.workerId == record.workerId) {
            "Background activity request actor or Worker does not match its Server envelope"
        }
        val mount = requireNotNull(toolRequest.executionTarget.workspaceMountId) { "Background activity requires an exact mount" }
        val calls = toolRequest.toolCalls.associateBy { it.id }
        require(calls.size == toolRequest.toolCalls.size) { "Duplicate tool-call identity" }
        val result = json.decodeFromString<WorkerToolExecutionResponse>(requireNotNull(response.payload).decodeToString())
        for (item in result.results) {
            val call = requireNotNull(calls[item.toolUseId]) { "Worker returned an unknown tool-call identity" }
            if (item.isError) continue
            val kind = when (call.call.name) {
                GRZ_EXECUTE_COMMAND_TOOL_NAME -> BackgroundActivityOrigin.Kind.COMMAND
                "grz_monitor_command" -> BackgroundActivityOrigin.Kind.MONITOR
                else -> continue
            }
            require(item.toolName == call.call.name) { "Worker result tool does not match its request" }
            val metadata = item.result.filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.Text>()
                .firstOrNull()?.content?.let { json.parseToJsonElement(it) as? JsonObject } ?: continue
            val field = if (kind == BackgroundActivityOrigin.Kind.COMMAND) "task_id" else "monitor_id"
            val id = (metadata[field] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: continue
            when (kind) {
                BackgroundActivityOrigin.Kind.COMMAND -> {
                    val task = coordinator.findCommandTask(conversationId, CommandTask.Id(id)) ?: continue
                    require(task.workerId == record.workerId && task.workspaceMountId == mount && task.agentDefinitionId == agent && task.slotOrigin == record.slotOrigin) {
                        "Command result does not belong to its creating request"
                    }
                }
                BackgroundActivityOrigin.Kind.MONITOR -> {
                    val monitor = coordinator.findCommandMonitor(conversationId, CommandMonitor.Id(id)) ?: continue
                    require(monitor.workerId == record.workerId && monitor.workspaceMountId == mount && monitor.agentDefinitionId == agent) {
                        "Monitor result does not belong to its creating request"
                    }
                    val sourceId = ((call.call.input as? JsonObject)?.get("task_id") as? JsonPrimitive)?.contentOrNull
                    require(monitor.commandTaskId.value == sourceId) { "Monitor result has another source command" }
                }
            }
            origins.bind(BackgroundActivityOrigin(kind, id, conversationId, agent, actor, record.workerId,
                WorkspaceMount.Id(mount.value), record.id, call.id.value))
        }
    }
}
