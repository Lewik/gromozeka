package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation.Message.ContentItem
import klog.KLoggers
import kotlinx.coroutines.*
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
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
    private val applicationContext: org.springframework.context.ApplicationContext,
    private val mcpToolProvider: com.gromozeka.domain.service.McpToolProvider,
    private val toolApprovalService: ToolApprovalService,
) {
    private val log = KLoggers.logger(this)

    /**
     * Execute multiple tool calls in parallel.
     *
     * @param toolCalls Spring AI tool calls from the model
     * @param toolContext Context to pass to tools (e.g., projectPath)
     * @param scope Coroutine scope for async execution
     * @return ToolExecutionResult with results and returnDirect flag
     */
    suspend fun executeParallel(
        toolCalls: List<AssistantMessage.ToolCall>,
        toolContext: ToolContext,
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
            callbackMap[toolCall.name()]?.toolMetadata?.returnDirect() == true
        }

        return ToolExecutionResult(results, returnDirect)
    }

    /**
     * Build callback map dynamically from ApplicationContext.
     * 
     * Uses getBeansOfType() to pick up ALL ToolCallback beans including those
     * registered after initial Spring context creation (via registerSingleton):
     * - ToolsRegistrationConfig: Tool<*,*> beans (grz_*, stride, memory, web, lsp tools)
     * - InternalMcpToolsRegistrar: GromozekaMcpTool beans (create_agent, tell_agent, etc.)
     */
    private fun buildCallbackMap(): Map<String, ToolCallback> {
        val map = mutableMapOf<String, ToolCallback>()

        // All registered ToolCallback beans (built-in + internal MCP tools)
        applicationContext.getBeansOfType(ToolCallback::class.java).values.forEach { callback ->
            map[callback.toolDefinition.name()] = callback
        }

        // External MCP tools from MCP servers
        mcpToolProvider.getToolCallbacks().forEach { callback ->
            map[callback.toolDefinition.name()] = callback
        }

        return map
    }

    private suspend fun executeSingleTool(
        toolCall: AssistantMessage.ToolCall,
        callbackMap: Map<String, ToolCallback>,
        toolContext: ToolContext,
    ): ContentItem.ToolResult {
        val toolId = ContentItem.ToolCall.Id(toolCall.id())
        val toolName = toolCall.name()

        log.debug { "Executing tool: $toolName (${toolCall.id()})" }

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
            val arguments = toolCall.arguments()
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
