package com.gromozeka.server

import com.gromozeka.domain.tool.AiToolContextField.*
import com.gromozeka.domain.tool.AiToolContextRequirements

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolLoadingPolicy
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.AiToolCallResult
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolResult.Data
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredUserId
import com.gromozeka.remote.protocol.WorkerGatewayCodec
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerToolExecutionResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service
import java.util.Base64

@Service
class WorkerRequestToolContributor(
    private val requests: WorkerRequestService,
    private val authorization: WorkerRequestAuthorization,
) : AiToolCallbackContributor {
    override val callbacks: List<AiToolCallback> = listOf(callback(false), callback(true))

    private fun callback(cancel: Boolean): AiToolCallback = object : AiToolCallback {
        override val definition = AiToolDefinition(
            name = if (cancel) "grz_worker_request_cancel" else "grz_worker_request_get",
            description = if (cancel) "Cancel a pending Worker request by ID. Offline cancellation is delivered on reconnect; already performed effects cannot be undone."
                else "Read the status and saved tool result of your Worker request by ID after the original wait ended. Never resubmit the action to retrieve its result.",
            inputSchema = """{"type":"object","properties":{"request_id":{"type":"string"}},"required":["request_id"],"additionalProperties":false}""",
        )
        override val metadata = AiToolMetadata(
            contextRequirements = AiToolContextRequirements(setOf(USER), optional = setOf(PROJECT)),
            executionScope = AiToolExecutionScope.SERVER,
            loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE,
            visibleToMemoryPipeline = false,
        )

        override fun call(toolInput: String, context: ToolExecutionContext?): String =
            runBlocking { callResult(toolInput, context).renderText() }

        override suspend fun callResult(toolInput: String, context: ToolExecutionContext?): AiToolCallResult = coroutineScope {
            try {
                val id = requireNotNull(Json.parseToJsonElement(toolInput).jsonObject["request_id"]).jsonPrimitive.content
                var record = requireNotNull(requests.find(id)) { "Request unavailable" }
                require(record.actorUserId == context.requiredUserId()) { "Request unavailable" }
                context?.getString(com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID)?.let { projectId ->
                    require(record.projectId?.value == projectId) { "Request unavailable" }
                }
                authorization.requireAccess(record)
                if (cancel) {
                    requests.cancel(id)
                    record = requireNotNull(requests.find(id))
                }
                val response = record.response?.let { WorkerGatewayCodec.decode(it) as WorkerGatewayMessage.Response }
                val request = WorkerGatewayCodec.decode(record.request) as WorkerGatewayMessage.Request
                val saved = if (!cancel && request.operation == WorkerGatewayOperation.TOOL_EXECUTION && response?.payload != null)
                    Json.decodeFromString<WorkerToolExecutionResponse>(response.payload!!.decodeToString()) else null
                val status = buildJsonObject {
                    put("request_id", id)
                    put("worker_id", record.workerId.value)
                    put("status", when {
                        response != null -> response.status.name
                        record.cancelRequestedAt != null -> "CANCEL_REQUESTED"
                        record.dispatchedAt != null -> "PENDING_RESULT"
                        else -> "QUEUED"
                    })
                    put("start_deadline", record.startDeadline.toString())
                    if (response != null) {
                        response.errorCode?.let { put("error_code", it) }
                        response.errorMessage?.let { put("error", it) }
                    }
                    saved?.let { result ->
                        put("tool_results", kotlinx.serialization.json.buildJsonArray {
                            result.results.forEach { tool -> add(buildJsonObject {
                                put("call_id", tool.toolUseId.value); put("tool_name", tool.toolName); put("is_error", tool.isError)
                                result.structuredContentByCallId[tool.toolUseId.value]?.let { put("structured_content", it) }
                            }) }
                        })
                    }
                }
                val content = buildList {
                    add(AiToolResult.Text(status.toString()))
                    if (saved != null) {
                        saved.results.forEach { toolResult ->
                            add(AiToolResult.Text("Tool ${toolResult.toolName} (${toolResult.toolUseId.value}), is_error=${toolResult.isError}"))
                            toolResult.result.forEach { item ->
                                add(when (item) {
                                    is Data.Text -> AiToolResult.Text(item.content)
                                    is Data.Base64Data -> AiToolResult.Binary(Base64.getDecoder().decode(item.data), item.fileName ?: "worker-result", item.mediaType.value)
                                    is Data.UrlData -> AiToolResult.Text(item.url)
                                    is Data.ArtifactData -> AiToolResult.Text(Json.encodeToString(Data.serializer(), item))
                                })
                            }
                        }
                    }
                }
                AiToolCallResult(content = content, structuredContent = status)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AiToolCallResult(
                    content = listOf(AiToolResult.Text(buildJsonObject { put("error", error.message ?: "Worker request unavailable") }.toString())),
                    isError = true,
                )
            }
        }
    }
}
