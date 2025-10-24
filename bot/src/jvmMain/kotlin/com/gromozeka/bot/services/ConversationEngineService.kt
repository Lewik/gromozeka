package com.gromozeka.bot.services

import com.gromozeka.shared.domain.conversation.ConversationTree
import com.gromozeka.shared.services.ConversationTreeService
import klog.KLoggers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.claudecode.ClaudeCodeChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.stereotype.Service

/**
 * Service for managing conversation with AI using User-Controlled Tool Execution.
 *
 * This service implements the user-controlled tool execution pattern from Spring AI,
 * which gives us full control over the tool execution lifecycle:
 * 1. Call ChatModel with internalToolExecutionEnabled=false
 * 2. Check if response contains tool calls
 * 3. Approve tool calls (currently auto-approved)
 * 4. Execute tool calls via ToolCallingManager
 * 5. Send tool results back to model
 * 6. Repeat until no more tool calls
 *
 * This approach allows us to:
 * - See all intermediate tool_use and tool_result messages
 * - Persist them to the conversation tree
 * - Add UI approval in the future
 * - Have full visibility and control over tool execution
 */
@Service
class ConversationEngineService(
    private val chatModel: ChatModel,
    private val toolCallingManager: ToolCallingManager,
    private val toolApprovalService: ToolApprovalService,
    private val conversationTreeService: ConversationTreeService,
    private val messageConversionService: MessageConversionService,
) {
    private val log = KLoggers.logger(this)

    /**
     * Stream messages in a conversation with user-controlled tool execution.
     *
     * This method implements the manual tool execution loop, giving us full control
     * over when and how tools are executed.
     *
     * @param conversationId The conversation to append messages to
     * @param userMessage The user message to send
     * @return Flow of StreamUpdate events representing the conversation progress
     */
    suspend fun streamMessage(
        conversationId: ConversationTree.Id,
        userMessage: ConversationTree.Message
    ): Flow<StreamUpdate> = flow {
        log.debug { "Starting user-controlled tool execution for conversation: $conversationId" }

        // 1. Load conversation history
        val conversation = conversationTreeService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")

        log.debug { "Loaded conversation with ${conversation.messages.size} messages" }

        // 2. Persist user message immediately
        conversationTreeService.addMessage(conversationId, userMessage)

        // 3. Convert history to Spring AI format
        val springHistory = messageConversionService.convertHistoryToSpringAI(
            conversation.messages + userMessage
        )

        log.debug { "Converted ${springHistory.size} messages to Spring AI format" }

        // 4. Create options based on ChatModel defaults with internalToolExecutionEnabled=false for user control
        val defaultOptions = chatModel.defaultOptions as ClaudeCodeChatOptions
        val options = ClaudeCodeChatOptions.builder()
            .model(defaultOptions.model)
            .maxTokens(defaultOptions.maxTokens)
            .temperature(defaultOptions.temperature)
            .thinkingBudgetTokens(defaultOptions.thinkingBudgetTokens)
            .useXmlToolFormat(defaultOptions.useXmlToolFormat)
            .toolNames(defaultOptions.toolNames) // Use default tool names from config
            .internalToolExecutionEnabled(false) // KEY: We control tool execution
            .build()

        var currentPrompt = Prompt(springHistory, options)
        var iterationCount = 0
        val maxIterations = 10 // Safety limit to prevent infinite loops

        // 5. Tool execution loop - manually handle tool calls
        while (iterationCount < maxIterations) {
            iterationCount++
            log.debug { "Tool execution iteration $iterationCount" }

            // 5a. Call the model (blocking call)
            val chatResponse = chatModel.call(currentPrompt)

            log.debug {
                "Received response with ${chatResponse.results.size} results, " +
                    "finish reason: ${chatResponse.result.metadata?.finishReason}"
            }

            // 5b. Check if response contains tool calls
            if (chatResponse.hasToolCalls()) {
                log.debug { "Response contains tool calls, processing..." }

                // Extract assistant message with tool calls
                val assistantMessage = chatResponse.result.output as? AssistantMessage
                    ?: throw IllegalStateException("Expected AssistantMessage with tool calls")

                val toolCalls = assistantMessage.toolCalls ?: emptyList()
                log.debug { "Found ${toolCalls.size} tool calls: ${toolCalls.map { it.name() }}" }

                // 5c. Convert and emit assistant message with tool_use
                val conversationMessage = messageConversionService.fromSpringAI(assistantMessage)
                emit(StreamUpdate.Chunk(conversationMessage))
                conversationTreeService.addMessage(conversationId, conversationMessage)
                log.debug { "Emitted and persisted assistant message with tool calls" }

                // 5d. Approve tool calls
                val approvalResult = toolApprovalService.approve(toolCalls)
                log.debug { "Tool approval result: $approvalResult" }

                if (approvalResult is ApprovalResult.Rejected) {
                    log.warn { "Tool calls rejected: ${approvalResult.reason}" }
                    emit(
                        StreamUpdate.Error(
                            IllegalStateException("Tool calls rejected: ${approvalResult.reason}")
                        )
                    )
                    break
                }

                // 5e. Execute tool calls via ToolCallingManager
                log.debug { "Executing tool calls..." }
                val toolExecutionResult = try {
                    toolCallingManager.executeToolCalls(currentPrompt, chatResponse)
                } catch (e: Exception) {
                    log.error(e) { "Tool execution failed" }
                    emit(StreamUpdate.Error(e))
                    break
                }

                log.debug {
                    "Tool execution completed, returnDirect: ${toolExecutionResult.returnDirect()}, " +
                        "conversation history size: ${toolExecutionResult.conversationHistory().size}"
                }

                // 5f. Extract and emit tool result messages
                val toolResultMessages = toolExecutionResult.conversationHistory()
                    .filterIsInstance<ToolResponseMessage>()

                log.debug { "Found ${toolResultMessages.size} tool result messages" }

                toolResultMessages.forEach { toolResponseMessage ->
                    val toolResultConversationMessage = messageConversionService.fromSpringAI(toolResponseMessage)
                    emit(StreamUpdate.Chunk(toolResultConversationMessage))
                    conversationTreeService.addMessage(conversationId, toolResultConversationMessage)
                    log.debug { "Emitted and persisted tool result message" }
                }

                // 5g. Check if tool execution returned direct result
                if (toolExecutionResult.returnDirect()) {
                    log.debug { "Tool execution returned direct result, stopping loop" }
                    break
                }

                // 5h. Update prompt with new conversation history and continue loop
                currentPrompt = Prompt(toolExecutionResult.conversationHistory(), options)
                log.debug { "Updated prompt with tool execution results, continuing loop" }

            } else {
                // 6. Final response - no tool calls
                log.debug { "No tool calls in response, this is the final message" }

                val assistantMessage = chatResponse.result.output as? AssistantMessage
                    ?: throw IllegalStateException("Expected AssistantMessage in final response")

                // Convert and emit final message
                val conversationMessage = messageConversionService.fromSpringAI(assistantMessage)
                emit(StreamUpdate.Chunk(conversationMessage))
                conversationTreeService.addMessage(conversationId, conversationMessage)
                log.debug { "Emitted and persisted final assistant message" }

                // Exit loop
                break
            }
        }

        if (iterationCount >= maxIterations) {
            log.error { "Tool execution loop exceeded maximum iterations ($maxIterations)" }
            emit(
                StreamUpdate.Error(
                    IllegalStateException("Tool execution loop exceeded maximum iterations")
                )
            )
        }

        log.debug { "Completed conversation turn in $iterationCount iterations" }
    }

    /**
     * Stream update events representing conversation progress.
     */
    sealed class StreamUpdate {
        /**
         * A chunk of the conversation (a complete message).
         */
        data class Chunk(val message: ConversationTree.Message) : StreamUpdate()

        /**
         * An error occurred during conversation processing.
         */
        data class Error(val exception: Throwable) : StreamUpdate()
    }
}
