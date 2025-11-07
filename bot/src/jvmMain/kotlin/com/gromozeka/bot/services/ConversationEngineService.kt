package com.gromozeka.bot.services

import com.gromozeka.shared.domain.Conversation
import com.gromozeka.shared.services.ConversationService
import klog.KLoggers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.beans.factory.ObjectProvider
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
    private val chatModelFactory: ChatModelFactory,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val toolCallingManager: ToolCallingManager,
    private val toolApprovalService: ToolApprovalService,
    private val conversationService: ConversationService,
    private val messageConversionService: MessageConversionService,
    private val toolCallbacks: List<org.springframework.ai.tool.ToolCallback>,
    private val mcpToolProvider: ObjectProvider<SyncMcpToolCallbackProvider>,
) {
    private val log = KLoggers.logger(this)

    /**
     * Collect all available tools for user-controlled tool execution.
     * Tools are passed in runtime options to ToolCallingManager.
     */
    private fun collectToolOptions(): ChatOptions {
        val allCallbacks = mutableListOf<org.springframework.ai.tool.ToolCallback>()
        val allNames = mutableSetOf<String>()

        // Built-in @Bean tools
        allCallbacks.addAll(toolCallbacks)
        allNames.addAll(toolCallbacks.map { it.toolDefinition.name() })
        log.debug { "Built-in @Bean tools: ${toolCallbacks.size}" }

        // MCP tools
        mcpToolProvider.ifAvailable { provider ->
            val mcpCallbacks = provider.getToolCallbacks()
            allCallbacks.addAll(mcpCallbacks)
            allNames.addAll(mcpCallbacks.map { it.toolDefinition.name() })
            log.debug { "MCP tools: ${mcpCallbacks.size}" }
        }

        log.debug { "Total tools for runtime options: ${allCallbacks.size}" }

        return ToolCallingChatOptions.builder()
            .toolCallbacks(allCallbacks)
            .toolNames(allNames)
            .internalToolExecutionEnabled(false)
            .build()
    }

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
        conversationId: Conversation.Id,
        userMessage: Conversation.Message
    ): Flow<StreamUpdate> = flow {
        log.debug { "Starting user-controlled tool execution for conversation: $conversationId" }

        // 1. Load conversation metadata
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        log.debug { "Using AI provider: ${conversation.aiProvider}, model: ${conversation.modelName}" }

        // 2. Get project path for working directory
        val projectPath = conversationService.getProjectPath(conversationId)
        log.debug { "Using project path: $projectPath" }

        // 3. Get ChatModel for this conversation's provider with user's model
        val aiProvider = com.gromozeka.bot.settings.AIProvider.valueOf(conversation.aiProvider)
        val chatModel = chatModelFactory.get(
            aiProvider,
            conversation.modelName,
            projectPath
        )
        log.debug { "Loaded ChatModel: ${chatModel::class.simpleName}" }

        // 4. Load conversation history
        val messages = conversationService.loadCurrentMessages(conversationId)
        log.debug { "Loaded conversation with ${messages.size} messages" }

        // 5. Persist user message immediately
        conversationService.addMessage(conversationId, userMessage)

        // 6. Build system prompt with CLAUDE.md context and environment info
        val systemPromptText = systemPromptBuilder.build(projectPath)
        val systemMessage = org.springframework.ai.chat.messages.SystemMessage(systemPromptText)
        log.debug { "Built system prompt: ${systemPromptText.length} chars" }

        // 7. Convert history to Spring AI format
        val springHistory = messageConversionService.convertHistoryToSpringAI(
            messages + userMessage
        )
        log.debug { "Converted ${springHistory.size} messages to Spring AI format" }

        // 8. Prepend system message to conversation history
        val fullHistory = listOf(systemMessage) + springHistory
        log.debug { "Full history with system prompt: ${fullHistory.size} messages" }

        // 9. Collect tools for user-controlled execution (passed in runtime options)
        val toolOptions = collectToolOptions()

        var currentPrompt = Prompt(fullHistory, toolOptions)
        var iterationCount = 0
        val maxIterations = 10 // Safety limit to prevent infinite loops

        // 10. Tool execution loop - manually handle tool calls
        while (iterationCount < maxIterations) {
            iterationCount++
            log.debug { "Tool execution iteration $iterationCount" }

            // 5a. Manual aggregation of streaming chunks (simpler than MessageAggregator with Flow)
            // Accumulate text, metadata, and tool calls from all chunks
            val aggregatedTextBuilder = StringBuilder()
            val aggregatedMetadata = mutableMapOf<String, Any>()
            val aggregatedToolCalls = mutableListOf<AssistantMessage.ToolCall>()

            // Create single streaming message ID for all chunks (for future streaming UI feature)
            val streamingMessageId = java.util.UUID.randomUUID().toString()
            // TODO: Re-enable streaming UI - accumulate content for incremental updates
            // val accumulatedContent = mutableListOf<Conversation.Message.ContentItem>()

            // Separate: thinking blocks (persist immediately) vs text chunks (aggregate first)
            val thinkingMessages = mutableListOf<Conversation.Message>()
            var lastChatResponse: org.springframework.ai.chat.model.ChatResponse? = null

            // Log request details before calling model
            log.debug {
                "Calling chatModel.stream(): messages=${currentPrompt.instructions.size}, " +
                "systemPrompt=${systemPromptText.length} chars, " +
                "model=${chatModel::class.simpleName}"
            }
            chatModel.stream(currentPrompt).asFlow().collect { chatResponse ->
                lastChatResponse = chatResponse

                // 5b. Emit each chunk immediately for real-time UI updates (but don't persist yet)
                chatResponse.results.forEach { generation ->
                    val assistantMessage = generation.output as? AssistantMessage

                    if (assistantMessage != null) {
                        val isThinking = assistantMessage.metadata["thinking"] as? Boolean ?: false
                        val hasText = !assistantMessage.text.isNullOrBlank()
                        val hasToolCalls = !assistantMessage.toolCalls.isNullOrEmpty()

                        // Thinking blocks are persisted immediately (separate messages, not aggregated)
                        if (isThinking) {
                            val thinkingMessage = messageConversionService.fromSpringAI(assistantMessage)
                                .copy(conversationId = conversationId)
                            if (thinkingMessage.content.isNotEmpty()) {
                                emit(StreamUpdate.Chunk(thinkingMessage))
                                thinkingMessages.add(thinkingMessage)
                                log.debug { "Emitted thinking block: ${thinkingMessage.content.size} content items" }
                            }
                        }
                        // Text and tool calls are emitted for UI but NOT persisted yet (will be aggregated)
                        else if (hasText || hasToolCalls) {
                            // Accumulate text for DB persistence
                            if (hasText) {
                                aggregatedTextBuilder.append(assistantMessage.text)
                            }

                            // Accumulate metadata for DB persistence
                            assistantMessage.metadata?.let { metadata ->
                                aggregatedMetadata.putAll(metadata)
                            }

                            // Accumulate tool calls for DB persistence
                            assistantMessage.toolCalls?.let { toolCalls ->
                                aggregatedToolCalls.addAll(toolCalls)
                            }

                            // TODO: Re-enable streaming UI feature (typing effect)
                            // Currently disabled for UI/DB consistency - only emit after DB save
                            /*
                            // Convert chunk to content items and accumulate for UI
                            val chunkMessage = messageConversionService.fromSpringAI(assistantMessage)
                            accumulatedContent.addAll(chunkMessage.content)

                            // Emit incremental message with ALL accumulated content and SAME ID
                            // UI will replace existing message with this ID, creating live streaming effect
                            val currentInstant = kotlinx.datetime.Clock.System.now()
                            val incrementalMessage = Conversation.Message(
                                id = Conversation.Message.Id(streamingMessageId),
                                conversationId = conversationId,
                                role = Conversation.Message.Role.ASSISTANT,
                                content = accumulatedContent.toList(),
                                createdAt = kotlin.time.Instant.fromEpochMilliseconds(currentInstant.toEpochMilliseconds())
                            )
                            emit(StreamUpdate.Chunk(incrementalMessage))
                            log.debug { "Emitted incremental chunk: ${accumulatedContent.size} total content items" }
                            */
                        }
                    }
                }
            }

            // 5c. Persist thinking blocks collected during streaming
            thinkingMessages.forEach { thinkingMessage ->
                conversationService.addMessage(conversationId, thinkingMessage)
                log.debug { "Persisted thinking block" }
            }

            // 5d. Create aggregated final response from accumulated data
            val aggregatedText = aggregatedTextBuilder.toString()
            val finalChunk = if (aggregatedToolCalls.isNotEmpty() || aggregatedText.isNotBlank()) {
                // Create aggregated AssistantMessage
                val aggregatedAssistantMessage = if (aggregatedToolCalls.isNotEmpty()) {
                    AssistantMessage.builder()
                        .content(aggregatedText.ifBlank { "" })
                        .properties(aggregatedMetadata)
                        .toolCalls(aggregatedToolCalls)
                        .build()
                } else {
                    // Text-only message with empty tool calls list
                    AssistantMessage.builder()
                        .content(aggregatedText)
                        .properties(aggregatedMetadata)
                        .build()
                }

                // Wrap in ChatResponse for compatibility
                lastChatResponse?.let { response ->
                    org.springframework.ai.chat.model.ChatResponse(
                        listOf(org.springframework.ai.chat.model.Generation(aggregatedAssistantMessage)),
                        response.metadata
                    )
                }
            } else {
                lastChatResponse
            }

            if (finalChunk == null) {
                throw IllegalStateException("No response received from model")
            }

            log.debug { "Aggregated response: text length=${aggregatedText.length}, tool calls=${aggregatedToolCalls.size}" }

            // Log usage metadata (including thinking tokens for Gemini)
            finalChunk.metadata?.usage?.let { usage ->
                when (usage) {
                    is GoogleGenAiUsage -> {
                        log.info {
                            "Usage: prompt=${usage.promptTokens}, completion=${usage.completionTokens}, " +
                                "total=${usage.totalTokens}, thoughts=${usage.thoughtsTokenCount}"
                        }
                    }
                    else -> {
                        log.info {
                            "Usage: prompt=${usage.promptTokens}, completion=${usage.completionTokens}, " +
                                "total=${usage.totalTokens}"
                        }
                    }
                }
            }

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

                // 5e. Persist aggregated message with tool calls
                val toolCallMessage = messageConversionService.fromSpringAI(assistantMessage)
                    .copy(
                        id = Conversation.Message.Id(streamingMessageId), // Use same ID as streaming chunks for UI consistency
                        conversationId = conversationId
                    )
                conversationService.addMessage(conversationId, toolCallMessage)
                log.debug { "Persisted aggregated message with ${toolCalls.size} tool calls, id=$streamingMessageId" }

                // Emit to UI after DB save (UI/DB consistency)
                emit(StreamUpdate.Chunk(toolCallMessage))

                // 5f. Approve tool calls
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

                // 5g. Execute tool calls via ToolCallingManager
                log.debug { "Executing tool calls..." }
                val toolExecutionResult = try {
                    toolCallingManager.executeToolCalls(currentPrompt, finalChunk)
                } catch (e: Exception) {
                    log.error(e) { "Tool execution failed: ${e.message}" }

                    // Create error tool responses to send back to Claude
                    val errorResponses = toolCalls.map { toolCall ->
                        ToolResponseMessage.ToolResponse(
                            toolCall.id(),
                            toolCall.name(),
                            "<tool_use_error>Error: ${e.message ?: e::class.simpleName}</tool_use_error>"
                        )
                    }

                    val errorToolResponseMessage = ToolResponseMessage.builder()
                        .responses(errorResponses)
                        .metadata(emptyMap())
                        .build()

                    // Convert to conversation format and emit
                    val errorConversationMessage = messageConversionService.fromSpringAI(errorToolResponseMessage)
                        .copy(conversationId = conversationId)
                    emit(StreamUpdate.Chunk(errorConversationMessage))
                    conversationService.addMessage(conversationId, errorConversationMessage)
                    log.debug { "Emitted and persisted error tool result, continuing conversation" }

                    // Update prompt with error response and continue loop
                    val updatedHistory = currentPrompt.instructions + assistantMessage + errorToolResponseMessage
                    currentPrompt = Prompt(updatedHistory, toolOptions)
                    log.debug { "Updated prompt with error tool results, continuing loop" }

                    // Continue loop - Claude will see the error and can self-correct
                    continue
                }

                log.debug {
                    "Tool execution completed, returnDirect: ${toolExecutionResult.returnDirect()}, " +
                        "conversation history size: ${toolExecutionResult.conversationHistory().size}"
                }

                // 5h. Extract ONLY NEW tool result message (last in history)
                // Spring AI returns: [old messages] + [new AssistantMessage] + [new ToolResponseMessage]
                // We only need the ToolResponseMessage (AssistantMessage was already persisted above)
                val newHistory = toolExecutionResult.conversationHistory()
                val toolResponseMessage = newHistory.lastOrNull() as? ToolResponseMessage
                    ?: throw IllegalStateException("Expected ToolResponseMessage as last message in history")

                log.debug { "Persisting single ToolResponseMessage with ${toolResponseMessage.responses.size} tool responses" }

                val toolResultConversationMessage = messageConversionService.fromSpringAI(toolResponseMessage)
                    .copy(conversationId = conversationId)
                emit(StreamUpdate.Chunk(toolResultConversationMessage))
                conversationService.addMessage(conversationId, toolResultConversationMessage)
                log.debug { "Emitted and persisted tool result message" }

                // 5i. Check if tool execution returned direct result
                if (toolExecutionResult.returnDirect()) {
                    log.debug { "Tool execution returned direct result, stopping loop" }
                    break
                }

                // 5j. Update prompt with new conversation history and continue loop
                currentPrompt = Prompt(toolExecutionResult.conversationHistory(), toolOptions)
                log.debug { "Updated prompt with tool execution results, continuing loop" }

            } else {
                // 6. Final response - no tool calls
                log.debug { "No tool calls in response, finishReason: $finishReason" }

                // 6a. Persist aggregated final message (text only, no tool calls)
                val finalAssistantMessage = finalChunk.result.output as? AssistantMessage
                if (finalAssistantMessage != null && !finalAssistantMessage.text.isNullOrBlank()) {
                    val finalMessage = messageConversionService.fromSpringAI(finalAssistantMessage)
                        .copy(
                            id = Conversation.Message.Id(streamingMessageId), // Use same ID as streaming chunks for UI consistency
                            conversationId = conversationId
                        )
                    conversationService.addMessage(conversationId, finalMessage)
                    log.debug { "Persisted aggregated final message: ${finalAssistantMessage.text.length} chars, id=$streamingMessageId" }

                    // Emit to UI after DB save (UI/DB consistency)
                    emit(StreamUpdate.Chunk(finalMessage))
                } else {
                    log.debug { "No text content in final message, skipping persistence" }
                }

                // Extended thinking blocks have been persisted separately during streaming
                // Final text message has now been persisted and emitted to UI

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
        data class Chunk(val message: Conversation.Message) : StreamUpdate()

        /**
         * An error occurred during conversation processing.
         */
        data class Error(val exception: Throwable) : StreamUpdate()
    }
}
