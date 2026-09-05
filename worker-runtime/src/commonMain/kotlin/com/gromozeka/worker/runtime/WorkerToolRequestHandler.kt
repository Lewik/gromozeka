package com.gromozeka.worker.runtime

import com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolResult
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerToolExecutionRequest
import com.gromozeka.remote.protocol.WorkerToolExecutionResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

interface WorkerTool {
    val descriptor: AiToolDescriptor
    suspend fun execute(arguments: JsonElement): JsonElement
}

class WorkerToolRequestHandler(
    private val workerId: ConversationRuntimeWorkerId,
    tools: List<WorkerTool>,
    private val beforeExecution: suspend () -> Unit = {},
) : WorkerRequestHandler {
    private val tools = tools.associateBy { it.descriptor.definition.name }
    private val json = Json { encodeDefaults = true }

    init {
        require(this.tools.size == tools.size) { "Worker tool names must be unique" }
        require(tools.all { it.descriptor.metadata.executionScope == AiToolExecutionScope.WORKER }) {
            "Worker tool handler only accepts Worker-scoped tools"
        }
    }

    override suspend fun execute(request: WorkerGatewayMessage.Request): WorkerGatewayMessage.Response {
        require(request.operation == WorkerGatewayOperation.TOOL_EXECUTION) { "Worker does not support ${request.operation}" }
        val execution = json.decodeFromString<WorkerToolExecutionRequest>(request.payload.decodeToString())
        require(execution.executionTarget.workerId == workerId) { "Tool request targets another Worker" }
        require(execution.executionTarget.workspaceMountId == null) { "Worker tools do not accept a Workspace target" }
        require(execution.resolvedSecretsByToolCallId.isEmpty()) { "These Worker tools do not accept secrets" }
        require(execution.toolCalls.size in 1..64) { "Worker tool batch must contain 1-64 calls" }
        require(execution.toolCalls.map { it.id }.distinct().size == execution.toolCalls.size) { "Duplicate tool call IDs" }
        val results = execution.toolCalls.map { call ->
            beforeExecution()
            try {
                val tool = requireNotNull(tools[call.call.name]) { "Worker tool is not available: ${call.call.name}" }
                val result = tool.execute(call.call.input)
                ToolResult(call.id, call.call.name, listOf(ToolResult.Data.Text(result.toString())))
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                ToolResult(call.id, call.call.name, listOf(ToolResult.Data.Text(error.message ?: "Invalid tool request")), isError = true)
            }
        }
        return WorkerGatewayMessage.Response(
            requestId = request.id,
            status = WorkerGatewayMessage.Response.Status.SUCCEEDED,
            payload = json.encodeToString(WorkerToolExecutionResponse(
                results = results,
                returnDirect = execution.toolCalls.any { tools[it.call.name]?.descriptor?.metadata?.returnDirect == true },
            )).encodeToByteArray(),
        )
    }
}
