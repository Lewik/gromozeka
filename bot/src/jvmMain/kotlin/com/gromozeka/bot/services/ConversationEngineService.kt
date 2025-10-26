package com.gromozeka.bot.services

import com.gromozeka.shared.domain.conversation.ConversationTree
import com.gromozeka.shared.services.ConversationTreeService
import klog.KLoggers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
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

            // 5a. Stream the model response with real-time emission
            val allChunks = mutableListOf<org.springframework.ai.chat.model.ChatResponse>()

            chatModel.stream(currentPrompt).asFlow().collect { chatResponse ->
                // Accumulate for later tool calls check
                allChunks.add(chatResponse)

                // 5b. Emit each chunk immediately for real-time UI updates
                chatResponse.results.forEach { generation ->
                    val assistantMessage = generation.output as? AssistantMessage

                    // Debug logging
                    log.debug { "Processing chunk: assistantMessage=${assistantMessage != null}, " +
                        "text=${assistantMessage?.text?.take(50)}, " +
                        "textBlank=${assistantMessage?.text.isNullOrBlank()}, " +
                        "metadata=${assistantMessage?.metadata?.keys}, " +
                        "hasToolCalls=${assistantMessage?.toolCalls?.isNotEmpty()}" }

                    if (assistantMessage != null) {
                        // Emit thinking blocks even if text is blank
                        val isThinking = assistantMessage.metadata["thinking"] as? Boolean ?: false
                        val hasText = !assistantMessage.text.isNullOrBlank()
                        val hasToolCalls = !assistantMessage.toolCalls.isNullOrEmpty()

                        if (isThinking || hasText || hasToolCalls) {
                            val conversationMessage = messageConversionService.fromSpringAI(assistantMessage)
                            if (conversationMessage.content.isNotEmpty()) {
                                emit(StreamUpdate.Chunk(conversationMessage))
                                conversationTreeService.addMessage(conversationId, conversationMessage)
                                log.debug { "Emitted chunk: ${conversationMessage.content.size} content items, " +
                                    "thinking=$isThinking, hasText=$hasText, hasToolCalls=$hasToolCalls" }
                            } else {
                                log.warn { "Conversion resulted in empty content, skipping chunk" }
                            }
                        } else {
                            log.debug { "Skipping empty chunk (no thinking, text, or tool calls)" }
                        }
                    }
                }
            }

            log.debug { "Received ${allChunks.size} streaming chunks" }

            // 5c. Aggregate final response for tool calls check (last non-empty chunk)
            val finalChunk = allChunks.lastOrNull { it.results.isNotEmpty() }
                ?: throw IllegalStateException("No non-empty chunks received")

            val finishReason = finalChunk.result.metadata?.finishReason
            log.debug {
                "Final chunk: ${finalChunk.results.size} results, " +
                    "finish reason: $finishReason, " +
                    "hasToolCalls: ${finalChunk.hasToolCalls()}"
            }

            // 5d. Check if response contains tool calls
            if (finalChunk.hasToolCalls()) {
                log.debug { "Response contains tool calls, processing..." }

                // Extract assistant message with tool calls
                val assistantMessage = finalChunk.result.output as? AssistantMessage
                    ?: throw IllegalStateException("Expected AssistantMessage with tool calls")

                val toolCalls = assistantMessage.toolCalls ?: emptyList()
                log.debug { "Found ${toolCalls.size} tool calls: ${toolCalls.map { it.name() }}" }

                // Tool call message already emitted in step 5b

                // 5e. Approve tool calls
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

                // 5f. Execute tool calls via ToolCallingManager
                log.debug { "Executing tool calls..." }
                val toolExecutionResult = try {
                    toolCallingManager.executeToolCalls(currentPrompt, finalChunk)
                } catch (e: Exception) {
                    log.error(e) { "Tool execution failed" }
                    emit(StreamUpdate.Error(e))
                    break
                }

                log.debug {
                    "Tool execution completed, returnDirect: ${toolExecutionResult.returnDirect()}, " +
                        "conversation history size: ${toolExecutionResult.conversationHistory().size}"
                }

                // 5g. Extract and emit tool result messages
                val toolResultMessages = toolExecutionResult.conversationHistory()
                    .filterIsInstance<ToolResponseMessage>()

                log.debug { "Found ${toolResultMessages.size} tool result messages" }

                toolResultMessages.forEach { toolResponseMessage ->
                    val toolResultConversationMessage = messageConversionService.fromSpringAI(toolResponseMessage)
                    emit(StreamUpdate.Chunk(toolResultConversationMessage))
                    conversationTreeService.addMessage(conversationId, toolResultConversationMessage)
                    log.debug { "Emitted and persisted tool result message" }
                }

                // 5h. Check if tool execution returned direct result
                if (toolExecutionResult.returnDirect()) {
                    log.debug { "Tool execution returned direct result, stopping loop" }
                    break
                }

                // 5i. Update prompt with new conversation history and continue loop
                currentPrompt = Prompt(toolExecutionResult.conversationHistory(), options)
                log.debug { "Updated prompt with tool execution results, continuing loop" }

            } else {
                // 6. Final response - no tool calls (chunks already emitted in step 5b)
                log.debug { "No tool calls in response, finishReason: $finishReason" }

                // Extended thinking: all chunks (thinking + text) already collected and emitted
                // The stream from Claude CLI includes all assistant messages until Result event
                // So if we're here, the complete response (including thinking) has been processed

                if (finishReason == null) {
                    log.warn { "Final chunk has null finishReason - unexpected, but continuing" }
                }

                // Exit loop - conversation turn is complete
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
