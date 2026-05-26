package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.BlockState
import com.gromozeka.domain.model.Conversation.Message.ContentItem
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
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeControlAction
import com.gromozeka.domain.service.ConversationRuntimeCommand
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.domain.repository.ThreadRepository
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.gromozeka.domain.tool.AiToolCallback
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
    private val memoryMaintenanceQueue: MemoryMaintenanceQueue,
    private val memoryMessageRoutingApplicationService: MemoryMessageRoutingApplicationService,
    private val toolCallSequenceFixerService: ToolCallSequenceFixerService,
    private val settingsProvider: com.gromozeka.domain.service.SettingsProvider,
    private val aiModelSpecRepository: AiModelSpecRepository,
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeEventBus: ConversationRuntimeEventBus,
) : ConversationRuntimeService {
    private val log = KLoggers.logger(this)
    private val executorJobsLock = Any()
    private val executorJobsByConversation = mutableMapOf<Conversation.Id, Job>()

    override suspend fun enqueueMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
        placement: QueuedMessagePlacement,
    ): Boolean {
        val state = runtimeCoordinator.find(conversationId)
        val pendingCommands = runtimeCoordinator.listPending(conversationId)
        val canAcceptQueuedCommand = state == null ||
            state.status == ConversationExecutionState.Status.RUNNING ||
            state.status == ConversationExecutionState.Status.PAUSE_REQUESTED ||
            state.status == ConversationExecutionState.Status.PAUSED
        val canQueue = when (placement) {
            QueuedMessagePlacement.AFTER_TOOL_RESULT -> canAcceptQueuedCommand && state?.activeCommandId != null
            QueuedMessagePlacement.END_OF_TURN ->
                canAcceptQueuedCommand && (state != null || pendingCommands.any { it.placement == QueuedMessagePlacement.END_OF_TURN })
        }
        if (!canQueue) {
            log.info {
                "Rejected queued message without active runtime: conversation=${conversationId.value} " +
                    "message=${userMessage.id.value} placement=$placement"
            }
            return false
        }

        val command = queuedRuntimeCommand(conversationId, userMessage, agent, placement)
        val accepted = submitRuntimeCommand(command)
        if (accepted) {
            log.info {
                "Queued runtime message: conversation=${conversationId.value} message=${userMessage.id.value} placement=$placement"
            }
        }
        return accepted
    }

    override suspend fun cancelQueuedMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean {
        val removed = runtimeCoordinator.cancelByMessageId(conversationId, messageId)
        if (removed) {
            publishRuntimeSnapshot(conversationId)
            log.info { "Cancelled runtime queued message: conversation=${conversationId.value} message=${messageId.value}" }
        }
        return removed
    }

    override suspend fun controlExecution(
        conversationId: Conversation.Id,
        action: ConversationRuntimeControlAction,
    ): Boolean {
        val accepted = when (action) {
            ConversationRuntimeControlAction.PAUSE -> runtimeCoordinator.requestPause(conversationId)
            ConversationRuntimeControlAction.RESUME -> runtimeCoordinator.requestResume(conversationId).also { accepted ->
                if (accepted) {
                    wakeConversationExecutor(conversationId)
                }
            }
            ConversationRuntimeControlAction.STOP -> runtimeCoordinator.requestStop(conversationId).also { accepted ->
                if (accepted) {
                    wakeConversationExecutor(conversationId)
                }
            }
            ConversationRuntimeControlAction.INTERRUPT -> {
                val requested = runtimeCoordinator.requestInterrupt(conversationId)
                val cancelled = cancelConversationExecutor(conversationId)
                if (requested && !cancelled) {
                    wakeConversationExecutor(conversationId)
                }
                cancelled || requested
            }
        }
        if (accepted) {
            publishRuntimeSnapshot(conversationId)
            log.info { "Runtime execution control accepted: conversation=${conversationId.value} action=$action" }
        } else {
            log.info { "Runtime execution control ignored without active turn: conversation=${conversationId.value} action=$action" }
        }
        return accepted
    }

    override suspend fun submitMessage(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
    ): Boolean {
        val command = queuedRuntimeCommand(conversationId, userMessage, agent, QueuedMessagePlacement.END_OF_TURN)
        return submitRuntimeCommand(command)
    }

    override fun observeConversation(conversationId: Conversation.Id): Flow<ConversationRuntimeEvent> = flow {
        val subscription = runtimeEventBus.subscribe(conversationId)
        try {
            emit(runtimeSnapshotEvent(conversationId))
            subscription.events.collect(::emit)
        } finally {
            subscription.close()
        }
    }

    private suspend fun submitRuntimeCommand(command: ConversationRuntimeCommand): Boolean {
        val accepted = runtimeCoordinator.submit(command)
        if (accepted) {
            publishRuntimeSnapshot(command.conversationId)
            if (command.placement == QueuedMessagePlacement.END_OF_TURN) {
                wakeConversationExecutor(command.conversationId)
            }
        }
        return accepted
    }

    private fun wakeConversationExecutor(conversationId: Conversation.Id) {
        lateinit var job: Job
        job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            var wakeAgain = false
            try {
                wakeAgain = drainConversationRuntime(conversationId)
            } finally {
                synchronized(executorJobsLock) {
                    if (executorJobsByConversation[conversationId] == job) {
                        executorJobsByConversation.remove(conversationId)
                    }
                }
                if (wakeAgain) {
                    wakeConversationExecutor(conversationId)
                }
            }
        }

        val shouldStart = synchronized(executorJobsLock) {
            val existingJob = executorJobsByConversation[conversationId]
            if (existingJob?.isActive == true) {
                false
            } else {
                executorJobsByConversation[conversationId] = job
                true
            }
        }

        if (shouldStart) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private fun cancelConversationExecutor(conversationId: Conversation.Id): Boolean {
        val job = synchronized(executorJobsLock) {
            executorJobsByConversation[conversationId]
        } ?: return false
        if (!job.isActive) {
            return false
        }
        job.cancel(CancellationException("Conversation runtime interrupted: ${conversationId.value}"))
        return true
    }

    private suspend fun drainConversationRuntime(conversationId: Conversation.Id): Boolean {
        val workerId = uuid7()
        if (finishRuntimeIfIdle(conversationId)) {
            runtimeEventBus.publish(ConversationRuntimeEvent.ExecutionCompleted(conversationId))
            return false
        }
        if (!awaitExecutionCanContinue(conversationId)) {
            finishRuntimeIfIdle(conversationId)
            runtimeEventBus.publish(ConversationRuntimeEvent.ExecutionCompleted(conversationId))
            return false
        }

        val command = runtimeCoordinator.claimNextTurn(
            conversationId = conversationId,
            workerId = workerId,
            leaseUntil = null,
        )
        publishRuntimeSnapshot(conversationId)

        if (command == null) {
            if (finishRuntimeIfIdle(conversationId)) {
                runtimeEventBus.publish(ConversationRuntimeEvent.ExecutionCompleted(conversationId))
            }
            return false
        }

        try {
            runtimeEventBus.publish(
                ConversationRuntimeEvent.MessageEmitted(
                    conversationId = conversationId,
                    commandId = command.id,
                    message = command.userMessage,
                )
            )
            runSingleTurn(command.conversationId, command.userMessage, command.agent).collect { message ->
                runtimeEventBus.publish(
                    ConversationRuntimeEvent.MessageEmitted(
                        conversationId = command.conversationId,
                        commandId = command.id,
                        message = message,
                    )
                )
            }
            runtimeCoordinator.completeActiveTurn(conversationId)
            publishRuntimeSnapshot(conversationId)

            if (finishRuntimeIfIdle(conversationId)) {
                runtimeEventBus.publish(ConversationRuntimeEvent.ExecutionCompleted(conversationId))
                return false
            }
            if (!awaitExecutionCanContinue(conversationId)) {
                finishRuntimeIfIdle(conversationId)
                runtimeEventBus.publish(ConversationRuntimeEvent.ExecutionCompleted(conversationId))
                return false
            }
            return true
        } catch (error: CancellationException) {
            runtimeCoordinator.abort(conversationId)
            publishRuntimeSnapshot(conversationId)
            runtimeEventBus.publish(ConversationRuntimeEvent.ExecutionCompleted(conversationId))
            return false
        } catch (error: Throwable) {
            runtimeCoordinator.fail(conversationId)
            publishRuntimeSnapshot(conversationId)
            runtimeEventBus.publish(
                ConversationRuntimeEvent.ExecutionFailed(
                    conversationId = conversationId,
                    message = error.message ?: "Unknown conversation runtime error",
                    type = error::class.simpleName,
                )
            )
            return false
        }
    }

    private fun runSingleTurn(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
    ): Flow<Conversation.Message> = flow {
        markRuntimePhase(conversationId, ConversationExecutionState.Phase.BEFORE_LLM)
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
        val assistantResponseFormat = resolvedRuntime.modelConfiguration.assistantResponseFormat
        val memorySystemPrompts = baseSystemPrompts
        val runtimeSystemPrompts = buildList {
            addAll(baseSystemPrompts)
            AssistantResponseFormatContract.instruction(assistantResponseFormat)?.let(::add)
        }
        val automaticMemoryRememberEnabled = settingsProvider.userProfile.memorySettings.autoRemember
        val automaticMemoryRecallEnabled = settingsProvider.userProfile.memorySettings.autoRecall

        // Add user message
        conversationService.addMessage(conversationId, userMessage)
        val writeResult = if (automaticMemoryRememberEnabled) {
            routeMessageThroughMemoryRouter(conversationId, conversation.currentThread, userMessage, agent, project, memorySystemPrompts, memoryPipelineTools)
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
                    runtimeSystemPrompts = memorySystemPrompts,
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
                "systemPrompts=${runtimeSystemPrompts.size} assistantResponseFormat=$assistantResponseFormat " +
                "rememberTriggered=$automaticMemoryRememberEnabled recallTriggered=$automaticMemoryRecallEnabled"
        }

        var iterationCount = 0
        val maxIterations = 200

        while (iterationCount < maxIterations) {
            if (!awaitExecutionCanContinue(conversationId)) {
                break
            }
            iterationCount++

            val currentMessages = if (iterationCount == 1) finalMessages else conversationService.loadCurrentMessages(conversationId)

            val runtimeRequest = AiRuntimeRequest(
                systemPrompts = runtimeSystemPrompts,
                messages = currentMessages,
                tools = availableTools,
                options = AiRuntimeOptions(
                    maxOutputTokens = agent.runtimeOverrides.maxOutputTokens,
                    reasoning = agent.runtimeOverrides.reasoning,
                    autoCompactionThresholdTokens = autoCompactionThresholdTokens,
                    responseFormat = AssistantResponseFormatContract.runtimeResponseFormat(assistantResponseFormat),
                    assistantResponseFormat = assistantResponseFormat,
                    toolContext = mapOf(
                        "projectPath" to project.path,
                        "conversationId" to conversationId.value,
                        "aiProvider" to provider.name,
                        "modelName" to modelName
                    )
                )
            )

            val runtimeResponse = try {
                log.info { "Calling LLM runtime: model=$modelName, provider=$provider, iteration=$iterationCount" }
                markRuntimePhase(conversationId, ConversationExecutionState.Phase.RUNNING_LLM)
                runtime.call(runtimeRequest)
            } catch (e: Exception) {
                log.error(e) { "Chat call error" }
                val errorMessage = AiConversationMessageMapper.createErrorMessage(conversationId, e.message ?: "Unknown error")
                emit(errorMessage)
                conversationService.addMessage(conversationId, errorMessage)
                if (automaticMemoryRememberEnabled) {
                    routeMessageThroughMemoryRouter(conversationId, conversation.currentThread, errorMessage, agent, project, memorySystemPrompts, memoryPipelineTools)
                }
                break
            }

            log.info {
                "Runtime response received: assistantMessages=${runtimeResponse.messages.size}, " +
                    "toolCalls=${runtimeResponse.toolCalls.size}, finishReason=${runtimeResponse.finishReason}"
            }
            markRuntimePhase(conversationId, ConversationExecutionState.Phase.AFTER_LLM)
            if (runtimeCoordinator.find(conversationId)?.status == ConversationExecutionState.Status.INTERRUPTING) {
                break
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
                    routeMessageThroughMemoryRouter(conversationId, conversation.currentThread, message, agent, project, memorySystemPrompts, memoryPipelineTools)
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

                val toolContext = ToolExecutionContext(
                    mapOf(
                        "projectPath" to project.path,
                        "conversationId" to conversationId.value,
                        "aiProvider" to provider.name,
                        "modelName" to modelName
                    )
                )
                markRuntimePhase(conversationId, ConversationExecutionState.Phase.RUNNING_TOOL)
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
                markRuntimePhase(conversationId, ConversationExecutionState.Phase.AFTER_TOOL_RESULT)
                if (automaticMemoryRememberEnabled) {
                    routeMessageThroughMemoryRouter(conversationId, conversation.currentThread, toolResultMessage, agent, project, memorySystemPrompts, memoryPipelineTools)
                }
                if (!awaitExecutionCanContinue(conversationId)) {
                    break
                }

                if (executionResult.returnDirect) {
                    break
                }

                emitQueuedRuntimeMessagesAtSafePoint(
                    conversationId = conversationId,
                    placement = QueuedMessagePlacement.AFTER_TOOL_RESULT,
                    conversation = conversation,
                    project = project,
                    memorySystemPrompts = memorySystemPrompts,
                    memoryPipelineTools = memoryPipelineTools,
                    automaticMemoryRememberEnabled = automaticMemoryRememberEnabled,
                    automaticMemoryRecallEnabled = automaticMemoryRecallEnabled,
                ).forEach { queuedMessage ->
                    emit(queuedMessage)
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

    private suspend fun popQueuedRuntimeMessages(
        conversationId: Conversation.Id,
        placement: QueuedMessagePlacement,
    ): List<ConversationRuntimeCommand> {
        val commands = runtimeCoordinator.takeActiveInsertions(conversationId, placement)
        if (commands.isNotEmpty()) {
            publishRuntimeSnapshot(conversationId)
        }
        return commands
    }

    private suspend fun markRuntimePhase(
        conversationId: Conversation.Id,
        phase: ConversationExecutionState.Phase,
    ) {
        runtimeCoordinator.markPhase(conversationId, phase)
        publishRuntimeSnapshot(conversationId)
    }

    private suspend fun finishRuntimeIfIdle(conversationId: Conversation.Id): Boolean {
        val finished = runtimeCoordinator.finishIfIdle(conversationId)
        if (finished) {
            publishRuntimeSnapshot(conversationId)
        }
        return finished
    }

    private suspend fun publishRuntimeSnapshot(conversationId: Conversation.Id) {
        runtimeEventBus.publish(runtimeSnapshotEvent(conversationId))
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
                state.status == ConversationExecutionState.Status.STOPPING ||
                    state.status == ConversationExecutionState.Status.INTERRUPTING -> return false

                state.status == ConversationExecutionState.Status.PAUSED -> delay(250)

                state.status == ConversationExecutionState.Status.PAUSE_REQUESTED -> {
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
        placement: QueuedMessagePlacement,
        conversation: Conversation,
        project: Project,
        memorySystemPrompts: List<String>,
        memoryPipelineTools: List<AiToolCallback>,
        automaticMemoryRememberEnabled: Boolean,
        automaticMemoryRecallEnabled: Boolean,
    ): List<Conversation.Message> {
        val queuedMessages = popQueuedRuntimeMessages(conversationId, placement)
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
        queued: ConversationRuntimeCommand,
        memorySystemPrompts: List<String>,
        memoryPipelineTools: List<AiToolCallback>,
        automaticMemoryRememberEnabled: Boolean,
        automaticMemoryRecallEnabled: Boolean,
    ): List<Conversation.Message> {
        val emittedMessages = mutableListOf<Conversation.Message>()
        val userMessage = queued.userMessage
        val agent = queued.agent

        conversationService.addMessage(conversationId, userMessage)
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
                conversationService.addMessage(conversationId, syntheticMessage)
                emittedMessages.add(syntheticMessage)
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
                conversationService.addMessage(conversationId, syntheticMessage)
                emittedMessages.add(syntheticMessage)
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

    private fun queuedRuntimeCommand(
        conversationId: Conversation.Id,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
        placement: QueuedMessagePlacement,
    ): ConversationRuntimeCommand =
        ConversationRuntimeCommand(
            id = ConversationRuntimeCommand.Id(userMessage.id.value),
            conversationId = conversationId,
            userMessage = userMessage,
            agent = agent,
            placement = placement,
            createdAt = Clock.System.now(),
        )
}
