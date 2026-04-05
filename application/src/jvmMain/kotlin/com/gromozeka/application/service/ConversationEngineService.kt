package com.gromozeka.application.service

import com.gromozeka.domain.model.AIProvider
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.BlockState
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.Plan
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiAutoCompaction
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.repository.ThreadRepository
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import com.gromozeka.domain.tool.ToolExecutionContext
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
    private val aiRuntimeProvider: AiRuntimeProvider,
    private val aiToolProvider: AiToolProvider,
    private val agentDomainService: AgentDomainService,
    private val toolApprovalService: ToolApprovalService,
    private val parallelToolExecutor: ParallelToolExecutor,
    private val conversationService: ConversationDomainService,
    private val threadRepository: ThreadRepository,
    private val threadMessageRepository: com.gromozeka.domain.repository.ThreadMessageRepository,
    private val tokenUsageStatisticsRepository: TokenUsageStatisticsRepository,
    private val coroutineScope: CoroutineScope,
    private val vectorMemoryService: com.gromozeka.domain.service.VectorMemoryService,
    private val knowledgeGraphService: com.gromozeka.domain.service.KnowledgeGraphService?,
    private val toolCallSequenceFixerService: ToolCallSequenceFixerService,
    private val settingsProvider: com.gromozeka.domain.service.SettingsProvider,
    private val strideEngineService: com.gromozeka.domain.service.StrideEngineService,
    private val planRepository: com.gromozeka.domain.repository.PlanRepository
) {
    private val log = KLoggers.logger(this)

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
        
        val runtime = aiRuntimeProvider.getRuntime(
            provider,
            modelName,
            project.path
        )
        val availableTools = aiToolProvider.getTools()

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

        val finalMessages = conversationService.loadCurrentMessages(conversationId)
        val systemPrompts = agentDomainService.assembleSystemPrompt(agent, project)

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

            val toolChoice = when {
                // Plan completed → normal mode (LLM decides, no forced tools)
                conversation.strideEnabled && activePlan == null && hasCompletedPlans -> {
                    log.info { "Stride Engine: plan completed, switching to normal mode" }
                    AiToolChoice.Auto
                }

                // No plans at all → force create_plan tool
                conversation.strideEnabled && activePlan == null -> {
                    log.info { "Stride Engine: PLANNING state - forcing create_plan tool" }
                    AiToolChoice.RequiredTool("create_plan")
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

                    AiToolChoice.RequiredAny
                }
                
                // Normal mode (no Stride) → auto (LLM decides)
                else -> AiToolChoice.Auto
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
            
            val runtimeRequest = AiRuntimeRequest(
                systemPrompts = systemPrompts,
                messages = messagesWithStrideInstruction,
                tools = availableTools,
                options = AiRuntimeOptions(
                    maxTokens = agent.maxTokens,
                    thinking = agent.thinking,
                    outputConfig = agent.outputConfig,
                    autoCompaction = runtime.capabilities.supportsAutoCompaction
                        .takeIf { it }
                        ?.let { AiAutoCompaction() },
                    toolChoice = toolChoice,
                    toolContext = mapOf(
                        "projectPath" to project.path,
                        "conversationId" to conversationId.value,
                        "planId" to activePlan?.id?.value,
                        "aiProvider" to provider.name,
                        "modelName" to modelName
                    )
                )
            )

            val runtimeResponse = try {
                log.info { "Calling LLM runtime: model=$modelName, provider=$provider, iteration=$iterationCount" }
                runtime.call(runtimeRequest)
            } catch (e: Exception) {
                log.error(e) { "Chat call error" }
                val errorMessage = AiConversationMessageMapper.createErrorMessage(conversationId, e.message ?: "Unknown error")
                emit(errorMessage)
                conversationService.addMessage(conversationId, errorMessage)
                break
            }

            log.info {
                "Runtime response received: assistantMessages=${runtimeResponse.messages.size}, " +
                    "toolCalls=${runtimeResponse.toolCalls.size}, finishReason=${runtimeResponse.finishReason}"
            }

            try {
                if (runtimeResponse.messages.isEmpty() && runtimeResponse.toolCalls.isEmpty()) {
                    log.warn { "Empty response from AI runtime" }
                    break
                }
            } catch (e: Exception) {
                log.error(e) { "Runtime response processing error" }
                break
            }

            val allToolCalls = runtimeResponse.toolCalls
            val hasToolCalls = allToolCalls.isNotEmpty()

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
                    val violationMessage = AiConversationMessageMapper.createErrorMessage(
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

            val assistantMessages = AiConversationMessageMapper.toConversationMessages(conversationId, runtimeResponse)

            // Save usage statistics with reference to the last assistant message
            val lastAssistantMessage = assistantMessages.lastOrNull()
            if (lastAssistantMessage != null) {
                runtimeResponse.usage?.let { usage ->
                    try {
                        val totalInputTokens = usage.totalInputTokens
                        val totalOutputTokens = usage.totalOutputTokens
                        
                        log.info { 
                            "Tokens: prompt=${usage.promptTokens} (new), cache_creation=${usage.cacheCreationTokens}, cache_read=${usage.cacheReadTokens}, " +
                            "total_input=$totalInputTokens, completion=${usage.completionTokens}, thinking=${usage.thinkingTokens}, " +
                            "total_output=$totalOutputTokens, total=${totalInputTokens + totalOutputTokens}"
                        }
                        
                        if (usage.thinkingTokens > 0) {
                            log.info { "Extended thinking was used: ${usage.thinkingTokens} thinking tokens generated" }
                        }

                        tokenUsageStatisticsRepository.save(
                            TokenUsageStatistics(
                                id = TokenUsageStatistics.Id(uuid7()),
                                threadId = conversation.currentThread,
                                lastMessageId = lastAssistantMessage.id,
                                timestamp = Clock.System.now(),
                                promptTokens = usage.promptTokens,
                                completionTokens = usage.completionTokens,
                                cacheCreationTokens = usage.cacheCreationTokens,
                                cacheReadTokens = usage.cacheReadTokens,
                                thinkingTokens = usage.thinkingTokens,
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
                val toolContext = ToolExecutionContext(
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
            val errorMessage = AiConversationMessageMapper.createErrorMessage(
                conversationId,
                "Tool execution loop exceeded maximum iterations ($maxIterations)",
                "max_iterations"
            )
            emit(errorMessage)
        }
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
