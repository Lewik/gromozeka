package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.BlockState
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.Plan
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.application.service.memory.MEMORY_ENRICH_CONTEXT_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_REMEMBER_TOOL_NAME
import com.gromozeka.application.service.memory.MemoryMessageRoutingApplicationService
import com.gromozeka.application.service.memory.MemoryToolResultRenderer
import com.gromozeka.application.service.memory.withoutMemoryManagementTools
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult
import com.gromozeka.domain.repository.AiModelSpecRepository
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.repository.ThreadRepository
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import com.gromozeka.domain.tool.ToolExecutionContext
import org.springframework.stereotype.Service
import com.gromozeka.shared.uuid.uuid7
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.springframework.beans.factory.annotation.Qualifier

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
 * 6. Auto-remember thread to typed memory if enabled (after final response)
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
    @Qualifier("supervisorScope") private val coroutineScope: CoroutineScope,
    private val memoryApplicationService: MemoryApplicationService,
    private val memoryMessageRoutingApplicationService: MemoryMessageRoutingApplicationService,
    private val toolCallSequenceFixerService: ToolCallSequenceFixerService,
    private val settingsProvider: com.gromozeka.domain.service.SettingsProvider,
    private val aiModelSpecRepository: AiModelSpecRepository,
    private val strideEngineService: com.gromozeka.domain.service.StrideEngineService,
    private val planRepository: com.gromozeka.domain.repository.PlanRepository
) : ConversationRuntimeService {
    private val log = KLoggers.logger(this)

    /**
     * Send message and get response using blocking call().
     * LLM-agnostic - works with all providers (Anthropic, OpenAI, Google, etc.)
     *
     * Automatically uses typed memory around a turn only when enabled by settings:
     * - user profile memory settings write current turn memory
     * - user profile memory settings recall memory before the main model call
     *
     * @param conversationId The conversation to append messages to
     * @param userMessage The user message to send
     * @param agent The agent to use for this conversation (provides system prompts)
     * @return Flow of Message objects (assistant responses, tool results).
     *         Flow completes when conversation turn is done.
     */
    override suspend fun sendMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
    ): Flow<Conversation.Message> = flow {
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val project = conversationService.getProject(conversationId)
        val resolvedRuntime = settingsProvider.resolveAiRuntime(agent.runtimeSelection)
        val provider = resolvedRuntime.connection.kind.provider
        val modelName = resolvedRuntime.modelConfiguration.providerModelId
        log.info {
            "Agent config: name=${agent.name}, modelName=$modelName, provider=$provider, " +
                "runtimeOverrides=${agent.runtimeOverrides}"
        }
        
        val runtime = aiRuntimeProvider.getRuntime(
            agent.runtimeSelection,
            project.path
        )
        val autoCompactionThresholdTokens = if (runtime.capabilities.supportsAutoCompaction) {
            aiModelSpecRepository.find(provider, modelName)?.autoCompactionThresholdTokens.also { threshold ->
                if (threshold == null) {
                    log.warn { "Auto compaction disabled: context window is not configured for model=$modelName" }
                } else {
                    log.info { "Auto compaction configured: model=$modelName threshold=$threshold" }
                }
            }
        } else {
            null
        }
        val availableTools = aiToolProvider.getTools()
        val memoryPipelineTools = availableTools.withoutMemoryManagementTools()
        val baseSystemPrompts = agentDomainService.assembleSystemPrompt(agent, project)
        val systemPrompts = baseSystemPrompts
        val automaticMemoryRememberEnabled = settingsProvider.userProfile.memorySettings.autoRemember
        val automaticMemoryRecallEnabled = settingsProvider.userProfile.memorySettings.autoRecall

        // Add user message
        conversationService.addMessage(conversationId, userMessage)
        val writeResult = if (automaticMemoryRememberEnabled) {
            routeMessageThroughMemoryRouter(conversationId, conversation.currentThread, userMessage, agent, project, systemPrompts, memoryPipelineTools)
        } else {
            null
        }
        if (automaticMemoryRememberEnabled) {
            buildSyntheticMemoryToolPair(
                conversationId = conversationId,
                targetMessage = userMessage,
                toolName = MEMORY_REMEMBER_TOOL_NAME,
                arguments = buildJsonObject {
                    put("target", "previous_user_message")
                    put("target_message_id", userMessage.id.value)
                    put("mode", "automatic_hot_path")
                },
                resultText = MemoryToolResultRenderer.rememberResultJsonString(writeResult)
            ).forEach { syntheticMessage ->
                emit(syntheticMessage)
                conversationService.addMessage(conversationId, syntheticMessage)
            }
        }

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

        val activeConversationAfterWrite = conversationService.findById(conversationId) ?: conversation
        val messagesBeforeRecall = conversationService.loadCurrentMessages(conversationId)
        val runtimeMemoryResult = if (automaticMemoryRecallEnabled) {
            runCatching {
                memoryApplicationService.buildRuntimeMemoryReadResult(
                    conversationId = conversationId,
                    threadId = activeConversationAfterWrite.currentThread,
                    targetMessage = userMessage,
                    threadMessages = messagesBeforeRecall,
                    agent = agent,
                    project = project,
                    runtimeSystemPrompts = systemPrompts,
                    runtimeTools = memoryPipelineTools,
                )
            }.onFailure { error ->
                log.warn(error) {
                    "Memory runtime recall failed: conversation=${conversationId.value} target=${userMessage.id.value} error=${error.message}"
                }
            }.getOrNull()
        } else {
            null
        }
        if (automaticMemoryRecallEnabled) {
            buildSyntheticMemoryToolPair(
                conversationId = conversationId,
                targetMessage = userMessage,
                toolName = MEMORY_ENRICH_CONTEXT_TOOL_NAME,
                arguments = buildJsonObject {
                    put("target", "previous_user_message")
                    put("target_message_id", userMessage.id.value)
                    put("mode", "automatic_runtime_context_enrichment")
                },
                resultText = MemoryToolResultRenderer.enrichContextResultJsonString(runtimeMemoryResult)
            ).forEach { syntheticMessage ->
                emit(syntheticMessage)
                conversationService.addMessage(conversationId, syntheticMessage)
            }
        }
        val finalMessages = conversationService.loadCurrentMessages(conversationId)
        log.info {
            "Memory auto wiring: conversation=${conversationId.value} autoRemember=$automaticMemoryRememberEnabled autoRecall=$automaticMemoryRecallEnabled " +
                "memoryPromptPresent=${!runtimeMemoryResult?.runtimePrompt.isNullOrBlank()} memoryPromptChars=${runtimeMemoryResult?.runtimePrompt?.length ?: 0} " +
                "systemPrompts=${systemPrompts.size} rememberTriggered=$automaticMemoryRememberEnabled recallTriggered=$automaticMemoryRecallEnabled"
        }

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
                
                // Find target USER message
                val lastUserMessageIndex = currentMessages.indexOfFirst { it.id == userMessage.id }
                
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
                    maxOutputTokens = agent.runtimeOverrides.maxOutputTokens,
                    reasoning = agent.runtimeOverrides.reasoning,
                    autoCompactionThresholdTokens = autoCompactionThresholdTokens,
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
                if (automaticMemoryRememberEnabled) {
                    routeMessageThroughMemoryRouter(conversationId, conversation.currentThread, errorMessage, agent, project, systemPrompts, memoryPipelineTools)
                }
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

            // Stride Engine: Violation Detection
            // If plan is ACTIVE but LLM didn't call any tools → protocol violation
            if (allToolCalls.isEmpty() && activePlan != null) {
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
                    if (automaticMemoryRememberEnabled) {
                        routeMessageThroughMemoryRouter(conversationId, conversation.currentThread, violationMessage, agent, project, systemPrompts, memoryPipelineTools)
                    }
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
                                provider = provider.name,
                                modelId = modelName
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
                if (automaticMemoryRememberEnabled) {
                    routeMessageThroughMemoryRouter(conversationId, conversation.currentThread, message, agent, project, systemPrompts, memoryPipelineTools)
                }
            }

            if (allToolCalls.isNotEmpty()) {
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
                if (automaticMemoryRememberEnabled) {
                    routeMessageThroughMemoryRouter(conversationId, conversation.currentThread, toolResultMessage, agent, project, systemPrompts, memoryPipelineTools)
                }

                if (executionResult.returnDirect) {
                    break
                }
            } else {
                log.info {
                    "Legacy memory inline ingest disabled: conversation=${conversationId.value} " +
                        "thread=${conversation.currentThread.value}; router-only per-message pipeline is active"
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

    private suspend fun routeMessageThroughMemoryRouter(
        conversationId: Conversation.Id,
        threadId: Conversation.Thread.Id,
        message: Conversation.Message,
        agent: AgentDefinition,
        project: Project,
        systemPrompts: List<String>,
        tools: List<AiToolCallback>,
    ): DirectStructuredMemoryWriteResult? {
        return runCatching {
            memoryMessageRoutingApplicationService.routeMessage(
                conversationId = conversationId,
                threadId = threadId,
                message = message,
                agent = agent,
                project = project,
                runtimeSystemPrompts = systemPrompts,
                runtimeTools = tools,
            )
        }.onFailure { error ->
            val failed = "Memory router trigger failed: conversation=${conversationId.value} message=${message.id.value} role=${message.role} error=${error.message}"
            log.warn(error) { failed }
            println(failed)
            if (java.lang.Boolean.getBoolean("gromozeka.memory.routing.failFast")) {
                throw error
            }
        }.getOrNull()
    }

    /**
     * Process current thread into the new knowledge memory.
     */
    override suspend fun rememberCurrentThread(conversationId: Conversation.Id) {
        memoryApplicationService.ingestCurrentThread(conversationId)
        log.info { "Processed thread into typed memory for conversation $conversationId" }
    }

    override suspend fun consolidateCurrentMemory(conversationId: Conversation.Id) {
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val agent = agentDomainService.findById(conversation.agentDefinitionId)
            ?: throw IllegalStateException("Agent not found: ${conversation.agentDefinitionId}")
        consolidateCurrentMemory(conversationId, agent)
    }

    suspend fun consolidateCurrentMemory(
        conversationId: Conversation.Id,
        agent: AgentDefinition,
    ) {
        val project = conversationService.getProject(conversationId)
        val systemPrompts = agentDomainService.assembleSystemPrompt(agent, project)
        val memoryPipelineTools = aiToolProvider.getTools().withoutMemoryManagementTools()
        memoryApplicationService.runNoteConsolidation(
            conversationId = conversationId,
            agent = agent,
            project = project,
            runtimeSystemPrompts = systemPrompts,
            runtimeTools = memoryPipelineTools,
        )
        log.info { "Ran memory consolidation for conversation $conversationId" }
    }

    override suspend fun repairCurrentMemory(conversationId: Conversation.Id) {
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val agent = agentDomainService.findById(conversation.agentDefinitionId)
            ?: throw IllegalStateException("Agent not found: ${conversation.agentDefinitionId}")
        repairCurrentMemory(conversationId, agent)
    }

    suspend fun repairCurrentMemory(
        conversationId: Conversation.Id,
        agent: AgentDefinition,
    ) {
        val project = conversationService.getProject(conversationId)
        val systemPrompts = agentDomainService.assembleSystemPrompt(agent, project)
        val memoryPipelineTools = aiToolProvider.getTools().withoutMemoryManagementTools()
        memoryApplicationService.runMemoryRepair(
            conversationId = conversationId,
            agent = agent,
            project = project,
            runtimeSystemPrompts = systemPrompts,
            runtimeTools = memoryPipelineTools,
        )
        log.info { "Ran memory repair for conversation $conversationId" }
    }

    override suspend fun maintainMemoryEntities(conversationId: Conversation.Id) {
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val agent = agentDomainService.findById(conversation.agentDefinitionId)
            ?: throw IllegalStateException("Agent not found: ${conversation.agentDefinitionId}")
        maintainMemoryEntities(conversationId, agent)
    }

    suspend fun maintainMemoryEntities(
        conversationId: Conversation.Id,
        agent: AgentDefinition,
    ) {
        val project = conversationService.getProject(conversationId)
        val systemPrompts = agentDomainService.assembleSystemPrompt(agent, project)
        val memoryPipelineTools = aiToolProvider.getTools().withoutMemoryManagementTools()
        memoryApplicationService.runEntityMaintenance(
            conversationId = conversationId,
            agent = agent,
            project = project,
            runtimeSystemPrompts = systemPrompts,
            runtimeTools = memoryPipelineTools,
        )
        log.info { "Ran memory entity maintenance for conversation $conversationId" }
    }

    override suspend fun applyCurrentMemoryRetention(conversationId: Conversation.Id) {
        val project = conversationService.getProject(conversationId)
        memoryApplicationService.runRetention(conversationId, project)
        log.info { "Ran memory retention for conversation $conversationId" }
    }

    private fun buildSyntheticMemoryToolPair(
        conversationId: Conversation.Id,
        targetMessage: Conversation.Message,
        toolName: String,
        arguments: JsonObject,
        resultText: String,
    ): List<Conversation.Message> {
        val toolCallId = ContentItem.ToolCall.Id("mem_${uuid7().replace("-", "")}")
        val createdAt = Clock.System.now()
        val toolCallMessage = Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(
                ContentItem.ToolCall(
                    id = toolCallId,
                    call = ContentItem.ToolCall.Data(
                        name = toolName,
                        input = arguments,
                    ),
                    state = BlockState.COMPLETE,
                )
            ),
            providerMetadata = buildJsonObject {
                put("synthetic", true)
                put("syntheticKind", "memory")
                put("targetMessageId", targetMessage.id.value)
            },
            createdAt = createdAt,
        )

        val toolResultMessage = Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(
                ContentItem.ToolResult(
                    toolUseId = toolCallId,
                    toolName = toolName,
                    result = listOf(ContentItem.ToolResult.Data.Text(resultText)),
                    isError = false,
                    state = BlockState.COMPLETE,
                )
            ),
            providerMetadata = buildJsonObject {
                put("synthetic", true)
                put("syntheticKind", "memory")
                put("targetMessageId", targetMessage.id.value)
            },
            createdAt = createdAt,
        )

        return listOf(toolCallMessage, toolResultMessage)
    }
}
