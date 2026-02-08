package com.gromozeka.application.service

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.BlockState
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.repository.ThreadRepository
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.gromozeka.domain.service.ChatModelProvider
import com.gromozeka.domain.service.McpToolProvider
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.anthropic.api.AnthropicCacheOptions
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.stereotype.Service
import com.gromozeka.shared.uuid.uuid7

/**
 * LLM-agnostic service for managing conversation with AI using User-Controlled Tool Execution.
 *
 * Uses blocking call() instead of streaming for better provider compatibility.
 * Tool execution loop:
 * 1. Call ChatModel with internalToolExecutionEnabled=false
 * 2. Check if the response contains tool calls
 * 3. Execute tool calls in parallel
 * 4. Send tool results back to the model
 * 5. Repeat until no more tool calls
 * 6. Auto-remember thread to vector memory if enabled (after final response)
 */
@Service
class ConversationEngineService(
    private val chatModelProvider: ChatModelProvider,
    private val agentDomainService: AgentDomainService,
    private val toolApprovalService: ToolApprovalService,
    private val parallelToolExecutor: ParallelToolExecutor,
    private val conversationService: ConversationDomainService,
    private val threadRepository: ThreadRepository,
    private val threadMessageRepository: com.gromozeka.domain.repository.ThreadMessageRepository,
    private val messageConversionService: MessageConversionService,
    private val toolCallbacks: List<org.springframework.ai.tool.ToolCallback>,
    private val mcpToolProvider: McpToolProvider,
    private val tokenUsageStatisticsRepository: TokenUsageStatisticsRepository,
    private val coroutineScope: CoroutineScope,
    private val vectorMemoryService: com.gromozeka.domain.service.VectorMemoryService,
    private val knowledgeGraphService: com.gromozeka.domain.service.KnowledgeGraphService?,
    private val toolCallPairingService: ToolCallPairingService,
    private val toolCallSequenceFixerService: ToolCallSequenceFixerService,
    private val settingsProvider: com.gromozeka.domain.service.SettingsProvider
) {
    private val log = KLoggers.logger(this)

    /**
     * Collect all available tools for user-controlled tool execution.
     * Tools are passed in runtime options to ToolCallingManager.
     */
    private fun collectToolOptions(projectPath: String?, provider: AIProvider): ChatOptions {
        log.debug { "collectToolOptions: provider=$provider" }
        
        val allCallbacks = mutableListOf<org.springframework.ai.tool.ToolCallback>()
        val allNames = mutableSetOf<String>()

        // Built-in @Bean tools
        allCallbacks.addAll(toolCallbacks)
        allNames.addAll(toolCallbacks.map { it.toolDefinition.name() })
        log.debug { "Built-in @Bean tools: ${toolCallbacks.size}" }

        // MCP tools
        val mcpCallbacks = mcpToolProvider.getToolCallbacks()
        allCallbacks.addAll(mcpCallbacks)
        allNames.addAll(mcpCallbacks.map { it.toolDefinition.name() })
        log.debug { "MCP tools: ${mcpCallbacks.size}" }

        log.debug { "Total tools for runtime options: ${allCallbacks.size}" }

        // Use Anthropic-specific options for caching support
        return when (provider) {
            AIProvider.ANTHROPIC -> {
                val cacheOptions = AnthropicCacheOptions.builder()
                    .strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
                    .build()

                log.info { "Creating AnthropicChatOptions with caching strategy: ${cacheOptions.strategy}" }

                AnthropicChatOptions.builder()
                    .toolCallbacks(allCallbacks)
                    .toolNames(allNames)
                    .internalToolExecutionEnabled(false)
                    .toolContext(mapOf("projectPath" to projectPath))
                    .cacheOptions(cacheOptions)
                    .build()
                    .also { log.debug { "AnthropicChatOptions created with ${allCallbacks.size} tools and cache enabled" } }
            }
            AIProvider.OPEN_AI -> {
                log.debug { "Creating OpenAiChatOptions for provider=$provider" }
                OpenAiChatOptions.builder()
                    .toolCallbacks(allCallbacks)
                    .toolNames(allNames)
                    .internalToolExecutionEnabled(false)
                    .toolContext(mapOf("projectPath" to projectPath))
                    .build()
            }
            else -> {
                log.debug { "Creating standard ToolCallingChatOptions for provider=$provider" }
                ToolCallingChatOptions.builder()
                    .toolCallbacks(allCallbacks)
                    .toolNames(allNames)
                    .internalToolExecutionEnabled(false)
                    .toolContext(mapOf("projectPath" to projectPath))
                    .build()
            }
        }
    }

    /**
     * Send message and get response using blocking call().
     * LLM-agnostic - works with all providers (Anthropic, OpenAI, Google, etc.)
     *
     * Automatically remembers thread to vector memory after final response if:
     * - settings.autoRememberThreads is enabled
     * - settings.vectorStorageEnabled is enabled
     *
     * @param conversationId The conversation to append messages to
     * @param userMessage The user message to send
     * @param agent The agent to use for this conversation (provides system prompts)
     * @return Flow of Message objects (assistant responses, tool results).
     *         Flow completes when conversation turn is done.
     */
    suspend fun sendMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
    ): Flow<Conversation.Message> = flow {
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val project = conversationService.getProject(conversationId)
        val provider = AIProvider.valueOf(agent.aiProvider)
        val chatModel = chatModelProvider.getChatModel(
            provider,
            agent.modelName,
            project.path
        )
        // Add user message
        conversationService.addMessage(conversationId, userMessage)
        
        // Fix non-sequential ToolCall/ToolResult pairs AFTER adding user message
        // This ensures ToolResult appears IMMEDIATELY after ToolCall (Anthropic API requirement)
        val fixResult = toolCallSequenceFixerService.fixNonSequentialPairs(conversationId)
        
        if (fixResult.fixed) {
            log.info { 
                "Fixed non-sequential ToolCall/ToolResult pairs: " +
                "added ${fixResult.addedResults} error results, " +
                "converted ${fixResult.convertedResults} orphaned results" 
            }
        }

        val systemMessages = agentDomainService
            .assembleSystemPrompt(agent, project)
            .map { SystemMessage(it) }

        val finalMessages = conversationService.loadCurrentMessages(conversationId)
        val springHistory = messageConversionService.convertHistoryToSpringAI(finalMessages)
        val toolOptions = collectToolOptions(project.path, provider)

        var currentPrompt = Prompt(systemMessages + springHistory, toolOptions)
        var iterationCount = 0
        val maxIterations = 200

        while (iterationCount < maxIterations) {
            iterationCount++

            if (iterationCount > 1) {
                val currentMessages = conversationService.loadCurrentMessages(conversationId)
                val currentSpringHistory = messageConversionService.convertHistoryToSpringAI(currentMessages)
                currentPrompt = Prompt(systemMessages + currentSpringHistory, toolOptions)
            }

            val chatResponse: ChatResponse
            try {
                log.info { "Calling LLM: model=${agent.modelName}, provider=$provider, iteration=$iterationCount" }
                chatResponse = withContext(Dispatchers.IO) {
                    chatModel.call(currentPrompt)
                }
                log.info { "ChatResponse received: ${chatResponse.results.size} results, stopReason=${chatResponse.results.firstOrNull()?.metadata?.finishReason}" }
            } catch (e: Exception) {
                log.error(e) { "Chat call error" }
                val errorMessage = createErrorMessage(conversationId, e.message ?: "Unknown error")
                emit(errorMessage)
                conversationService.addMessage(conversationId, errorMessage)
                break
            }

            val generation = chatResponse.results.firstOrNull()
            if (generation == null) {
                log.warn { "Empty response from chat model" }
                break
            }

            val allToolCalls = chatResponse.results.flatMap { it.output.toolCalls }
            val hasToolCalls = chatResponse.hasToolCalls()

            val assistantMessages = createAssistantMessagesFromResponse(conversationId, chatResponse)

            // Save usage statistics with reference to the last assistant message
            val lastAssistantMessage = assistantMessages.lastOrNull()
            if (lastAssistantMessage != null) {
                chatResponse.metadata.usage?.let { usage ->
                    try {
                        val nativeUsage = usage.getNativeUsage()

                        val (cacheCreation, cacheRead) = when (nativeUsage) {
                            is AnthropicApi.Usage -> Pair(
                                nativeUsage.cacheCreationInputTokens() ?: 0,
                                nativeUsage.cacheReadInputTokens() ?: 0
                            )
                            else -> Pair(0, 0)
                        }

                        val thinkingTokens = when (nativeUsage) {
                            is AnthropicApi.Usage -> nativeUsage.outputTokens()?.minus(usage.completionTokens ?: 0) ?: 0
                            is GoogleGenAiUsage -> nativeUsage.thoughtsTokenCount ?: 0
                            else -> 0
                        }

                        val totalInputTokens = (usage.promptTokens ?: 0) + cacheCreation + cacheRead
                        val totalOutputTokens = (usage.completionTokens ?: 0) + thinkingTokens
                        
                        log.info { 
                            "Tokens: prompt=${usage.promptTokens} (new), cache_creation=$cacheCreation, cache_read=$cacheRead, " +
                            "total_input=$totalInputTokens, completion=${usage.completionTokens}, thinking=$thinkingTokens, " +
                            "total_output=$totalOutputTokens, total=${totalInputTokens + totalOutputTokens}"
                        }
                        
                        if (thinkingTokens > 0) {
                            log.info { "Extended thinking was used: $thinkingTokens thinking tokens generated" }
                        }

                        tokenUsageStatisticsRepository.save(
                            TokenUsageStatistics(
                                id = TokenUsageStatistics.Id(uuid7()),
                                threadId = conversation.currentThread,
                                lastMessageId = lastAssistantMessage.id,
                                timestamp = Clock.System.now(),
                                promptTokens = usage.promptTokens ?: 0,
                                completionTokens = usage.completionTokens ?: 0,
                                cacheCreationTokens = cacheCreation,
                                cacheReadTokens = cacheRead,
                                thinkingTokens = thinkingTokens,
                                provider = agent.aiProvider,
                                modelId = agent.modelName
                            )
                        )
                    } catch (e: Exception) {
                        log.error(e) { "Failed to save token usage statistics" }
                    }
                }
            }

            // Emit and save all assistant messages (thinking, tool calls, text - as separate messages)
            assistantMessages.forEach { message ->
                emit(message)
                conversationService.addMessage(conversationId, message)
            }

            if (hasToolCalls) {
                val approvalResult = toolApprovalService.approve(allToolCalls)
                if (approvalResult is ApprovalResult.Rejected) {
                    log.warn { "Tool calls rejected: ${approvalResult.reason}" }
                    // Find the message containing tool calls
                    val messageWithToolCalls = assistantMessages.lastOrNull { msg ->
                        msg.content.any { it is ContentItem.ToolCall }
                    }
                    if (messageWithToolCalls != null) {
                        val rejectedMessage = messageWithToolCalls.copy(
                            error = Conversation.Message.GenerationError(
                                message = "Tool calls rejected: ${approvalResult.reason}",
                                type = "rejected"
                            )
                        )
                        emit(rejectedMessage)
                    }
                    break
                }

                val toolContext = ToolContext(
                    mapOf("projectPath" to project.path)
                )
                val executionResult = parallelToolExecutor.executeParallel(
                    toolCalls = allToolCalls,
                    toolContext = toolContext,
                    scope = coroutineScope
                )

                val toolResultMessage = Conversation.Message(
                    id = Conversation.Message.Id(uuid7()),
                    conversationId = conversationId,
                    role = Conversation.Message.Role.USER,
                    content = executionResult.results,
                    createdAt = Clock.System.now()
                )
                emit(toolResultMessage)
                conversationService.addMessage(conversationId, toolResultMessage)

                if (executionResult.returnDirect) {
                    break
                }
            } else {
                // Final message (no tool calls) - auto-remember thread if enabled
                if (settingsProvider.autoRememberThreads && settingsProvider.vectorStorageEnabled) {
                    try {
                        vectorMemoryService.rememberThread(conversation.currentThread.value)
                        log.debug { "Auto-remembered thread ${conversation.currentThread}" }
                    } catch (e: Exception) {
                        log.warn(e) { "Auto-remember thread failed: ${e.message}" }
                    }
                }
                break
            }
        }

        if (iterationCount >= maxIterations) {
            val errorMessage = createErrorMessage(
                conversationId,
                "Tool execution loop exceeded maximum iterations ($maxIterations)",
                "max_iterations"
            )
            emit(errorMessage)
        }
    }

    private fun createAssistantMessagesFromResponse(
        conversationId: Conversation.Id,
        chatResponse: ChatResponse,
    ): List<Conversation.Message> {
        val messages = mutableListOf<Conversation.Message>()
        
        log.info { "Processing ${chatResponse.results.size} generations from ChatResponse" }

        chatResponse.results.forEachIndexed { index, generation ->
            val assistantMsg = generation.output
            val content = mutableListOf<ContentItem>()
            
            // Check if this is a thinking block (Spring AI sets "signature" metadata for thinking)
            val metadata = assistantMsg.metadata
            val signature = metadata["signature"] as? String
            val isThinking = signature != null || metadata.containsKey("thinking")
            
            log.info { 
                "Generation[$index]: isThinking=$isThinking, " +
                "hasText=${!assistantMsg.text.isNullOrBlank()}, " +
                "textLength=${assistantMsg.text?.length ?: 0}, " +
                "toolCallsCount=${assistantMsg.toolCalls.size}, " +
                "metadata_keys=${metadata.keys}"
            }
            
            if (isThinking) {
                val thinkingText = assistantMsg.text ?: ""
                log.info { "THINKING BLOCK DETECTED: signature=${signature?.take(20)}..., text_length=${thinkingText.length}" }
                log.debug { "Thinking content preview: ${thinkingText.take(200)}..." }
                
                content.add(
                    ContentItem.Thinking(
                        thinking = thinkingText,
                        signature = signature,
                        state = BlockState.COMPLETE
                    )
                )
            } else if (!assistantMsg.text.isNullOrBlank()) {
                log.info { "TEXT BLOCK DETECTED: text_length=${assistantMsg.text!!.length}" }
                content.add(
                    ContentItem.AssistantMessage(
                        structured = Conversation.Message.StructuredText(fullText = assistantMsg.text ?: ""),
                        state = BlockState.COMPLETE
                    )
                )
            }

            assistantMsg.toolCalls.forEach { toolCall ->
                val input = try {
                    Json.parseToJsonElement(toolCall.arguments())
                } catch (e: Exception) {
                    log.error(e) { "Failed to parse tool call arguments for ${toolCall.name()}: ${toolCall.arguments()}" }
                    JsonObject(
                        mapOf(
                            "error" to kotlinx.serialization.json.JsonPrimitive("Parse error: ${e.message}"),
                            "raw" to kotlinx.serialization.json.JsonPrimitive(toolCall.arguments())
                        )
                    )
                }
                content.add(
                    ContentItem.ToolCall(
                        id = ContentItem.ToolCall.Id(toolCall.id()),
                        call = ContentItem.ToolCall.Data(
                            name = toolCall.name(),
                            input = input
                        ),
                        state = BlockState.COMPLETE
                    )
                )
            }
            
            // Create separate message for each generation (Anthropic requires separate messages)
            if (content.isNotEmpty()) {
                val message = Conversation.Message(
                    id = Conversation.Message.Id(uuid7()),
                    conversationId = conversationId,
                    role = Conversation.Message.Role.ASSISTANT,
                    content = content,
                    createdAt = Clock.System.now()
                )
                messages.add(message)
                log.info { "Created message with ${content.size} content items: ${content.map { it::class.simpleName }}" }
            } else {
                log.warn { "Generation[$index]: SKIPPED - no content to add" }
            }
        }
        
        log.info { "Created ${messages.size} separate messages from ${chatResponse.results.size} generations" }

        return messages
    }

    private fun createErrorMessage(
        conversationId: Conversation.Id,
        message: String,
        type: String = "error",
    ): Conversation.Message {
        return Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.SYSTEM,
            content = listOf(
                ContentItem.System(
                    level = ContentItem.System.SystemLevel.ERROR,
                    content = message,
                    state = BlockState.COMPLETE
                )
            ),
            createdAt = Clock.System.now(),
            error = Conversation.Message.GenerationError(message = message, type = type)
        )
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
        if (knowledgeGraphService == null) {
            log.warn { "KnowledgeGraphService not available (knowledge-graph.enabled=false)" }
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

            val result = knowledgeGraphService.extractAndSaveToGraph(threadMessages)
            log.info { "Knowledge graph update result: $result" }
        } catch (e: Exception) {
            log.error(e) { "Failed to add thread to graph for conversation $conversationId: ${e.message}" }
            throw e
        }
    }

    private fun extractGraphTextContent(message: Conversation.Message): String {
        return message.content.mapNotNull { contentItem ->
            when (contentItem) {
                is ContentItem.UserMessage -> contentItem.text
                is ContentItem.AssistantMessage -> contentItem.structured.fullText
                else -> null
            }
        }.joinToString("\\n")
    }
}
