package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.coroutines.*
import com.gromozeka.domain.tool.ToolExecutionContext
import org.springframework.stereotype.Service

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
     * @param toolContext Context to pass to tools (e.g., projectPath)
     * @param scope Coroutine scope for async execution
     * @return ToolExecutionResult with results and returnDirect flag
     */
    suspend fun executeParallel(
        toolCalls: List<ContentItem.ToolCall>,
        toolContext: ToolExecutionContext,
        scope: CoroutineScope,
    ): ToolExecutionResult {
        if (toolCalls.isEmpty()) return ToolExecutionResult(emptyList(), false)

        val callbackMap = buildCallbackMap()

        val deferreds: List<Deferred<ContentItem.ToolResult>> = toolCalls.map { toolCall ->
            scope.async {
                executeSingleTool(toolCall, callbackMap, toolContext)
            }
        }

        val results = deferreds.awaitAll()

        // Check if all executed tools have returnDirect=true
        val returnDirect = toolCalls.all { toolCall ->
            callbackMap[toolCall.call.name]?.metadata?.returnDirect == true
        }

        return ToolExecutionResult(results, returnDirect)
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
            val arguments = toolCall.call.input.toString()
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

            // Execute on IO dispatcher (blocking call)
            val result = withContext(Dispatchers.IO) {
                try {
                    callback.call(arguments, toolContext)
                } catch (e: Exception) {
                    log.error(e) { "Tool execution error for $toolName with arguments: $arguments" }
                    throw e
                }
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

        } catch (e: Exception) {
            log.error(e) { "Tool execution failed: $toolName" }
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
