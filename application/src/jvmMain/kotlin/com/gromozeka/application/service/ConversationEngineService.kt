package com.gromozeka.application.service

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.BlockState
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.Plan
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
    private val applicationContext: org.springframework.context.ApplicationContext,
    private val mcpToolProvider: McpToolProvider,
    private val tokenUsageStatisticsRepository: TokenUsageStatisticsRepository,
    private val coroutineScope: CoroutineScope,
    private val vectorMemoryService: com.gromozeka.domain.service.VectorMemoryService,
    private val knowledgeGraphService: com.gromozeka.domain.service.KnowledgeGraphService?,
    private val toolCallPairingService: ToolCallPairingService,
    private val toolCallSequenceFixerService: ToolCallSequenceFixerService,
    private val settingsProvider: com.gromozeka.domain.service.SettingsProvider,
    private val strideEngineService: com.gromozeka.domain.service.StrideEngineService,
    private val planRepository: com.gromozeka.domain.repository.PlanRepository
) {
    private val log = KLoggers.logger(this)

    /**
     * Collect all available tools for user-controlled tool execution.
     * Tools are passed in runtime options to ToolCallingManager.
     * 
     * Uses dynamic lookup through ApplicationContext to ensure all registered tools are included,
     * including those registered after ConversationEngineService construction (Stride tools, internal MCP tools).
     */
    private fun collectToolOptions(projectPath: String?, provider: AIProvider, agent: AgentDefinition): ChatOptions {
        log.debug { "collectToolOptions: provider=$provider, agent=${agent.name}" }
        
        val allCallbacks = mutableListOf<org.springframework.ai.tool.ToolCallback>()
        val allNames = mutableSetOf<String>()

        // Get ALL registered ToolCallback beans dynamically from ApplicationContext
        // This includes: built-in tools, Stride tools, internal MCP tools
        // (registered via @Bean methods, ToolsRegistrationConfig, InternalMcpToolsRegistrar)
        val registeredCallbacks = applicationContext.getBeansOfType(org.springframework.ai.tool.ToolCallback::class.java).values.toList()
        allCallbacks.addAll(registeredCallbacks)
        allNames.addAll(registeredCallbacks.map { it.toolDefinition.name() })
        log.info { "Registered ToolCallback beans: ${registeredCallbacks.size}" }

        // External MCP tools from MCP servers
        val mcpCallbacks = mcpToolProvider.getToolCallbacks()
        allCallbacks.addAll(mcpCallbacks)
        allNames.addAll(mcpCallbacks.map { it.toolDefinition.name() })
        log.info { "External MCP tools: ${mcpCallbacks.size}" }

        log.info { "Total tools for runtime options: ${allCallbacks.size}" }
        log.info { "Tool names: ${allNames.sorted()}" }

        // Use Anthropic-specific options for caching support
        return when (provider) {
            AIProvider.ANTHROPIC -> {
                val cacheOptions = AnthropicCacheOptions.builder()
                    .strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
                    .build()

                log.info { "Creating AnthropicChatOptions with caching strategy: ${cacheOptions.strategy}" }

                val builder = AnthropicChatOptions.builder()
                    .toolCallbacks(allCallbacks)
                    .toolNames(allNames)
                    .internalToolExecutionEnabled(false)
                    .toolContext(mapOf("projectPath" to projectPath))
                    .cacheOptions(cacheOptions)

                // Apply maxTokens from agent definition
                agent.maxTokens?.let { builder.maxTokens(it) }

                // Apply thinking configuration from agent definition
                agent.thinking?.let { thinkingConfig ->
                    when (thinkingConfig.type) {
                        "adaptive", "enabled" -> {
                            val budgetTokens = thinkingConfig.budgetTokens
                                ?: (agent.maxTokens?.let { it / 2 } ?: 16_000)
                            builder.thinking(
                                org.springframework.ai.anthropic.api.AnthropicApi.ThinkingType.ENABLED,
                                budgetTokens
                            )
                        }
                        "disabled" -> {
                            builder.thinking(
                                org.springframework.ai.anthropic.api.AnthropicApi.ThinkingType.DISABLED,
                                null
                            )
                        }
                    }
                }

                // Pass thinking type and effort to AdaptiveThinkingInterceptor via HTTP headers
                val httpHeaders = mutableMapOf<String, String>()
                agent.thinking?.let { thinkingConfig ->
                    httpHeaders["X-Gromozeka-Thinking-Type"] = thinkingConfig.type
                }
                agent.outputConfig?.let { outputConfig ->
                    httpHeaders["X-Gromozeka-Effort"] = outputConfig.effort
                }
                if (httpHeaders.isNotEmpty()) {
                    builder.httpHeaders(httpHeaders)
                }

                builder.build()
                    .also { log.debug { "AnthropicChatOptions created with ${allCallbacks.size} tools, cache enabled, thinking=${agent.thinking}, effort=${agent.outputConfig}" } }
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
        
        val modelName = agent.modelName
        log.info { "Agent config: name=${agent.name}, modelName=$modelName, provider=$provider, maxTokens=${agent.maxTokens}, thinking=${agent.thinking}, outputConfig=${agent.outputConfig}" }
        
        val chatModel = chatModelProvider.getChatModel(
            provider,
            modelName,
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
        val baseToolOptions = collectToolOptions(project.path, provider, agent)

        var iterationCount = 0
        val maxIterations = 200
        val maxStrideIterations = 50 // Separate limit for Stride mode to prevent infinite loops

        while (iterationCount < maxIterations) {
            iterationCount++

            // Stride Engine: Domain-Driven State Machine
            // Check active plan state BEFORE each LLM call to determine tool_choice
            val activePlan = if (conversation.strideEnabled) {
                strideEngineService.findActivePlan(conversationId)
            } else {
                null
            }

            // Stride Engine: Force tool execution based on plan state (not iteration count)
            // Three states: PLANNING (no plans yet), STEPPING (active plan), NORMAL (plan completed)
            val hasCompletedPlans = if (conversation.strideEnabled && activePlan == null) {
                planRepository.findByConversationId(conversationId).isNotEmpty()
            } else false

            val toolOptions = when {
                // Plan completed → normal mode (LLM decides, no forced tools)
                conversation.strideEnabled && activePlan == null && hasCompletedPlans -> {
                    log.info { "Stride Engine: plan completed, switching to normal mode" }
                    baseToolOptions
                }

                // No plans at all → force create_plan tool
                conversation.strideEnabled && activePlan == null -> {
                    log.info { "Stride Engine: PLANNING state - forcing create_plan tool" }
                    when (baseToolOptions) {
                        is AnthropicChatOptions -> {
                            baseToolOptions.copy().apply {
                                toolChoice = AnthropicApi.ToolChoiceTool("create_plan")
                            }
                        }
                        is OpenAiChatOptions -> {
                            // OpenAI: tool_choice = "required" forces ANY tool, not specific one
                            // Need to filter toolCallbacks to only create_plan
                            log.warn { "Stride Engine: OpenAI forced tool choice not yet fully supported" }
                            baseToolOptions.copy().apply {
                                // This forces ANY tool, not specifically create_plan
                                // TODO: Filter toolCallbacks to only include create_plan
                            }
                        }
                        else -> {
                            log.warn { "Stride Engine: Provider ${provider} may not support forced tool choice" }
                            baseToolOptions
                        }
                    }
                }
                
                // Plan active → force ANY tool (keep stepping until plan completes)
                conversation.strideEnabled && activePlan != null -> {
                    log.info { "Stride Engine: STEPPING state - plan ${activePlan.id} active, forcing tool execution" }
                    
                    // Check iteration limit for Stride mode
                    if (iterationCount > maxStrideIterations) {
                        log.error { "Stride Engine: max iterations ($maxStrideIterations) exceeded with active plan ${activePlan.id}" }
                        try {
                            planRepository.updateStatus(activePlan.id, Plan.Status.FAILED)
                            log.info { "Stride Engine: marked plan ${activePlan.id} as FAILED due to max iterations" }
                        } catch (e: Exception) {
                            log.error(e) { "Failed to mark plan as failed: ${e.message}" }
                        }
                        break
                    }
                    
                    when (baseToolOptions) {
                        is AnthropicChatOptions -> {
                            baseToolOptions.copy().apply {
                                toolChoice = AnthropicApi.ToolChoiceAny() // Force ANY tool
                            }
                        }
                        is OpenAiChatOptions -> {
                            baseToolOptions.copy().apply {
                                // OpenAI: tool_choice = "required" forces tool execution
                                // Spring AI doesn't expose this directly, using toolChoice field
                                // TODO: Verify OpenAI compatibility
                            }
                        }
                        else -> {
                            log.warn { "Stride Engine: Provider ${provider} may not support forced ANY tool choice" }
                            baseToolOptions
                        }
                    }
                }
                
                // Normal mode (no Stride) → auto (LLM decides)
                else -> baseToolOptions
            }

            val currentMessages = if (iterationCount == 1) finalMessages else conversationService.loadCurrentMessages(conversationId)
            
            // Stride Engine: Add PLANNING instruction to last USER message (only if no plans exist yet)
            val messagesWithStrideInstruction = if (conversation.strideEnabled && activePlan == null && !hasCompletedPlans && iterationCount == 1) {
                log.info { "Stride Engine: Adding PLANNING instruction to user message" }
                
                // Find last USER message
                val lastUserMessageIndex = currentMessages.indexOfLast { it.role == Conversation.Message.Role.USER }
                
                if (lastUserMessageIndex >= 0) {
                    val lastUserMessage = currentMessages[lastUserMessageIndex]
                    val planningInstruction = Conversation.Message.Instruction.UserInstruction(
                        id = "stride_planning",
                        title = "Stride Planning",
                        description = "Create execution plan by calling create_plan tool"
                    )
                    
                    // Add instruction to message
                    val modifiedMessage = lastUserMessage.copy(
                        instructions = lastUserMessage.instructions + planningInstruction
                    )
                    
                    // Replace in list
                    currentMessages.toMutableList().apply {
                        set(lastUserMessageIndex, modifiedMessage)
                    }
                } else {
                    currentMessages
                }
            } else {
                currentMessages
            }
            
            val currentSpringHistory = messageConversionService.convertHistoryToSpringAI(messagesWithStrideInstruction)
            val currentPrompt = Prompt(systemMessages + currentSpringHistory, toolOptions)

            val chatResponse: ChatResponse
            try {
                log.info { "Calling LLM: model=$modelName, provider=$provider, iteration=$iterationCount" }
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

            // Stride Engine: Violation Detection
            // If plan is ACTIVE but LLM didn't call any tools → protocol violation
            if (!hasToolCalls && activePlan != null) {
                log.error { 
                    "STRIDE VIOLATION: Plan ${activePlan.id} is ACTIVE but LLM response contains no tool calls. " +
                    "This violates Stride Engine protocol (tool_choice should force tool execution). " +
                    "Marking plan as FAILED."
                }
                try {
                    planRepository.updateStatus(activePlan.id, Plan.Status.FAILED)
                    
                    // Emit error message to user
                    val violationMessage = createErrorMessage(
                        conversationId,
                        "Stride Engine protocol violation: Plan execution interrupted unexpectedly. " +
                        "The AI model stopped executing steps without properly completing the plan.",
                        "stride_violation"
                    )
                    emit(violationMessage)
                    conversationService.addMessage(conversationId, violationMessage)
                } catch (e: Exception) {
                    log.error(e) { "Failed to handle Stride violation: ${e.message}" }
                }
                break
            }

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

                // Use activePlan from loop scope (already fetched at iteration start)
                val toolContext = ToolContext(
                    mapOf(
                        "projectPath" to project.path,
                        "conversationId" to conversationId.value,
                        "planId" to activePlan?.id?.value,
                        "aiProvider" to provider.name,
                        "modelName" to modelName
                    )
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
                // Log tool call from LLM for debugging
                log.info { "LLM tool call: ${toolCall.name()}, arguments: ${toolCall.arguments()}" }
                
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
