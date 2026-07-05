package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.BlockState
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.application.service.memory.MEMORY_ENRICH_CONTEXT_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_REMEMBER_TOOL_NAME
import com.gromozeka.application.service.memory.MemoryMaintenanceAction
import com.gromozeka.application.service.memory.MemoryMaintenanceQueue
import com.gromozeka.application.service.memory.MemoryMessageRoutingApplicationService
import com.gromozeka.application.service.memory.MemoryToolResultRenderer
import com.gromozeka.application.service.memory.defaultMemoryNamespace
import com.gromozeka.application.service.memory.withoutMemoryManagementTools
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult
import com.gromozeka.domain.repository.AiModelSpecRepository
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeWorkerAffinity
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import com.gromozeka.domain.tool.ToolExecutionContext
import org.springframework.stereotype.Service
import com.gromozeka.shared.uuid.uuid7
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.beans.factory.ObjectProvider

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
    private val tokenUsageStatisticsRepository: TokenUsageStatisticsRepository,
    private val memoryApplicationService: MemoryApplicationService,
    private val memoryMaintenanceQueue: MemoryMaintenanceQueue,
    private val memoryMessageRoutingApplicationService: MemoryMessageRoutingApplicationService,
    private val toolCallSequenceFixerService: ToolCallSequenceFixerService,
    private val settingsProvider: com.gromozeka.domain.service.SettingsProvider,
    private val aiModelSpecRepository: AiModelSpecRepository,
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeEventBus: ConversationRuntimeEventBus,
    private val runtimeDispatcher: ConversationRuntimeDispatcher,
    runtimeWorkerDescriptor: ConversationRuntimeWorkerDescriptor,
) : ConversationRuntimeService, ConversationRuntimeTaskRunner {
    private val log = KLoggers.logger(this)
    private val localAgentAffinity = runtimeWorkerDescriptor
        .affinities
        .sortedWith(compareBy<ConversationRuntimeWorkerAffinity> { it.kind.localToolPriority() }.thenBy { it.value })
        .firstOrNull()

    override suspend fun enqueueMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
        placement: QueuedMessagePlacement,
    ): Boolean = runtimeDispatcher.enqueueMessage(conversationId, userMessage, agent, placement)

    override suspend fun cancelQueuedMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean = runtimeDispatcher.cancelQueuedMessage(conversationId, messageId)

    override suspend fun controlExecution(
        conversationId: Conversation.Id,
        action: ConversationRuntimeControlAction,
    ): Boolean = runtimeDispatcher.controlExecution(conversationId, action)

    override suspend fun submitMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
    ): Boolean = runtimeDispatcher.submitMessage(conversationId, userMessage, agent)

    override fun observeConversation(
        conversationId: Conversation.Id,
        afterEventSequence: Long?,
    ): Flow<ConversationRuntimeEvent> =
        runtimeDispatcher.observeConversation(conversationId, afterEventSequence)

    override fun runRuntimeTask(
        task: ConversationRuntimeTask,
        workerId: String,
    ): Flow<Conversation.Message> =
        when (val payload = task.payload) {
            is ConversationRuntimeTask.Payload.UserTurn -> runUserTurnStep(task, workerId, payload)
            is ConversationRuntimeTask.Payload.LlmCall -> runLlmCallStep(task, workerId, payload)
            is ConversationRuntimeTask.Payload.ToolExecution -> runToolExecutionStep(task, workerId, payload)
        }

    private fun runUserTurnStep(
        task: ConversationRuntimeTask,
        workerId: String,
        payload: ConversationRuntimeTask.Payload.UserTurn,
    ): Flow<Conversation.Message> = flow {
        val conversationId = task.conversationId
        ensureRuntimeTaskOwner(conversationId, task.id, workerId)
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val context = buildConversationRuntimeContext(payload.agent, conversation)
        appendUserMessageWithAutomaticMemory(
            conversationId = conversationId,
            conversation = conversation,
            project = context.project,
            userMessage = payload.userMessage,
            agent = payload.agent,
            memorySystemPrompts = context.memorySystemPrompts,
            memoryPipelineTools = context.memoryPipelineTools,
            automaticMemoryRememberEnabled = context.automaticMemoryRememberEnabled,
            automaticMemoryRecallEnabled = context.automaticMemoryRecallEnabled,
            recallFailureLogPrefix = "Memory runtime recall failed",
        ).forEach { emit(it) }

        val fixResult = toolCallSequenceFixerService.fixNonSequentialPairs(conversationId)
        if (fixResult.fixed) {
            log.info {
                "Fixed non-sequential ToolCall/ToolResult pairs: " +
                "added ${fixResult.addedResults} error results, " +
                "converted ${fixResult.convertedResults} orphaned results"
            }
        }

        if (awaitExecutionCanContinue(conversationId)) {
            submitContinuationTask(
                llmCallTask(
                    conversationId = conversationId,
                    rootUserMessageId = payload.userMessage.id,
                    agent = payload.agent,
                    iteration = 1,
                )
            )
        }
    }

    private fun runLlmCallStep(
        task: ConversationRuntimeTask,
        workerId: String,
        payload: ConversationRuntimeTask.Payload.LlmCall,
    ): Flow<Conversation.Message> = flow {
        val conversationId = task.conversationId
        if (!awaitExecutionCanContinue(conversationId)) {
            return@flow
        }

        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val context = buildConversationRuntimeContext(payload.agent, conversation)

        if (payload.iteration > MAX_TOOL_LOOP_ITERATIONS) {
            val errorMessage = AiConversationMessageMapper.createErrorMessage(
                conversationId,
                "Tool execution loop exceeded maximum iterations ($MAX_TOOL_LOOP_ITERATIONS)",
                "max_iterations"
            ).copy(id = runtimeMessageId(task.id, "max-iterations"))
            val added = addRuntimeMessageIfMissing(conversationId, errorMessage)
            if (added) {
                emit(errorMessage)
            }
            if (added && context.automaticMemoryRememberEnabled) {
                routeMessageThroughMemoryRouter(
                    conversationId,
                    conversation.currentThread,
                    errorMessage,
                    payload.agent,
                    context.project,
                    context.memorySystemPrompts,
                    context.memoryPipelineTools,
                )
            }
            return@flow
        }

        val currentMessages = conversationService.loadCurrentMessages(conversationId)

        val runtimeRequest = AiRuntimeRequest(
            systemPrompts = context.runtimeSystemPrompts,
            messages = currentMessages,
            tools = context.availableTools,
            options = AiRuntimeOptions(
                maxOutputTokens = payload.agent.runtimeOverrides.maxOutputTokens,
                reasoning = payload.agent.runtimeOverrides.reasoning,
                autoCompactionThresholdTokens = context.autoCompactionThresholdTokens,
                toolChoice = AiToolChoice.Auto,
                responseFormat = AssistantResponseFormatContract.runtimeResponseFormat(context.assistantResponseFormat),
                assistantResponseFormat = context.assistantResponseFormat,
                toolContext = mapOf(
                    "projectPath" to context.project.path,
                    "conversationId" to conversationId.value,
                    "threadId" to conversation.currentThread.value,
                    "projectId" to context.project.id.value,
                    "aiProvider" to context.provider.name,
                    "modelName" to context.modelName
                )
            )
        )

        val runtimeResponse = try {
            log.info { "Calling LLM runtime: model=${context.modelName}, provider=${context.provider}, iteration=${payload.iteration}" }
            ensureRuntimeTaskOwner(conversationId, task.id, workerId)
            context.runtime.call(runtimeRequest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Chat call error" }
            if (java.lang.Boolean.getBoolean("gromozeka.memory.routing.failFast")) {
                throw e
            }
            ensureRuntimeTaskOwner(conversationId, task.id, workerId)
            val errorMessage = AiConversationMessageMapper
                .createErrorMessage(conversationId, e.message ?: "Unknown error")
                .copy(id = runtimeMessageId(task.id, "llm-error"))
            val added = addRuntimeMessageIfMissing(conversationId, errorMessage)
            if (added) {
                emit(errorMessage)
            }
            if (added && context.automaticMemoryRememberEnabled) {
                routeMessageThroughMemoryRouter(
                    conversationId,
                    conversation.currentThread,
                    errorMessage,
                    payload.agent,
                    context.project,
                    context.memorySystemPrompts,
                    context.memoryPipelineTools,
                )
            }
            return@flow
        }

        log.info {
            "Runtime response received: assistantMessages=${runtimeResponse.messages.size}, " +
                "toolCalls=${runtimeResponse.toolCalls.size}, finishReason=${runtimeResponse.finishReason}"
        }
        ensureRuntimeTaskOwner(conversationId, task.id, workerId)
        if (!awaitExecutionCanContinue(conversationId)) {
            return@flow
        }

        if (runtimeResponse.messages.isEmpty() && runtimeResponse.toolCalls.isEmpty()) {
            log.warn { "Empty response from AI runtime" }
            return@flow
        }

        val allToolCalls = runtimeResponse.toolCalls
        val assistantMessages = AiConversationMessageMapper
            .toConversationMessages(conversationId, runtimeResponse)
            .withRuntimeMessageIds(task.id, "assistant")
        ensureRuntimeTaskOwner(conversationId, task.id, workerId)

        assistantMessages.forEach { message ->
            ensureRuntimeTaskOwner(conversationId, task.id, workerId)
            val added = addRuntimeMessageIfMissing(conversationId, message)
            if (added) {
                emit(message)
            }
            if (added && context.automaticMemoryRememberEnabled) {
                routeMessageThroughMemoryRouter(
                    conversationId,
                    conversation.currentThread,
                    message,
                    payload.agent,
                    context.project,
                    context.memorySystemPrompts,
                    context.memoryPipelineTools,
                )
            }
        }
        saveTokenUsage(conversation, context, assistantMessages.lastOrNull(), runtimeResponse.usage)

        if (allToolCalls.isNotEmpty()) {
            submitContinuationTask(
                toolExecutionTask(
                    conversationId = conversationId,
                    rootUserMessageId = payload.rootUserMessageId,
                    agent = payload.agent,
                    iteration = payload.iteration,
                    toolCalls = allToolCalls,
                    availableTools = context.availableTools,
                )
            )
        } else {
            log.info {
                "Legacy memory inline ingest disabled: conversation=${conversationId.value} " +
                    "thread=${conversation.currentThread.value}; router-only per-message pipeline is active"
            }
        }
    }

    private fun runToolExecutionStep(
        task: ConversationRuntimeTask,
        workerId: String,
        payload: ConversationRuntimeTask.Payload.ToolExecution,
    ): Flow<Conversation.Message> = flow {
        val conversationId = task.conversationId
        if (!awaitExecutionCanContinue(conversationId)) {
            return@flow
        }

        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val context = buildConversationRuntimeContext(payload.agent, conversation)

        val approvalResult = toolApprovalService.approve(payload.toolCalls)
        if (approvalResult is ApprovalResult.Rejected) {
            log.warn { "Tool calls rejected: ${approvalResult.reason}" }
            return@flow
        }

        val toolContext = ToolExecutionContext(
            mapOf(
                "projectPath" to context.project.path,
                "conversationId" to conversationId.value,
                "threadId" to conversation.currentThread.value,
                "projectId" to context.project.id.value,
                "aiProvider" to context.provider.name,
                "modelName" to context.modelName
            )
        )
        ensureRuntimeTaskOwner(conversationId, task.id, workerId)
        clearRuntimeToolExecutions(conversationId, task.id, workerId)
        val executionResult = parallelToolExecutor.executeParallel(
            toolCalls = payload.toolCalls,
            toolContext = toolContext,
            runtimeTaskId = task.id,
            workerId = workerId,
            onToolExecutionChanged = { execution ->
                upsertRuntimeToolExecution(conversationId, execution)
            },
        )
        ensureRuntimeTaskOwner(conversationId, task.id, workerId)

        val toolResultMessage = Conversation.Message(
            id = runtimeMessageId(task.id, "result"),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = executionResult.results,
            createdAt = Clock.System.now()
        )
        val toolResultAdded = addRuntimeMessageIfMissing(conversationId, toolResultMessage)
        if (toolResultAdded) {
            emit(toolResultMessage)
        }
        ensureRuntimeTaskOwner(conversationId, task.id, workerId)
        clearRuntimeToolExecutions(conversationId, task.id, workerId)
        if (toolResultAdded && context.automaticMemoryRememberEnabled) {
            routeMessageThroughMemoryRouter(
                conversationId,
                conversation.currentThread,
                toolResultMessage,
                payload.agent,
                context.project,
                context.memorySystemPrompts,
                context.memoryPipelineTools,
            )
        }
        if (!awaitExecutionCanContinue(conversationId) || executionResult.returnDirect) {
            return@flow
        }

        emitQueuedRuntimeMessagesAtSafePoint(
            conversationId = conversationId,
            runtimeTaskId = task.id,
            workerId = workerId,
            placement = QueuedMessagePlacement.AFTER_TOOL_RESULT,
            conversation = conversation,
            project = context.project,
            memorySystemPrompts = context.memorySystemPrompts,
            memoryPipelineTools = context.memoryPipelineTools,
            automaticMemoryRememberEnabled = context.automaticMemoryRememberEnabled,
            automaticMemoryRecallEnabled = context.automaticMemoryRecallEnabled,
        ).forEach { queuedMessage ->
            emit(queuedMessage)
        }

        if (awaitExecutionCanContinue(conversationId)) {
            submitContinuationTask(
                llmCallTask(
                    conversationId = conversationId,
                    rootUserMessageId = payload.rootUserMessageId,
                    agent = payload.agent,
                    iteration = payload.iteration + 1,
                )
            )
        }
    }

    private data class ConversationRuntimeStepContext(
        val project: Project,
        val runtime: AiRuntime,
        val provider: AiProvider,
        val modelName: String,
        val availableTools: List<AiToolCallback>,
        val memoryPipelineTools: List<AiToolCallback>,
        val memorySystemPrompts: List<String>,
        val runtimeSystemPrompts: List<String>,
        val assistantResponseFormat: com.gromozeka.domain.model.ai.AiModelConfiguration.AssistantResponseFormat,
        val autoCompactionThresholdTokens: Int?,
        val automaticMemoryRememberEnabled: Boolean,
        val automaticMemoryRecallEnabled: Boolean,
    )

    private suspend fun buildConversationRuntimeContext(
        agent: AgentDefinition,
        conversation: Conversation,
    ): ConversationRuntimeStepContext {
        val project = conversationService.getProject(conversation.id)
        val resolvedRuntime = settingsProvider.resolveAiRuntime(agent.runtimeSelection)
        val provider = resolvedRuntime.connection.kind.provider
        val modelName = resolvedRuntime.modelConfiguration.providerModelId
        log.info {
            "Agent config: name=${agent.name}, modelName=$modelName, provider=$provider, " +
                "runtimeOverrides=${agent.runtimeOverrides}"
        }

        val runtime = aiRuntimeProvider.getRuntime(agent.runtimeSelection, project.path)
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
        val assistantResponseFormat = resolvedRuntime.modelConfiguration.assistantResponseFormat
        val runtimeSystemPrompts = buildList {
            addAll(baseSystemPrompts)
            AssistantResponseFormatContract.instruction(assistantResponseFormat)?.let(::add)
        }

        return ConversationRuntimeStepContext(
            project = project,
            runtime = runtime,
            provider = provider,
            modelName = modelName,
            availableTools = availableTools,
            memoryPipelineTools = memoryPipelineTools,
            memorySystemPrompts = baseSystemPrompts,
            runtimeSystemPrompts = runtimeSystemPrompts,
            assistantResponseFormat = assistantResponseFormat,
            autoCompactionThresholdTokens = autoCompactionThresholdTokens,
            automaticMemoryRememberEnabled = settingsProvider.userProfile.memorySettings.autoRemember,
            automaticMemoryRecallEnabled = settingsProvider.userProfile.memorySettings.autoRecall,
        )
    }

    private suspend fun appendUserMessageWithAutomaticMemory(
        conversationId: Conversation.Id,
        conversation: Conversation,
        project: Project,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
        memorySystemPrompts: List<String>,
        memoryPipelineTools: List<AiToolCallback>,
        automaticMemoryRememberEnabled: Boolean,
        automaticMemoryRecallEnabled: Boolean,
        recallFailureLogPrefix: String,
    ): List<Conversation.Message> {
        val emittedMessages = mutableListOf<Conversation.Message>()
        val userMessageAdded = addRuntimeMessageIfMissing(conversationId, userMessage)
        if (!userMessageAdded) {
            log.info {
                "Runtime user message already exists, skipping duplicate side effects: " +
                    "conversation=${conversationId.value} message=${userMessage.id.value}"
            }
            return emptyList()
        }
        emittedMessages.add(userMessage)

        val writeResult = if (automaticMemoryRememberEnabled) {
            routeMessageThroughMemoryRouter(
                conversationId,
                conversation.currentThread,
                userMessage,
                agent,
                project,
                memorySystemPrompts,
                memoryPipelineTools,
            )
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
                if (addRuntimeMessageIfMissing(conversationId, syntheticMessage)) {
                    emittedMessages.add(syntheticMessage)
                }
            }
        }

        val activeConversation = conversationService.findById(conversationId) ?: conversation
        val messagesBeforeRecall = conversationService.loadCurrentMessages(conversationId)
        val runtimeMemoryResult = if (automaticMemoryRecallEnabled) {
            runCatching {
                memoryApplicationService.buildRuntimeMemoryReadResult(
                    conversationId = conversationId,
                    threadId = activeConversation.currentThread,
                    targetMessage = userMessage,
                    threadMessages = messagesBeforeRecall,
                    agent = agent,
                    project = project,
                    runtimeSystemPrompts = memorySystemPrompts,
                    runtimeTools = memoryPipelineTools,
                )
            }.onFailure { error ->
                if (error is CancellationException) {
                    throw error
                }
                log.warn(error) {
                    "$recallFailureLogPrefix: conversation=${conversationId.value} target=${userMessage.id.value} error=${error.message}"
                }
                if (java.lang.Boolean.getBoolean("gromozeka.memory.routing.failFast")) {
                    throw error
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
                if (addRuntimeMessageIfMissing(conversationId, syntheticMessage)) {
                    emittedMessages.add(syntheticMessage)
                }
            }
        }

        log.info {
            "Memory auto wiring: conversation=${conversationId.value} autoRemember=$automaticMemoryRememberEnabled autoRecall=$automaticMemoryRecallEnabled " +
                "memoryPromptPresent=${!runtimeMemoryResult?.runtimePrompt.isNullOrBlank()} memoryPromptChars=${runtimeMemoryResult?.runtimePrompt?.length ?: 0} " +
                "systemPrompts=${memorySystemPrompts.size} rememberTriggered=$automaticMemoryRememberEnabled recallTriggered=$automaticMemoryRecallEnabled"
        }
        return emittedMessages
    }

    private suspend fun saveTokenUsage(
        conversation: Conversation,
        context: ConversationRuntimeStepContext,
        lastAssistantMessage: Conversation.Message?,
        usage: AiUsage?,
    ) {
        if (lastAssistantMessage == null || usage == null) {
            return
        }
        runCatching {
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
            val statisticsId = TokenUsageStatistics.Id("runtime:${lastAssistantMessage.id.value}:usage")
            if (tokenUsageStatisticsRepository.getRecentCalls(conversation.currentThread, limit = 100).any { it.id == statisticsId }) {
                log.info {
                    "Runtime token usage side effect already applied: " +
                        "thread=${conversation.currentThread.value} message=${lastAssistantMessage.id.value}"
                }
                return@runCatching
            }
            tokenUsageStatisticsRepository.save(
                TokenUsageStatistics(
                    id = statisticsId,
                    threadId = conversation.currentThread,
                    lastMessageId = lastAssistantMessage.id,
                    timestamp = Clock.System.now(),
                    promptTokens = usage.promptTokens,
                    completionTokens = usage.completionTokens,
                    cacheCreationTokens = usage.cacheCreationTokens,
                    cacheReadTokens = usage.cacheReadTokens,
                    thinkingTokens = usage.thinkingTokens,
                    provider = context.provider.name,
                    modelId = context.modelName,
                )
            )
        }.onFailure { error ->
            log.error(error) { "Failed to save token usage statistics" }
        }
    }

    private suspend fun submitContinuationTask(task: ConversationRuntimeTask) {
        runtimeDispatcher.submitContinuationTask(task)
    }

    private fun llmCallTask(
        conversationId: Conversation.Id,
        rootUserMessageId: Conversation.Message.Id,
        agent: AgentDefinition,
        iteration: Int,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${rootUserMessageId.value}:llm:$iteration"),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.LlmCall(
                rootUserMessageId = rootUserMessageId,
                agent = agent,
                iteration = iteration,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "conversation:${conversationId.value}:runtime:${rootUserMessageId.value}:llm:$iteration",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(ConversationRuntimeWorkerCapability.LLM_RUNTIME),
            ),
            createdAt = Clock.System.now(),
        )

    private fun toolExecutionTask(
        conversationId: Conversation.Id,
        rootUserMessageId: Conversation.Message.Id,
        agent: AgentDefinition,
        iteration: Int,
        toolCalls: List<ContentItem.ToolCall>,
        availableTools: List<AiToolCallback>,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${rootUserMessageId.value}:tools:$iteration"),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.ToolExecution(
                rootUserMessageId = rootUserMessageId,
                agent = agent,
                iteration = iteration,
                toolCalls = toolCalls,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "conversation:${conversationId.value}:runtime:${rootUserMessageId.value}:tools:$iteration",
            requirements = toolExecutionRequirements(toolCalls, availableTools),
            createdAt = Clock.System.now(),
        )

    private fun toolExecutionRequirements(
        toolCalls: List<ContentItem.ToolCall>,
        availableTools: List<AiToolCallback>,
    ): ConversationRuntimeTaskRequirements =
        conversationRuntimeToolExecutionRequirements(
            toolCalls = toolCalls,
            availableTools = availableTools,
            localAgentAffinity = localAgentAffinity,
        )

    private companion object {
        const val MAX_TOOL_LOOP_ITERATIONS = 200
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
            if (error is CancellationException) {
                throw error
            }
            val failed = "Memory router trigger failed: conversation=${conversationId.value} message=${message.id.value} role=${message.role} error=${error.message}"
            log.warn(error) { failed }
            println(failed)
            if (java.lang.Boolean.getBoolean("gromozeka.memory.routing.failFast")) {
                throw error
            }
        }.getOrNull()
    }

    private suspend fun popQueuedRuntimeMessages(
        conversationId: Conversation.Id,
        runtimeTaskId: ConversationRuntimeTask.Id,
        workerId: String,
        placement: QueuedMessagePlacement,
    ): List<ConversationRuntimeTask> {
        val tasks = runtimeCoordinator.takeActiveInsertions(conversationId, runtimeTaskId, workerId, placement)
        if (tasks.isNotEmpty()) {
            publishRuntimeSnapshot(conversationId)
        }
        return tasks
    }

    private suspend fun ensureRuntimeTaskOwner(
        conversationId: Conversation.Id,
        runtimeTaskId: ConversationRuntimeTask.Id,
        workerId: String,
    ) {
        val accepted = runtimeCoordinator.confirmActiveTaskOwner(conversationId, runtimeTaskId, workerId)
        if (!accepted) {
            throw IllegalStateException(
                "Conversation runtime task ownership was lost before side effect: " +
                    "conversation=${conversationId.value} task=${runtimeTaskId.value} worker=$workerId"
            )
        }
    }

    private suspend fun upsertRuntimeToolExecution(
        conversationId: Conversation.Id,
        execution: ConversationRuntimeToolExecution,
    ) {
        val accepted = runtimeCoordinator.upsertToolExecution(conversationId, execution)
        if (!accepted) {
            throw IllegalStateException(
                "Rejected stale runtime tool execution update: " +
                    "conversation=${conversationId.value} task=${execution.runtimeTaskId?.value} worker=${execution.workerId}"
            )
        }
        publishRuntimeSnapshot(conversationId)
    }

    private suspend fun clearRuntimeToolExecutions(
        conversationId: Conversation.Id,
        runtimeTaskId: ConversationRuntimeTask.Id,
        workerId: String,
    ) {
        if (runtimeCoordinator.clearToolExecutions(conversationId, runtimeTaskId, workerId)) {
            publishRuntimeSnapshot(conversationId)
        }
    }

    private suspend fun publishRuntimeSnapshot(conversationId: Conversation.Id) {
        try {
            runtimeEventBus.publish(runtimeSnapshotEvent(conversationId))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn(error) {
                "Failed to publish live runtime snapshot; clients can recover from snapshot reload: " +
                    "conversation=${conversationId.value} error=${error.message}"
            }
        }
    }

    private suspend fun runtimeSnapshotEvent(conversationId: Conversation.Id): ConversationRuntimeEvent.SnapshotUpdated =
        ConversationRuntimeEvent.SnapshotUpdated(
            conversationId = conversationId,
            snapshot = runtimeCoordinator.snapshot(conversationId),
        )

    private suspend fun awaitExecutionCanContinue(conversationId: Conversation.Id): Boolean {
        while (true) {
            val state = runtimeCoordinator.find(conversationId) ?: return true
            when {
                state.controlState == ConversationExecutionState.ControlState.STOPPING ||
                    state.controlState == ConversationExecutionState.ControlState.INTERRUPTING -> return false

                state.controlState == ConversationExecutionState.ControlState.PAUSED -> delay(250)

                state.controlState == ConversationExecutionState.ControlState.PAUSE_REQUESTED -> {
                    if (runtimeCoordinator.markPaused(conversationId)) {
                        publishRuntimeSnapshot(conversationId)
                    }
                    delay(250)
                }

                else -> return true
            }
        }
    }

    private suspend fun emitQueuedRuntimeMessagesAtSafePoint(
        conversationId: Conversation.Id,
        runtimeTaskId: ConversationRuntimeTask.Id,
        workerId: String,
        placement: QueuedMessagePlacement,
        conversation: Conversation,
        project: Project,
        memorySystemPrompts: List<String>,
        memoryPipelineTools: List<AiToolCallback>,
        automaticMemoryRememberEnabled: Boolean,
        automaticMemoryRecallEnabled: Boolean,
    ): List<Conversation.Message> {
        val queuedMessages = popQueuedRuntimeMessages(conversationId, runtimeTaskId, workerId, placement)
        if (queuedMessages.isEmpty()) {
            return emptyList()
        }

        return queuedMessages.flatMap { queued ->
            appendQueuedUserMessage(
                conversationId = conversationId,
                conversation = conversation,
                project = project,
                queued = queued,
                memorySystemPrompts = memorySystemPrompts,
                memoryPipelineTools = memoryPipelineTools,
                automaticMemoryRememberEnabled = automaticMemoryRememberEnabled,
                automaticMemoryRecallEnabled = automaticMemoryRecallEnabled,
            )
        }
    }

    private suspend fun appendQueuedUserMessage(
        conversationId: Conversation.Id,
        conversation: Conversation,
        project: Project,
        queued: ConversationRuntimeTask,
        memorySystemPrompts: List<String>,
        memoryPipelineTools: List<AiToolCallback>,
        automaticMemoryRememberEnabled: Boolean,
        automaticMemoryRecallEnabled: Boolean,
    ): List<Conversation.Message> {
        val emittedMessages = mutableListOf<Conversation.Message>()
        val userTurn = queued.requireUserTurn()
        val userMessage = userTurn.userMessage
        val agent = userTurn.agent

        val userMessageAdded = addRuntimeMessageIfMissing(conversationId, userMessage)
        if (!userMessageAdded) {
            log.info {
                "Queued runtime message already exists, skipping duplicate side effects: " +
                    "conversation=${conversationId.value} message=${userMessage.id.value}"
            }
            return emptyList()
        }
        emittedMessages.add(userMessage)
        log.info {
            "Accepted queued runtime message at ${queued.placement}: conversation=${conversationId.value} message=${userMessage.id.value}"
        }

        val writeResult = if (automaticMemoryRememberEnabled) {
            routeMessageThroughMemoryRouter(conversationId, conversation.currentThread, userMessage, agent, project, memorySystemPrompts, memoryPipelineTools)
        } else {
            null
        }

        if (automaticMemoryRememberEnabled) {
            val rememberMessages = buildSyntheticMemoryToolPair(
                conversationId = conversationId,
                targetMessage = userMessage,
                toolName = MEMORY_REMEMBER_TOOL_NAME,
                arguments = buildJsonObject {
                    put("target", "previous_user_message")
                    put("target_message_id", userMessage.id.value)
                    put("mode", "automatic_hot_path")
                },
                resultText = MemoryToolResultRenderer.rememberResultJsonString(writeResult)
            )
            rememberMessages.forEach { syntheticMessage ->
                if (addRuntimeMessageIfMissing(conversationId, syntheticMessage)) {
                    emittedMessages.add(syntheticMessage)
                }
            }
        }

        val activeConversation = conversationService.findById(conversationId) ?: conversation
        val messagesBeforeRecall = conversationService.loadCurrentMessages(conversationId)
        val runtimeMemoryResult = if (automaticMemoryRecallEnabled) {
            runCatching {
                memoryApplicationService.buildRuntimeMemoryReadResult(
                    conversationId = conversationId,
                    threadId = activeConversation.currentThread,
                    targetMessage = userMessage,
                    threadMessages = messagesBeforeRecall,
                    agent = agent,
                    project = project,
                    runtimeSystemPrompts = memorySystemPrompts,
                    runtimeTools = memoryPipelineTools,
                )
            }.onFailure { error ->
                if (error is CancellationException) {
                    throw error
                }
                log.warn(error) {
                    "Queued message memory recall failed: conversation=${conversationId.value} target=${userMessage.id.value} error=${error.message}"
                }
            }.getOrNull()
        } else {
            null
        }

        if (automaticMemoryRecallEnabled) {
            val recallMessages = buildSyntheticMemoryToolPair(
                conversationId = conversationId,
                targetMessage = userMessage,
                toolName = MEMORY_ENRICH_CONTEXT_TOOL_NAME,
                arguments = buildJsonObject {
                    put("target", "previous_user_message")
                    put("target_message_id", userMessage.id.value)
                    put("mode", "automatic_runtime_context_enrichment")
                },
                resultText = MemoryToolResultRenderer.enrichContextResultJsonString(runtimeMemoryResult)
            )
            recallMessages.forEach { syntheticMessage ->
                if (addRuntimeMessageIfMissing(conversationId, syntheticMessage)) {
                    emittedMessages.add(syntheticMessage)
                }
            }
        }

        return emittedMessages
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
        enqueueCurrentMemoryMaintenance(conversationId, agent, MemoryMaintenanceAction.CONSOLIDATE)
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
        enqueueCurrentMemoryMaintenance(conversationId, agent, MemoryMaintenanceAction.REPAIR)
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
        enqueueCurrentMemoryMaintenance(conversationId, agent, MemoryMaintenanceAction.MAINTAIN_ENTITIES)
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
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val agent = agentDomainService.findById(conversation.agentDefinitionId)
            ?: throw IllegalStateException("Agent not found: ${conversation.agentDefinitionId}")
        enqueueCurrentMemoryMaintenance(conversationId, agent, MemoryMaintenanceAction.APPLY_RETENTION)
    }

    suspend fun runCurrentMemoryRetention(conversationId: Conversation.Id) {
        val project = conversationService.getProject(conversationId)
        memoryApplicationService.runRetention(conversationId, project)
        log.info { "Ran memory retention for conversation $conversationId" }
    }

    private suspend fun enqueueCurrentMemoryMaintenance(
        conversationId: Conversation.Id,
        agent: AgentDefinition,
        action: MemoryMaintenanceAction,
    ) {
        val project = conversationService.getProject(conversationId)
        val systemPrompts = agentDomainService.assembleSystemPrompt(agent, project)
        val memoryPipelineTools = aiToolProvider.getTools().withoutMemoryManagementTools()
        val result = memoryMaintenanceQueue.enqueue(
            action = action,
            targetKind = "conversation_id",
            targetValue = conversationId.value,
            conversationId = conversationId,
            agent = agent,
            project = project,
            namespace = project.defaultMemoryNamespace(),
            runtimeSystemPrompts = systemPrompts,
            runtimeTools = memoryPipelineTools,
        )
        log.info {
            "Queued memory maintenance for conversation $conversationId: action=${action.toolName} run=${result.runId.value}"
        }
    }

    private suspend fun addRuntimeMessageIfMissing(
        conversationId: Conversation.Id,
        message: Conversation.Message,
    ): Boolean {
        val existsInCurrentThread = conversationService.loadCurrentMessages(conversationId).any { it.id == message.id }
        if (existsInCurrentThread) {
            log.info {
                "Runtime message side effect already applied: " +
                    "conversation=${conversationId.value} message=${message.id.value}"
            }
            return false
        }
        conversationService.addMessage(conversationId, message)
        return true
    }

    private fun runtimeMessageId(
        taskId: ConversationRuntimeTask.Id,
        suffix: String,
    ): Conversation.Message.Id =
        Conversation.Message.Id("${taskId.value}:$suffix")

    private fun List<Conversation.Message>.withRuntimeMessageIds(
        taskId: ConversationRuntimeTask.Id,
        prefix: String,
    ): List<Conversation.Message> =
        mapIndexed { index, message ->
            message.copy(id = runtimeMessageId(taskId, "$prefix:$index"))
        }

    private fun buildSyntheticMemoryToolPair(
        conversationId: Conversation.Id,
        targetMessage: Conversation.Message,
        toolName: String,
        arguments: JsonObject,
        resultText: String,
    ): List<Conversation.Message> {
        val toolCallId = ContentItem.ToolCall.Id(
            "mem_${stableIdentifierSlug(targetMessage.id.value)}_${stableIdentifierSlug(toolName)}"
        )
        val createdAt = Clock.System.now()
        val toolCallMessage = Conversation.Message(
            id = Conversation.Message.Id("${targetMessage.id.value}:memory:$toolName:call"),
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
            id = Conversation.Message.Id("${targetMessage.id.value}:memory:$toolName:result"),
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

    private fun stableIdentifierSlug(value: String): String =
        value.filter { it.isLetterOrDigit() }.take(48).ifBlank { "x" }

}

private fun ConversationRuntimeWorkerAffinity.Kind.localToolPriority(): Int =
    when (this) {
        ConversationRuntimeWorkerAffinity.Kind.WORKSPACE -> 0
        ConversationRuntimeWorkerAffinity.Kind.PROJECT -> 1
        ConversationRuntimeWorkerAffinity.Kind.MACHINE -> 2
        ConversationRuntimeWorkerAffinity.Kind.USER -> 3
    }
