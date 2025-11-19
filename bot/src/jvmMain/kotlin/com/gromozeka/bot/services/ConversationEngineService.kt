package com.gromozeka.bot.services

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.repository.ThreadRepository
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.gromozeka.domain.repository.ConversationDomainService
import com.gromozeka.domain.service.AIProvider
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.stereotype.Service
import com.gromozeka.infrastructure.ai.config.mcp.McpConfigurationService
import com.gromozeka.infrastructure.ai.springai.ChatModelFactory
import java.util.UUID
import kotlinx.datetime.Clock

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
    private val tabPromptService: TabPromptService,
    private val toolCallingManager: ToolCallingManager,
    private val toolApprovalService: ToolApprovalService,
    private val conversationService: ConversationDomainService,
    private val threadRepository: ThreadRepository,
    private val threadMessageRepository: com.gromozeka.domain.repository.ThreadMessageRepository,
    private val messageConversionService: MessageConversionService,
    private val toolCallbacks: List<org.springframework.ai.tool.ToolCallback>,
    private val mcpConfigurationService: McpConfigurationService,
    private val tokenUsageStatisticsRepository: TokenUsageStatisticsRepository,
    private val coroutineScope: CoroutineScope,
    private val vectorMemoryService: com.gromozeka.bot.services.memory.VectorMemoryService,
    private val knowledgeGraphServiceFacade: com.gromozeka.bot.services.memory.graph.KnowledgeGraphServiceFacade?,
) {
    private val log = KLoggers.logger(this)

    /**
     * Collect all available tools for user-controlled tool execution.
     * Tools are passed in runtime options to ToolCallingManager.
     */
    private fun collectToolOptions(projectPath: String?): ChatOptions {
        val allCallbacks = mutableListOf<org.springframework.ai.tool.ToolCallback>()
        val allNames = mutableSetOf<String>()

        // Built-in @Bean tools
        allCallbacks.addAll(toolCallbacks)
        allNames.addAll(toolCallbacks.map { it.toolDefinition.name() })
        log.debug { "Built-in @Bean tools: ${toolCallbacks.size}" }

        // MCP tools
        val mcpCallbacks = mcpConfigurationService.getToolCallbacks()
        allCallbacks.addAll(mcpCallbacks)
        allNames.addAll(mcpCallbacks.map { it.toolDefinition.name() })
        log.debug { "MCP tools: ${mcpCallbacks.size}" }

        log.debug { "Total tools for runtime options: ${allCallbacks.size}" }

        return ToolCallingChatOptions.builder()
            .toolCallbacks(allCallbacks)
            .toolNames(allNames)
            .internalToolExecutionEnabled(false)
            .toolContext(mapOf("projectPath" to projectPath))
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
        userMessage: Conversation.Message,
        customPrompts: List<String> = emptyList()
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
        val aiProvider = AIProvider.valueOf(conversation.aiProvider)
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

        // 6. Build system prompt with CLAUDE.md context, default agent prompts, custom prompts, and environment info
        val claudeMdAndEnv = systemPromptBuilder.build(projectPath)
        val defaultPrompts = tabPromptService.buildDefaultPrompts()
        val additionalPrompts = tabPromptService.buildAdditionalPrompt(customPrompts)
        val systemPromptText = buildString {
            append(claudeMdAndEnv)
            if (defaultPrompts.isNotEmpty()) {
                append("\n\n")
                append(defaultPrompts)
            }
            if (additionalPrompts.isNotEmpty()) {
                append("\n\n---\n\n")
                append(additionalPrompts)
            }
        }
        val systemMessage = org.springframework.ai.chat.messages.SystemMessage(systemPromptText)
        log.debug { "Built system prompt: ${systemPromptText.length} chars (CLAUDE.md+env: ${claudeMdAndEnv.length}, default: ${defaultPrompts.length}, custom: ${additionalPrompts.length})" }

        // 7. Convert history to Spring AI format
        val springHistory = messageConversionService.convertHistoryToSpringAI(
            messages + userMessage
        )
        log.debug { "Converted ${springHistory.size} messages to Spring AI format" }

        // 8. Prepend system message to conversation history
        val fullHistory = listOf(systemMessage) + springHistory
        log.debug { "Full history with system prompt: ${fullHistory.size} messages" }

        // 9. Collect tools for user-controlled execution (passed in runtime options)
        val toolOptions = collectToolOptions(projectPath)

        // 9a. Increment turn number for this user message
        val turnNumber = threadRepository.incrementTurnNumber(conversation.currentThread)
        log.debug { "Starting turn $turnNumber for conversation $conversationId" }

        // 9b. Initialize token usage accumulator for all iterations in this turn
        var accumulatedPromptTokens = 0
        var accumulatedCompletionTokens = 0
        var accumulatedThinkingTokens = 0
        var accumulatedCacheCreationTokens = 0
        var accumulatedCacheReadTokens = 0

        var currentPrompt = Prompt(fullHistory, toolOptions)
        var iterationCount = 0
        val maxIterations = 10

        // 10. Tool execution loop - manually handle tool calls
        while (iterationCount < maxIterations) {
            iterationCount++
            log.debug { "Tool execution iteration $iterationCount" }

            // Reload conversation history from DB on iterations 2+ (single source of truth)
            if (iterationCount > 1) {
                val currentMessages = conversationService.loadCurrentMessages(conversationId)
                val currentSpringHistory = messageConversionService.convertHistoryToSpringAI(currentMessages)
                val fullHistory = listOf(systemMessage) + currentSpringHistory
                currentPrompt = Prompt(fullHistory, toolOptions)
                log.debug {
                    "Reloaded ${currentMessages.size} messages from DB for iteration $iterationCount " +
                    "(prompt history: ${fullHistory.size} messages)"
                }
            }

            // 5a. Manual aggregation of streaming chunks
            val aggregatedTextBuilder = StringBuilder()
            val aggregatedMetadata = mutableMapOf<String, Any>()
            val aggregatedToolCalls = mutableListOf<AssistantMessage.ToolCall>()

            val streamingMessageId = java.util.UUID.randomUUID().toString()

            var lastChatResponse: org.springframework.ai.chat.model.ChatResponse? = null

            log.debug {
                "Calling chatModel.stream(): messages=${currentPrompt.instructions.size}, " +
                "systemPrompt=${systemPromptText.length} chars, " +
                "model=${chatModel::class.simpleName}"
            }
            chatModel.stream(currentPrompt).asFlow().collect { chatResponse ->
                lastChatResponse = chatResponse

                // 5b. Emit each chunk immediately for real-time UI updates
                chatResponse.results.forEach { generation ->
                    val assistantMessage = generation.output as? AssistantMessage

                    if (assistantMessage != null) {
                        val isThinking = assistantMessage.metadata["thinking"] as? Boolean ?: false
                        val hasText = !assistantMessage.text.isNullOrBlank()
                        val hasToolCalls = !assistantMessage.toolCalls.isNullOrEmpty()

                        if (isThinking) {
                            val thinkingMessage = messageConversionService.fromSpringAI(assistantMessage)
                                .copy(conversationId = conversationId)
                            if (thinkingMessage.content.isNotEmpty()) {
                                conversationService.addMessage(conversationId, thinkingMessage)
                                emit(StreamUpdate.Chunk(thinkingMessage))
                                log.debug { "Persisted and emitted thinking block: ${thinkingMessage.content.size} content items" }
                            }
                        }
                        else if (hasText || hasToolCalls) {
                            if (hasText) {
                                aggregatedTextBuilder.append(assistantMessage.text)
                            }

                            assistantMessage.metadata?.let { metadata ->
                                aggregatedMetadata.putAll(metadata)
                            }

                            assistantMessage.toolCalls?.let { toolCalls ->
                                aggregatedToolCalls.addAll(toolCalls)
                            }
                        }
                    }
                }
            }

            // 5c. Create aggregated final response from accumulated data
            val aggregatedText = aggregatedTextBuilder.toString()
            val finalChunk = if (aggregatedToolCalls.isNotEmpty() || aggregatedText.isNotBlank()) {
                val aggregatedAssistantMessage = if (aggregatedToolCalls.isNotEmpty()) {
                    AssistantMessage.builder()
                        .content(aggregatedText.ifBlank { "" })
                        .properties(aggregatedMetadata)
                        .toolCalls(aggregatedToolCalls)
                        .build()
                } else {
                    AssistantMessage.builder()
                        .content(aggregatedText)
                        .properties(aggregatedMetadata)
                        .build()
                }

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

            // Accumulate usage metadata for this iteration
            finalChunk.metadata?.usage?.let { usage ->
                accumulatedPromptTokens += usage.promptTokens ?: 0
                accumulatedCompletionTokens += usage.completionTokens ?: 0

                when (usage) {
                    is GoogleGenAiUsage -> {
                        accumulatedThinkingTokens += usage.thoughtsTokenCount ?: 0
                        log.info {
                            "Iteration $iterationCount usage: prompt=${usage.promptTokens}, completion=${usage.completionTokens}, " +
                                "total=${usage.totalTokens}, thoughts=${usage.thoughtsTokenCount}"
                        }
                    }
                    else -> {
                        log.info {
                            "Iteration $iterationCount usage: prompt=${usage.promptTokens}, completion=${usage.completionTokens}, " +
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

                val assistantMessage = finalChunk.result.output as? AssistantMessage
                    ?: throw IllegalStateException("Expected AssistantMessage with tool calls")

                val toolCalls = assistantMessage.toolCalls ?: emptyList()
                log.debug { "Found ${toolCalls.size} tool calls: ${toolCalls.map { it.name() }}" }

                // 5e. Persist aggregated message with tool calls
                val toolCallMessage = messageConversionService.fromSpringAI(assistantMessage)
                    .copy(
                        id = Conversation.Message.Id(streamingMessageId),
                        conversationId = conversationId
                    )
                conversationService.addMessage(conversationId, toolCallMessage)
                log.debug { "Persisted aggregated message with ${toolCalls.size} tool calls, id=$streamingMessageId" }

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

                    val errorConversationMessage = messageConversionService.fromSpringAI(errorToolResponseMessage)
                        .copy(conversationId = conversationId)
                    emit(StreamUpdate.Chunk(errorConversationMessage))
                    conversationService.addMessage(conversationId, errorConversationMessage)
                    log.debug { "Emitted and persisted error tool result, continuing conversation" }

                    val updatedHistory = currentPrompt.instructions + assistantMessage + errorToolResponseMessage
                    currentPrompt = Prompt(updatedHistory, toolOptions)
                    log.debug { "Updated prompt with error tool results, continuing loop" }

                    continue
                }

                log.debug {
                    "Tool execution completed, returnDirect: ${toolExecutionResult.returnDirect()}, " +
                        "conversation history size: ${toolExecutionResult.conversationHistory().size}"
                }

                // 5h. Extract ONLY NEW tool result message
                val newHistory = toolExecutionResult.conversationHistory()
                val toolResponseMessage = newHistory.lastOrNull() as? ToolResponseMessage
                    ?: throw IllegalStateException("Expected ToolResponseMessage as last message in history")

                log.debug { "Persisting single ToolResponseMessage with ${toolResponseMessage.responses.size} tool responses" }

                val cleanedToolResponseMessage = removeAdditionalContentFromToolResponse(toolResponseMessage)

                val toolResultConversationMessage = messageConversionService.fromSpringAI(cleanedToolResponseMessage)
                    .copy(conversationId = conversationId)
                emit(StreamUpdate.Chunk(toolResultConversationMessage))
                conversationService.addMessage(conversationId, toolResultConversationMessage)
                log.debug { "Emitted and persisted tool result message (without additionalContent)" }

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

                // 6a. Persist aggregated final message
                val finalAssistantMessage = finalChunk.result.output as? AssistantMessage
                if (finalAssistantMessage != null && !finalAssistantMessage.text.isNullOrBlank()) {
                    val finalMessage = messageConversionService.fromSpringAI(finalAssistantMessage)
                        .copy(
                            id = Conversation.Message.Id(streamingMessageId),
                            conversationId = conversationId
                        )
                    conversationService.addMessage(conversationId, finalMessage)
                    log.debug { "Persisted aggregated final message: ${finalAssistantMessage.text.length} chars, id=$streamingMessageId" }

                    emit(StreamUpdate.Chunk(finalMessage))
                } else {
                    log.debug { "No text content in final message, skipping persistence" }
                }

                if (finishReason == null) {
                    log.warn { "Final chunk has null finishReason - unexpected, but continuing" }
                }

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

        // Save accumulated token usage statistics for this turn
        if (accumulatedPromptTokens > 0 || accumulatedCompletionTokens > 0) {
            coroutineScope.launch {
                try {
                    tokenUsageStatisticsRepository.save(
                        TokenUsageStatistics(
                            id = TokenUsageStatistics.Id(UUID.randomUUID().toString()),
                            threadId = conversation.currentThread,
                            turnNumber = turnNumber,
                            timestamp = Clock.System.now(),
                            promptTokens = accumulatedPromptTokens,
                            completionTokens = accumulatedCompletionTokens,
                            thinkingTokens = accumulatedThinkingTokens,
                            cacheCreationTokens = accumulatedCacheCreationTokens,
                            cacheReadTokens = accumulatedCacheReadTokens,
                            modelId = conversation.modelName
                        )
                    )
                    log.info {
                        "Saved turn $turnNumber statistics: prompt=$accumulatedPromptTokens, " +
                            "completion=$accumulatedCompletionTokens, thinking=$accumulatedThinkingTokens, " +
                            "total=${accumulatedPromptTokens + accumulatedCompletionTokens + accumulatedThinkingTokens}"
                    }
                } catch (e: Exception) {
                    log.error(e) { "Failed to save token usage statistics for turn $turnNumber" }
                }
            }
        }
    }

    /**
     * Remove additionalContent (images) from ToolResponseMessage before saving to DB.
     */
    private fun removeAdditionalContentFromToolResponse(message: ToolResponseMessage): ToolResponseMessage {
        val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()

        val cleanedResponses = message.responses.map { response ->
            try {
                val responseData = response.responseData()

                val textOnly = when {
                    !responseData.trim().startsWith("{") && !responseData.trim().startsWith("[") -> {
                        responseData
                    }

                    responseData.trim().startsWith("[") -> {
                        val jsonNode = objectMapper.readTree(responseData)
                        if (jsonNode.isArray) {
                            jsonNode.firstOrNull { it.has("type") && it.get("type").asText() == "text" }
                                ?.get("text")?.asText() ?: responseData
                        } else {
                            responseData
                        }
                    }

                    else -> {
                        val data = org.springframework.ai.model.ModelOptionsUtils.jsonToMap(responseData)
                        data["text"]?.toString() ?: responseData
                    }
                }

                ToolResponseMessage.ToolResponse(
                    response.id(),
                    response.name(),
                    textOnly
                )
            } catch (e: Exception) {
                log.warn(e) { "Failed to parse tool response data for cleaning" }
                response
            }
        }

        return ToolResponseMessage.builder()
            .responses(cleanedResponses)
            .metadata(message.metadata)
            .build()
    }

    /**
     * Remember current thread messages to vector memory.
     * This method triggers incremental embedding of thread messages for semantic recall.
     */
    suspend fun rememberCurrentThread(conversationId: Conversation.Id) {
        try {
            val conversation = conversationService.findById(conversationId)
                ?: throw IllegalStateException("Conversation not found: $conversationId")
            
            vectorMemoryService.rememberThread(conversation.currentThread.value)
            log.info { "Remembered thread ${conversation.currentThread} for conversation $conversationId" }
        } catch (e: Exception) {
            log.error(e) { "Failed to remember thread for conversation $conversationId: ${e.message}" }
            throw e
        }
    }

    /**
     * Add current thread to knowledge graph (Phase 2).
     * This method triggers entity and relationship extraction from conversation.
     */
    suspend fun addToGraphCurrentThread(conversationId: Conversation.Id) {
        if (knowledgeGraphServiceFacade == null) {
            log.warn { "KnowledgeGraphServiceFacade not available (knowledge-graph.enabled=false)" }
            return
        }

        try {
            val conversation = conversationService.findById(conversationId)
                ?: throw IllegalStateException("Conversation not found: $conversationId")

            val threadMessages = threadMessageRepository.getMessagesByThread(conversation.currentThread)
                .filter { message ->
                    message.role in listOf(Conversation.Message.Role.USER, Conversation.Message.Role.ASSISTANT)
                }
                .joinToString("\n\n") { message ->
                    "${message.role}: ${extractGraphTextContent(message)}"
                }

            log.info { "Adding thread ${conversation.currentThread} to knowledge graph for conversation $conversationId" }

            val result = knowledgeGraphServiceFacade.extractAndSaveToGraph(threadMessages)
            log.info { "Knowledge graph update result: $result" }
        } catch (e: Exception) {
            log.error(e) { "Failed to add thread to graph for conversation $conversationId: ${e.message}" }
            throw e
        }
    }

    private fun extractGraphTextContent(message: Conversation.Message): String {
        return message.content.mapNotNull { contentItem ->
            when (contentItem) {
                is Conversation.Message.ContentItem.UserMessage -> contentItem.text
                is Conversation.Message.ContentItem.AssistantMessage -> contentItem.structured.fullText
                else -> null
            }
        }.joinToString("\\n")
    }

    /**
     * Stream update events representing conversation progress.
     */
    sealed class StreamUpdate {
        data class Chunk(val message: Conversation.Message) : StreamUpdate()
        data class Error(val exception: Throwable) : StreamUpdate()
    }
}
