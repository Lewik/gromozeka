package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.WorkerToolExecutionClient
import com.gromozeka.domain.service.WorkerToolExecutionResult
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerToolExecutionRequest
import com.gromozeka.remote.protocol.WorkerToolExecutionResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class GatewayWorkerToolExecutionClient(
    private val requests: WorkerRequestService,
    @Value("\${gromozeka.runtime.tool-execution.timeout-millis:1800000}")
    timeoutMillis: Long,
    private val slots: com.gromozeka.application.service.SlotApplicationService,
    private val workers: com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry,
) : WorkerToolExecutionClient {
    private val timeout = Duration.ofMillis(timeoutMillis)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    init {
        require(timeoutMillis > 0) { "Worker tool execution timeout must be positive" }
    }

    override suspend fun execute(
        target: ConversationRuntimeWorkerIdentity,
        executionTarget: ConversationRuntimeTaskTarget.Worker,
        toolCalls: List<Conversation.Message.ContentItem.ToolCall>,
        toolContext: ToolExecutionContext,
        resolvedSecretsByToolCallId: Map<String, Map<String, String>>,
    ): WorkerToolExecutionResult {
        val actor = toolContext.getString(com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID)?.let(com.gromozeka.domain.model.User::Id)
        val conversation = toolContext.getString(com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID)?.let(Conversation::Id)
        val isLaunch = toolCalls.any { it.call.name == com.gromozeka.domain.tool.filesystem.GRZ_EXECUTE_COMMAND_TOOL_NAME }
        val origin = if (isLaunch && actor != null && conversation != null && executionTarget.workspaceMountId != null)
            slots.origin(actor, conversation, requireNotNull(executionTarget.workspaceMountId)) else null
        if (origin != null) require(workers.find(target.workerId)?.tools?.any {
            it.definition.name == com.gromozeka.domain.tool.filesystem.GRZ_EXECUTE_COMMAND_TOOL_NAME && it.metadata.supportsSlotContext
        } == true) { "Update this Worker before launching slot-associated commands; it does not support GRZ_SLOT/provenance yet" }
        val trustedContext = toolContext.asMap().minus(com.gromozeka.domain.slot.TOOL_CONTEXT_SLOT_ORIGIN).toMutableMap()
        origin?.let { trustedContext[com.gromozeka.domain.slot.TOOL_CONTEXT_SLOT_ORIGIN] = json.encodeToString(it) }
        val request = WorkerToolExecutionRequest(
            executionTarget = executionTarget,
            toolCalls = toolCalls,
            toolContext = trustedContext.mapValues { (key, value) ->
                require(value is String) {
                    "Worker tool context '$key' must be a string"
                }
                value
            },
            resolvedSecretsByToolCallId = resolvedSecretsByToolCallId,
        )
        val response = requests.execute(
            workerId = target.workerId,
            operation = WorkerGatewayOperation.TOOL_EXECUTION,
            slotOrigin = origin,
            payload = json.encodeToString(request).encodeToByteArray(),
            policy = executionTarget.requestPolicy ?: com.gromozeka.domain.service.WorkerRequestPolicy(executionTimeoutMillis = timeout.toMillis()),
            actorUserId = toolContext.getString(com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID)?.let(com.gromozeka.domain.model.User::Id),
            projectId = toolContext.getString(com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID)?.let(com.gromozeka.domain.model.Project::Id),
        ).let {
            json.decodeFromString<WorkerToolExecutionResponse>(it.decodeToString())
        }
        return WorkerToolExecutionResult(
            results = response.results,
            returnDirect = response.returnDirect,
        )
    }
}
