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
    private val sessionRegistry: WorkerGatewaySessionRegistry,
    @Value("\${gromozeka.runtime.tool-execution.timeout-millis:1800000}")
    timeoutMillis: Long,
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
        val request = WorkerToolExecutionRequest(
            executionTarget = executionTarget,
            toolCalls = toolCalls,
            toolContext = toolContext.asMap().mapValues { (key, value) ->
                require(value is String) {
                    "Worker tool context '$key' must be a string"
                }
                value
            },
            resolvedSecretsByToolCallId = resolvedSecretsByToolCallId,
        )
        val response = sessionRegistry.execute(
            target = target,
            operation = WorkerGatewayOperation.TOOL_EXECUTION,
            payload = json.encodeToString(request).encodeToByteArray(),
            timeout = timeout,
        ).let {
            json.decodeFromString<WorkerToolExecutionResponse>(it.decodeToString())
        }
        return WorkerToolExecutionResult(
            results = response.results,
            returnDirect = response.returnDirect,
        )
    }
}
