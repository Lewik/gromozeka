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
    private val asyncProjector: AsyncWorkerResultProjector? = null,
) : WorkerToolExecutionClient, com.gromozeka.domain.service.AsyncWorkerToolExecutionClient {
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
        val isLaunch = toolCalls.any { com.gromozeka.domain.tool.filesystem.isManagedCommandLaunchTool(it.call.name) }
        val origin = if (isLaunch && actor != null && conversation != null && executionTarget.workspaceMountId != null)
            slots.origin(actor, conversation, requireNotNull(executionTarget.workspaceMountId)) else null
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
            structuredContentByCallId = response.structuredContentByCallId,
        )
    }
    override fun captureTarget(target: ConversationRuntimeTaskTarget.Worker): ConversationRuntimeTaskTarget.Worker =
        target.copy(requestPolicy = target.requestPolicy ?: com.gromozeka.domain.service.WorkerRequestPolicy(executionTimeoutMillis = timeout.toMillis()))

    override suspend fun submit(operation: com.gromozeka.domain.model.AsyncToolOperation, resolvedSecretsByToolCallId: Map<String, Map<String, String>>) {
        val invocation = operation.invocation
        val origin = invocation.origin
        val target = invocation.target as? ConversationRuntimeTaskTarget.Worker ?: error("Async Worker target required")
        val reference = com.gromozeka.domain.model.AsyncToolDispatchReference(operation.id, checkNotNull(operation.executorSessionId))
        check(operation.phase == com.gromozeka.domain.model.AsyncToolOperation.Phase.CLAIMED && operation.acknowledgedAt != null) {
            "Async Worker submission requires its acknowledged claim"
        }
        val arguments = invocation.toolCall.call.input as? kotlinx.serialization.json.JsonObject ?: error("Async tool input is not an object")
        val call = invocation.toolCall.copy(call = invocation.toolCall.call.copy(input = kotlinx.serialization.json.JsonObject(arguments - "_grz")))
        val context = invocation.context.minus(com.gromozeka.domain.slot.TOOL_CONTEXT_SLOT_ORIGIN).toMutableMap()
        origin.slotOrigin?.let { context[com.gromozeka.domain.slot.TOOL_CONTEXT_SLOT_ORIGIN] = json.encodeToString(it) }
        val fingerprints = mapOf(call.id.value to invocation.contractFingerprint)
        context[com.gromozeka.application.service.EXPECTED_TOOL_FINGERPRINTS_CONTEXT_KEY] = json.encodeToString(fingerprints)
        val request = WorkerToolExecutionRequest(target, listOf(call), context, resolvedSecretsByToolCallId, fingerprints)
        val id = requests.submit(target.workerId, WorkerGatewayOperation.TOOL_EXECUTION,
            json.encodeToString(request).encodeToByteArray(), checkNotNull(target.requestPolicy),
            actorUserId = origin.actorUserId, projectId = origin.projectId, slotOrigin = origin.slotOrigin, asyncTool = reference)
        check(id == operation.id.value) { "Async Worker request changed its durable child identity" }
        reconcile(operation)
    }

    override suspend fun cancel(operation: com.gromozeka.domain.model.AsyncToolOperation) {
        val request = requests.find(operation.id.value) ?: return
        checkNotNull(asyncProjector) { "Async result projection is not configured" }.validateChild(operation, request)
        requests.cancel(operation.id.value)
        reconcile(operation)
    }

    override suspend fun reconcile(operation: com.gromozeka.domain.model.AsyncToolOperation) {
        requests.find(operation.id.value)
        checkNotNull(asyncProjector) { "Async result projection is not configured" }.reconcile(operation)
    }

}
