package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.ToolCancellationSignal
import klog.KLoggers
import kotlinx.coroutines.*
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.datetime.Clock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import kotlin.coroutines.coroutineContext

/**
 * Result of parallel tool execution.
 */
data class ToolExecutionResult(
    val results: List<ContentItem.ToolResult>,
    val returnDirect: Boolean,
)

/**
 * Executes tool calls in parallel using coroutines.
 *
 * Each tool is executed independently, allowing Claude's parallel tool calls
 * to actually run concurrently. Errors in one tool don't affect others.
 */
@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
class ParallelToolExecutor(
    private val aiToolProvider: AiToolProvider,
    private val toolApprovalService: ToolApprovalService,
) {
    private val log = KLoggers.logger(this)

    /**
     * Execute multiple tool calls in parallel.
     *
     * @param toolCalls tool calls requested by the model
     * @param toolContext exact project, workspace, worker, and cancellation context
     * @return ToolExecutionResult with results and returnDirect flag
     */
    suspend fun executeParallel(
        toolCalls: List<ContentItem.ToolCall>,
        toolContext: ToolExecutionContext,
        runtimeTaskId: ConversationRuntimeTask.Id?,
        worker: ConversationRuntimeWorkerIdentity?,
        expectedTarget: ConversationRuntimeTaskTarget,
        onToolExecutionChanged: suspend (ConversationRuntimeToolExecution) -> Unit = {},
    ): ToolExecutionResult {
        if (toolCalls.isEmpty()) return ToolExecutionResult(emptyList(), false)

        val callbackMap = buildCallbackMap()
        toolCalls.forEach { toolCall ->
            val callback = callbackMap[toolCall.call.name]
                ?: error("Worker does not advertise requested tool: ${toolCall.call.name}")
            val requestedTarget = toolCall.call.input.parseExecutionTarget()
            require(requestedTarget.workerId == expectedTarget.workerId) {
                "Tool ${toolCall.call.name} targets worker ${requestedTarget.workerId.value}, " +
                    "but runtime task targets ${expectedTarget.workerId.value}"
            }
            when (callback.metadata.executionScope) {
                AiToolExecutionScope.WORKER -> require(requestedTarget.workspaceId == null) {
                    "Worker-scoped tool ${toolCall.call.name} must not target a workspace"
                }
                AiToolExecutionScope.WORKSPACE,
                AiToolExecutionScope.COMMAND_TASK_OWNER,
                -> require(requestedTarget.workspaceId == expectedTarget.workspaceId) {
                    "Tool ${toolCall.call.name} targets workspace ${requestedTarget.workspaceId?.value}, " +
                        "but runtime task targets ${expectedTarget.workspaceId?.value}"
                }
            }
        }

        val results = coroutineScope {
            val deferreds: List<Deferred<ContentItem.ToolResult>> = toolCalls.map { toolCall ->
                async {
                    executeSingleToolWithProgress(
                        toolCall = toolCall,
                        callbackMap = callbackMap,
                        toolContext = toolContext,
                        runtimeTaskId = runtimeTaskId,
                        worker = worker,
                        onToolExecutionChanged = onToolExecutionChanged,
                    )
                }
            }
            deferreds.awaitAll()
        }

        // Check if all executed tools have returnDirect=true
        val returnDirect = toolCalls.all { toolCall ->
            callbackMap[toolCall.call.name]?.metadata?.returnDirect == true
        }

        return ToolExecutionResult(results, returnDirect)
    }

    private suspend fun executeSingleToolWithProgress(
        toolCall: ContentItem.ToolCall,
        callbackMap: Map<String, AiToolCallback>,
        toolContext: ToolExecutionContext,
        runtimeTaskId: ConversationRuntimeTask.Id?,
        worker: ConversationRuntimeWorkerIdentity?,
        onToolExecutionChanged: suspend (ConversationRuntimeToolExecution) -> Unit,
    ): ContentItem.ToolResult {
        return try {
            val started = ConversationRuntimeToolExecution(
                toolCallId = toolCall.id,
                toolName = toolCall.call.name,
                status = ConversationRuntimeToolExecution.Status.RUNNING,
                runtimeTaskId = runtimeTaskId,
                worker = worker,
                startedAt = Clock.System.now(),
            )
            onToolExecutionChanged(started)
            val result = executeSingleTool(toolCall, callbackMap, toolContext)
            onToolExecutionChanged(
                started.copy(
                    status = if (result.isError) {
                        ConversationRuntimeToolExecution.Status.FAILED
                    } else {
                        ConversationRuntimeToolExecution.Status.COMPLETED
                    },
                    completedAt = Clock.System.now(),
                    isError = result.isError,
                )
            )
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val failed = ConversationRuntimeToolExecution(
                toolCallId = toolCall.id,
                toolName = toolCall.call.name,
                status = ConversationRuntimeToolExecution.Status.FAILED,
                runtimeTaskId = runtimeTaskId,
                worker = worker,
                startedAt = Clock.System.now(),
                completedAt = Clock.System.now(),
                isError = true,
            )
            onToolExecutionChanged(failed)
            throw error
        }
    }

    /**
     * Build callback map dynamically from the aggregated AI tool provider.
     */
    private fun buildCallbackMap(): Map<String, AiToolCallback> {
        return aiToolProvider.getTools().associateBy { it.definition.name }
    }

    private suspend fun executeSingleTool(
        toolCall: ContentItem.ToolCall,
        callbackMap: Map<String, AiToolCallback>,
        toolContext: ToolExecutionContext,
    ): ContentItem.ToolResult {
        val toolId = toolCall.id
        val toolName = toolCall.call.name
        val arguments = toolCall.call.input.withoutExecutionTarget().toString()

        log.debug { "Executing tool: $toolName (${toolCall.id.value})" }

        try {
            // Check approval
            val approvalResult = toolApprovalService.approve(listOf(toolCall))
            if (approvalResult is ApprovalResult.Rejected) {
                log.warn { "Tool rejected: $toolName - ${approvalResult.reason}" }
                return ContentItem.ToolResult(
                    toolUseId = toolId,
                    toolName = toolName,
                    result = listOf(
                        ContentItem.ToolResult.Data.Text("Tool rejected: ${approvalResult.reason}")
                    ),
                    isError = true
                )
            }

            // Find callback
            val callback = callbackMap[toolName]
                ?: return ContentItem.ToolResult(
                    toolUseId = toolId,
                    toolName = toolName,
                    result = listOf(
                        ContentItem.ToolResult.Data.Text("Tool not found: $toolName")
                    ),
                    isError = true
                )

            // Validate JSON arguments before execution
            try {
                kotlinx.serialization.json.Json.parseToJsonElement(arguments)
            } catch (e: Exception) {
                log.error(e) { "Invalid JSON arguments for $toolName: $arguments" }
                return ContentItem.ToolResult(
                    toolUseId = toolId,
                    toolName = toolName,
                    result = listOf(
                        ContentItem.ToolResult.Data.Text("Failed to parse tool arguments: ${e.message}")
                    ),
                    isError = true
                )
            }

            // Log tool call arguments for debugging
            log.info { "Executing tool: $toolName with arguments: $arguments" }

            val parentJob = coroutineContext[Job]
            val cancellableToolContext = toolContext.withCancellationSignal(
                ToolCancellationSignal {
                    if (parentJob?.isActive == false) {
                        throw CancellationException("Tool execution cancelled: $toolName")
                    }
                }
            )

            // Execute on IO dispatcher (blocking call)
            val result = withContext(Dispatchers.IO) {
                callback.call(arguments, cancellableToolContext)
            }

            log.debug { "Tool $toolName completed successfully" }

            return ContentItem.ToolResult(
                toolUseId = toolId,
                toolName = toolName,
                result = listOf(
                    ContentItem.ToolResult.Data.Text(result)
                ),
                isError = false
            )

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Tool execution failed: $toolName with arguments: $arguments" }
            return ContentItem.ToolResult(
                toolUseId = toolId,
                toolName = toolName,
                result = listOf(
                    ContentItem.ToolResult.Data.Text("Error: ${e.message ?: e::class.simpleName}")
                ),
                isError = true
            )
        }
    }
}
