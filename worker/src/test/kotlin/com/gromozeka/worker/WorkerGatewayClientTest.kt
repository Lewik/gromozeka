package com.gromozeka.worker

import com.gromozeka.application.service.AI_TOOL_EXECUTION_TARGET_FIELD
import com.gromozeka.application.service.AI_TOOL_EXECUTION_WORKER_ID_FIELD
import com.gromozeka.application.service.AutoApproveToolApprovalService
import com.gromozeka.application.service.ParallelToolExecutor
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiEmbeddingRequest
import com.gromozeka.domain.service.AiEmbeddingResponse
import com.gromozeka.domain.service.AiRequestResponseExecutionHandler
import com.gromozeka.domain.service.AiSpeechSynthesisRequest
import com.gromozeka.domain.service.AiSpeechSynthesisResponse
import com.gromozeka.domain.service.AiSpeechTranscriptionRequest
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.WorkerControlHandler
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerToolExecutionRequest
import com.gromozeka.remote.protocol.WorkerToolExecutionResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class WorkerGatewayClientTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    @Test
    fun `gateway URL uses outbound secure WebSocket`() {
        assertEquals(
            "wss://gromozeka.example/worker/ws",
            workerGatewayWebSocketUrl("https://gromozeka.example"),
        )
        assertEquals(
            "ws://127.0.0.1:8765/worker/ws",
            workerGatewayWebSocketUrl("http://127.0.0.1:8765"),
        )
    }

    @Test
    fun `gateway URL refuses plaintext remote endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            workerGatewayWebSocketUrl("http://gromozeka.example")
        }
    }

    @Test
    fun `gateway executes tool only on exact worker and strips routing metadata`() = runBlocking {
        var receivedInput: String? = null
        var receivedContext: ToolExecutionContext? = null
        val tool = object : AiToolCallback {
            override val definition = AiToolDefinition(
                name = "test_worker_tool",
                description = "Test worker tool",
                inputSchema = """{"type":"object"}""",
            )
            override val metadata = AiToolMetadata(
                returnDirect = true,
                executionScope = AiToolExecutionScope.WORKER,
            )

            override fun call(toolInput: String, context: ToolExecutionContext?): String {
                receivedInput = toolInput
                receivedContext = context
                return "worker-result"
            }
        }
        val handler = operationHandler(tool)
        val identity = workerIdentity("worker-1")
        val response = handler.execute(
            identity = identity,
            request = toolRequest(
                requestId = "request-1",
                targetWorkerId = identity.workerId,
            ),
        )

        assertEquals(WorkerGatewayMessage.Response.Status.SUCCEEDED, response.status)
        val payload = json.decodeFromString<WorkerToolExecutionResponse>(
            assertNotNull(response.payload).decodeToString()
        )
        assertEquals("worker-result", payload.results.single().result.single().textContent())
        assertEquals(true, payload.returnDirect)
        assertFalse(receivedInput.orEmpty().contains(AI_TOOL_EXECUTION_TARGET_FIELD))
        assertEquals("context-value", receivedContext?.getString("context-key"))
    }

    @Test
    fun `gateway refuses tool request targeting another worker`() = runBlocking {
        val handler = operationHandler(
            object : AiToolCallback {
                override val definition = AiToolDefinition(
                    name = "test_worker_tool",
                    description = "Test worker tool",
                    inputSchema = """{"type":"object"}""",
                )
                override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.WORKER)

                override fun call(toolInput: String, context: ToolExecutionContext?): String =
                    error("Tool must not execute")
            }
        )
        val response = handler.execute(
            identity = workerIdentity("worker-1"),
            request = toolRequest(
                requestId = "request-2",
                targetWorkerId = ConversationRuntimeWorkerId("worker-2"),
            ),
        )

        assertEquals(WorkerGatewayMessage.Response.Status.FAILED, response.status)
        assertEquals("IllegalArgumentException", response.errorCode)
        assertEquals(null, response.payload)
    }

    private fun operationHandler(tool: AiToolCallback): WorkerGatewayOperationHandler =
        WorkerGatewayOperationHandler(
            workerControlHandler = WorkerControlHandler { error("Unused worker control request") },
            aiRequestResponseHandler = UnusedAiRequestResponseHandler,
            parallelToolExecutor = ParallelToolExecutor(
                aiToolProvider = object : AiToolProvider {
                    override fun getTools(): List<AiToolCallback> = listOf(tool)
                },
                toolApprovalService = AutoApproveToolApprovalService(),
            ),
        )

    private fun toolRequest(
        requestId: String,
        targetWorkerId: ConversationRuntimeWorkerId,
    ): WorkerGatewayMessage.Request {
        val target = ConversationRuntimeTaskTarget.Worker(targetWorkerId)
        val payload = WorkerToolExecutionRequest(
            executionTarget = target,
            toolCalls = listOf(
                Conversation.Message.ContentItem.ToolCall(
                    id = Conversation.Message.ContentItem.ToolCall.Id("tool-call-1"),
                    call = Conversation.Message.ContentItem.ToolCall.Data(
                        name = "test_worker_tool",
                        input = buildJsonObject {
                            put("path", "README.md")
                            putJsonObject(AI_TOOL_EXECUTION_TARGET_FIELD) {
                                put(AI_TOOL_EXECUTION_WORKER_ID_FIELD, targetWorkerId.value)
                            }
                        },
                    ),
                )
            ),
            toolContext = mapOf("context-key" to "context-value"),
        )
        return WorkerGatewayMessage.Request(
            id = requestId,
            operation = WorkerGatewayOperation.TOOL_EXECUTION,
            payload = json.encodeToString(payload).encodeToByteArray(),
        )
    }

    private fun workerIdentity(workerId: String): ConversationRuntimeWorkerIdentity =
        ConversationRuntimeWorkerIdentity(
            workerId = ConversationRuntimeWorkerId(workerId),
            sessionId = ConversationRuntimeWorkerSessionId("session-$workerId"),
        )

    private fun Conversation.Message.ContentItem.ToolResult.Data.textContent(): String =
        (this as Conversation.Message.ContentItem.ToolResult.Data.Text).content
}

private object UnusedAiRequestResponseHandler : AiRequestResponseExecutionHandler {
    override suspend fun call(
        selection: AiRuntimeSelection,
        workspaceRootPath: String?,
        request: AiRuntimeRequest,
    ): AiRuntimeResponse = error("Unused AI call")

    override suspend fun embed(request: AiEmbeddingRequest): AiEmbeddingResponse =
        error("Unused embedding call")

    override suspend fun transcribe(request: AiSpeechTranscriptionRequest): String =
        error("Unused speech transcription")

    override suspend fun synthesize(request: AiSpeechSynthesisRequest): AiSpeechSynthesisResponse =
        error("Unused speech synthesis")
}
