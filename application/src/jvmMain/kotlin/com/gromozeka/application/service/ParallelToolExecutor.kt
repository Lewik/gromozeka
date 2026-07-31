package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.ToolCancellationSignal
import com.gromozeka.domain.tool.TOOL_CONTEXT_TOOL_NAME
import klog.KLoggers
import kotlinx.coroutines.*
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.jsonObject
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
        executor: ConversationRuntimeExecutorIdentity,
        expectedTarget: ConversationRuntimeTaskTarget,
        onToolExecutionChanged: suspend (ConversationRuntimeToolExecution) -> Unit = {},
    ): ToolExecutionResult {
        if (toolCalls.isEmpty()) return ToolExecutionResult(emptyList(), false)

        require(expectedTarget.matches(executor)) {
            "Runtime task target $expectedTarget does not match executor $executor"
        }
        val callbackMap = buildCallbackMap()
        toolCalls.forEach { toolCall ->
            val callback = callbackMap[toolCall.call.name]
                ?: error("Runtime executor does not provide requested tool: ${toolCall.call.name}")
            when (callback.metadata.executionScope) {
                AiToolExecutionScope.SERVER -> {
                    require(expectedTarget == ConversationRuntimeTaskTarget.Server) {
                        "Conversation runtime tool ${toolCall.call.name} cannot execute on Worker"
                    }
                    require(AI_TOOL_EXECUTION_TARGET_FIELD !in toolCall.call.input.jsonObject) {
                        "Conversation runtime tool ${toolCall.call.name} must not declare execution_target"
                    }
                }
                AiToolExecutionScope.WORKER -> {
                    val requestedTarget = toolCall.call.input.parseExecutionTarget()
                    val workerTarget = expectedTarget as? ConversationRuntimeTaskTarget.Worker
                        ?: error("Worker tool ${toolCall.call.name} cannot execute on Server")
                    require(requestedTarget.workerId == workerTarget.workerId) {
                        "Tool ${toolCall.call.name} targets worker ${requestedTarget.workerId?.value}, " +
                            "but runtime task targets ${workerTarget.workerId.value}"
                    }
                }
                AiToolExecutionScope.WORKSPACE -> {
                    val requestedTarget = toolCall.call.input.parseExecutionTarget()
                    val workerTarget = expectedTarget as? ConversationRuntimeTaskTarget.Worker
                        ?: error("Workspace tool ${toolCall.call.name} cannot execute on Server")
                    require(requestedTarget.workspaceMountId == workerTarget.workspaceMountId) {
                        "Tool ${toolCall.call.name} targets workspace mount " +
                            "${requestedTarget.workspaceMountId?.value}, but runtime task targets " +
                            "${workerTarget.workspaceMountId?.value}"
                    }
                }
                AiToolExecutionScope.COMMAND_TASK_OWNER,
                AiToolExecutionScope.COMMAND_MONITOR_OWNER -> require(
                    expectedTarget is ConversationRuntimeTaskTarget.Worker
                ) {
                    "Command owner tool ${toolCall.call.name} cannot execute on Server"
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
                        executor = executor,
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
        executor: ConversationRuntimeExecutorIdentity,
        onToolExecutionChanged: suspend (ConversationRuntimeToolExecution) -> Unit,
    ): ContentItem.ToolResult {
        return try {
            val started = ConversationRuntimeToolExecution(
                toolCallId = toolCall.id,
                toolName = toolCall.call.name,
                status = ConversationRuntimeToolExecution.Status.RUNNING,
                runtimeTaskId = runtimeTaskId,
                executor = executor,
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
                executor = executor,
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

    private fun ConversationRuntimeTaskTarget.matches(executor: ConversationRuntimeExecutorIdentity): Boolean =
        when (this) {
            ConversationRuntimeTaskTarget.Server -> executor is ConversationRuntimeExecutorIdentity.Server
            is ConversationRuntimeTaskTarget.Worker ->
                executor is ConversationRuntimeExecutorIdentity.Worker &&
                    executor.identity.workerId == workerId
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
            val cancellableToolContext = toolContext
                .withValue(TOOL_CONTEXT_TOOL_NAME, toolName)
                .withCancellationSignal(
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
