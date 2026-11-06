package com.gromozeka.server

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.BackgroundActivityOrigin
import com.gromozeka.domain.repository.BackgroundActivityOriginRepository
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.service.*
import com.gromozeka.domain.slot.SlotCommandOrigin
import com.gromozeka.domain.tool.*
import com.gromozeka.domain.tool.filesystem.GRZ_EXECUTE_COMMAND_TOOL_NAME
import com.gromozeka.remote.protocol.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.springframework.stereotype.Service
import kotlin.time.Clock

/** Initial admission precedes state publication. A final tool response is not an authority source. */
@Service
class BackgroundActivityOriginRecorder(
    private val origins: BackgroundActivityOriginRepository,
    private val requests: WorkerRequestService,
    private val conversations: ConversationRepository,
) {
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun admit(task: CommandTask) = admit(Launch(
        BackgroundActivityOrigin.Kind.COMMAND, task.id.value, task.conversationId, task.agentDefinitionId,
        task.workerId, task.workspaceMountId, task.slotOrigin, requireNotNull(task.launchReference) {
            "New command registration requires its creating Worker request"
        }, task.command, visualId = task.visualId,
    ))

    suspend fun admit(monitor: CommandMonitor) = admit(Launch(
        BackgroundActivityOrigin.Kind.MONITOR, monitor.id.value, monitor.conversationId, monitor.agentDefinitionId,
        monitor.workerId, monitor.workspaceMountId, monitor.slotOrigin, requireNotNull(monitor.launchReference) {
            "New monitor registration requires its creating Worker request"
        }, monitor.filterCommand, sourceTaskId = monitor.commandTaskId,
    ))

    private suspend fun admit(launch: Launch) {
        val key = BackgroundActivityOrigin.Key(launch.kind, launch.id)
        origins.find(launch.conversationId, setOf(key))[key]?.let { existing ->
            require(existing.workerId == launch.workerId && existing.workspaceMountId == launch.mountId &&
                existing.agentDefinitionId == launch.agentId && existing.workerRequestId == launch.reference.workerRequestId &&
                existing.toolCallId == launch.reference.toolCallId) { "Background activity launch origin cannot change" }
            return
        }
        val record = requireNotNull(requests.find(launch.reference.workerRequestId)) {
            "Background activity launch request is no longer retained"
        }
        val actor = requireNotNull(record.actorUserId) { "Background activity launch has no authenticated actor" }
        require(record.workerId == launch.workerId) { "Background activity launch belongs to another Worker" }
        val conversation = requireNotNull(conversations.findById(launch.conversationId)) { "Launch conversation no longer exists" }
        require(record.projectId == conversation.projectId) { "Background activity launch belongs to another project" }
        val request = WorkerGatewayCodec.decode(record.request) as WorkerGatewayMessage.Request
        require(request.id == record.id) { "Stored Worker request identity mismatch" }
        when (request.operation) {
            WorkerGatewayOperation.TOOL_EXECUTION -> validateTool(launch, record, request)
            WorkerGatewayOperation.VISUAL_COMMAND -> validateVisual(launch, record, request)
            else -> error("Worker request does not create a background activity")
        }
        // This repository operation locks the RPC row and binds in the same transaction as its
        // pending check. A concurrent terminal CAS either wins before admission or follows it.
        origins.bindWhileRequestPending(BackgroundActivityOrigin(
            launch.kind, launch.id, launch.conversationId, launch.agentId, actor, record.workerId,
            launch.mountId, record.id, launch.reference.toolCallId,
        ), Clock.System.now())
    }

    private fun validateTool(launch: Launch, record: StoredWorkerRequest, request: WorkerGatewayMessage.Request) {
        require(launch.visualId == null) { "A tool call cannot impersonate a Visual handler" }
        val toolRequest = json.decodeFromString<WorkerToolExecutionRequest>(request.payload.decodeToString())
        validateContext(launch, record, toolRequest.toolContext)
        require(toolRequest.executionTarget.workerId == launch.workerId &&
            toolRequest.executionTarget.workspaceMountId == launch.mountId) { "Launch execution target does not match its request" }
        require(toolRequest.toolCalls.map { it.id }.distinct().size == toolRequest.toolCalls.size) { "Duplicate tool call identity" }
        val callId = requireNotNull(launch.reference.toolCallId) { "Tool launch requires a tool call id" }
        val call = toolRequest.toolCalls.singleOrNull { it.id.value == callId }
            ?: error("Unknown background activity tool call")
        val expectedTool = when (launch.kind) {
            BackgroundActivityOrigin.Kind.COMMAND -> GRZ_EXECUTE_COMMAND_TOOL_NAME
            BackgroundActivityOrigin.Kind.MONITOR -> "grz_monitor_command"
        }
        require(call.call.name == expectedTool) { "Tool call does not create this activity kind" }
        val input = call.call.input as? JsonObject ?: error("Launch tool input must be an object")
        fun text(name: String) = (input[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val commandKey = if (launch.kind == BackgroundActivityOrigin.Kind.COMMAND) "command" else "filter_command"
        require(text(commandKey) == launch.command) { "Background activity command does not match its request" }
        if (launch.kind == BackgroundActivityOrigin.Kind.MONITOR) {
            require(text("task_id") == launch.sourceTaskId?.value) { "Monitor request names another source command" }
        } else {
            require(record.slotOrigin == launch.slotOrigin) { "Command launch changed its slot origin" }
        }
    }

    private fun validateVisual(launch: Launch, record: StoredWorkerRequest, request: WorkerGatewayMessage.Request) {
        val command = json.decodeFromString<VisualWorkerCommand>(request.payload.decodeToString())
        require(launch.kind == BackgroundActivityOrigin.Kind.COMMAND && launch.reference.toolCallId == null &&
            command.operation == VisualWorkerCommand.Operation.START && command.visualId == launch.visualId &&
            command.conversationId == launch.conversationId && command.worker.workerId == launch.workerId &&
            command.workspace?.mount?.id == launch.mountId) { "Visual launch does not match its request" }
        validateContext(launch, record, command.toolContext)
        require(command.toolContext[TOOL_CONTEXT_VISUAL_ID] == launch.visualId && record.slotOrigin == launch.slotOrigin &&
            command.spec?.command == launch.command) {
            "Visual launch changed its command or origin"
        }
    }

    private fun validateContext(launch: Launch, record: StoredWorkerRequest, context: Map<String, String>) {
        require(context[TOOL_CONTEXT_USER_ID] == record.actorUserId?.value &&
            context[TOOL_CONTEXT_CONVERSATION_ID] == launch.conversationId.value &&
            context[TOOL_CONTEXT_PROJECT_ID] == record.projectId?.value &&
            context[TOOL_CONTEXT_AGENT_DEFINITION_ID] == launch.agentId?.value) {
            "Background activity request context does not match its Server-owned scope"
        }
    }

    private data class Launch(
        val kind: BackgroundActivityOrigin.Kind,
        val id: String,
        val conversationId: Conversation.Id,
        val agentId: AgentDefinition.Id?,
        val workerId: ConversationRuntimeWorkerId,
        val mountId: WorkspaceMount.Id,
        val slotOrigin: SlotCommandOrigin?,
        val reference: BackgroundActivityLaunchReference,
        val command: String,
        val visualId: String? = null,
        val sourceTaskId: CommandTask.Id? = null,
    )
}
