package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.BlockState
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.application.service.memory.MEMORY_ENRICH_CONTEXT_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_REMEMBER_TOOL_NAME
import com.gromozeka.application.service.memory.MemoryMessageRoutingApplicationService
import com.gromozeka.application.service.memory.MemoryNamespaceRecallAccessException
import com.gromozeka.application.service.memory.MemoryToolResultRenderer
import com.gromozeka.application.service.memory.withoutMemoryManagementTools
import com.gromozeka.domain.model.memory.DirectStructuredMemoryWriteResult
import com.gromozeka.domain.repository.AiModelSpecRepository
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentPromptAssemblyService
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeTaskIncident
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerDescriptor
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY_AUTOMATIC
import com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_TARGET_MESSAGE_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_THREAD_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKER_ID
import com.gromozeka.domain.tool.supportedBy
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

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
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
class ConversationEngineService(
    private val aiRuntimeProvider: AiRuntimeProvider,
    private val aiToolProvider: AiToolProvider,
    private val agentDomainService: AgentDomainService,
    private val agentPromptAssemblyService: AgentPromptAssemblyService,
    private val toolApprovalService: ToolApprovalService,
    private val parallelToolExecutor: ParallelToolExecutor,
    private val conversationService: ConversationDomainService,
    private val tokenUsageStatisticsRepository: TokenUsageStatisticsRepository,
    private val memoryApplicationService: MemoryApplicationService,
    private val memoryToolApplicationService: MemoryToolApplicationService,
    private val memoryMessageRoutingApplicationService: MemoryMessageRoutingApplicationService,
    private val toolCallSequenceFixerService: ToolCallSequenceFixerService,
    private val settingsProvider: com.gromozeka.domain.service.SettingsProvider,
    private val aiModelSpecRepository: AiModelSpecRepository,
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeEventBus: ConversationRuntimeEventBus,
    private val workspaceService: WorkspaceDomainService,
    private val distributedToolCatalog: DistributedAiToolCatalog,
    private val agentSkillRuntimeCatalogService: AgentSkillRuntimeCatalogService,
    private val aiToolRuntimeCatalogService: AiToolRuntimeCatalogService,
    private val toolRoutingService: ConversationRuntimeToolRoutingService,
    private val runtimeWorkerDescriptor: ConversationRuntimeWorkerDescriptor,
) : ConversationRuntimeTaskRunner {
    private val log = KLoggers.logger(this)

    override fun runRuntimeTask(
        task: ConversationRuntimeTask,
        worker: ConversationRuntimeWorkerIdentity,
    ): Flow<Conversation.Message> =
        when (val payload = task.payload) {
            is ConversationRuntimeTask.Payload.UserTurn -> runUserTurnStep(task, worker, payload)
            is ConversationRuntimeTask.Payload.LlmCall -> runLlmCallStep(task, worker, payload)
            is ConversationRuntimeTask.Payload.ToolExecution -> runToolExecutionStep(task, worker, payload)
            is ConversationRuntimeTask.Payload.ToolResultProcessing -> runToolResultProcessingStep(task, worker, payload)
            is ConversationRuntimeTask.Payload.MemoryRecall -> runMemoryRecallStep(task, worker, payload)
            is ConversationRuntimeTask.Payload.MemoryRunCompletion -> runMemoryRunCompletionStep(task, worker, payload)
            is ConversationRuntimeTask.Payload.ExecutionIncident -> runExecutionIncidentStep(task, worker, payload)
        }

    private fun runUserTurnStep(
        task: ConversationRuntimeTask,
        worker: ConversationRuntimeWorkerIdentity,
        payload: ConversationRuntimeTask.Payload.UserTurn,
    ): Flow<Conversation.Message> = flow {
        val conversationId = task.conversationId
        ensureRuntimeTaskOwner(conversationId, task.id, worker)
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val context = buildConversationRuntimeContext(payload.agentDefinitionId, conversation, worker)
        appendUserMessageWithAutomaticMemory(
            conversationId = conversationId,
            conversation = conversation,
            runtimeContext = context.runtimeContext,
            userMessage = payload.userMessage,
            agent = context.agent,
            memorySystemPrompts = context.memorySystemPrompts,
            memoryPipelineTools = context.memoryPipelineTools,
            automaticMemoryRememberEnabled = context.automaticMemoryRememberEnabled,
            automaticMemoryRecallEnabled = context.automaticMemoryRecallEnabled,
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
                    agentDefinitionId = payload.agentDefinitionId,
                    iteration = 1,
                )
            )
        }
    }

    private fun runLlmCallStep(
        task: ConversationRuntimeTask,
        worker: ConversationRuntimeWorkerIdentity,
        payload: ConversationRuntimeTask.Payload.LlmCall,
    ): Flow<Conversation.Message> = flow {
        val conversationId = task.conversationId
        if (!awaitExecutionCanContinue(conversationId)) {
            return@flow
        }

        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val context = buildConversationRuntimeContext(payload.agentDefinitionId, conversation, worker)

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
                    context.agent,
                    context.runtimeContext,
                    context.memorySystemPrompts,
                    context.memoryPipelineTools,
                )
            }
            return@flow
        }

        val currentMessages = conversationService.loadCurrentMessages(conversationId)
        val availableTools = aiToolRuntimeCatalogService.availableTools(
            agent = context.agent,
            catalog = context.toolCatalog,
            messages = currentMessages,
        )

        val runtimeRequest = AiRuntimeRequest(
            systemPrompts = context.runtimeSystemPrompts,
            messages = currentMessages,
            tools = availableTools,
            options = AiRuntimeOptions(
                maxOutputTokens = context.agent.runtimeOverrides.maxOutputTokens,
                reasoning = context.agent.runtimeOverrides.reasoning,
                autoCompactionThresholdTokens = context.autoCompactionThresholdTokens,
                toolChoice = AiToolChoice.Auto,
                responseFormat = AssistantResponseFormatContract.runtimeResponseFormat(context.assistantResponseFormat),
                assistantResponseFormat = context.assistantResponseFormat,
                toolContext = buildMap {
                    put(TOOL_CONTEXT_CONVERSATION_ID, conversationId.value)
                    put(TOOL_CONTEXT_THREAD_ID, conversation.currentThread.value)
                    put(TOOL_CONTEXT_TARGET_MESSAGE_ID, payload.rootUserMessageId.value)
                    put(TOOL_CONTEXT_PROJECT_ID, context.project.id.value)
                    put(TOOL_CONTEXT_WORKER_ID, worker.workerId.value)
                    put("aiProvider", context.provider.name)
                    put("modelName", context.modelName)
                }
            )
        )

        val runtimeResponse = try {
            log.info { "Calling LLM runtime: model=${context.modelName}, provider=${context.provider}, iteration=${payload.iteration}" }
            ensureRuntimeTaskOwner(conversationId, task.id, worker)
            context.runtime.call(runtimeRequest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Chat call error" }
            if (java.lang.Boolean.getBoolean("gromozeka.memory.routing.failFast")) {
                throw e
            }
            ensureRuntimeTaskOwner(conversationId, task.id, worker)
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
                    context.agent,
                    context.runtimeContext,
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
        ensureRuntimeTaskOwner(conversationId, task.id, worker)
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
        ensureRuntimeTaskOwner(conversationId, task.id, worker)

        assistantMessages.forEach { message ->
            ensureRuntimeTaskOwner(conversationId, task.id, worker)
            val added = addRuntimeMessageIfMissing(conversationId, message)
            if (added) {
                emit(message)
            }
            if (added && context.automaticMemoryRememberEnabled) {
                routeMessageThroughMemoryRouter(
                    conversationId,
                    conversation.currentThread,
                    message,
                    context.agent,
                    context.runtimeContext,
                    context.memorySystemPrompts,
                    context.memoryPipelineTools,
                )
            }
        }
        saveTokenUsage(conversation, context, assistantMessages.lastOrNull(), runtimeResponse.usage)

        if (allToolCalls.isNotEmpty()) {
            when (
                val routing = toolRoutingService.route(
                    conversation = conversation,
                    project = context.project,
                    toolCalls = allToolCalls,
                    catalog = context.toolCatalog,
                    runtimeWorkerId = worker.workerId,
                )
            ) {
                is ConversationRuntimeToolRoutingResult.Accepted -> {
                    submitContinuationTask(
                        toolExecutionTask(
                            conversationId = conversationId,
                            rootUserMessageId = payload.rootUserMessageId,
                            agentDefinitionId = payload.agentDefinitionId,
                            iteration = payload.iteration,
                            toolCalls = allToolCalls,
                            routing = routing,
                        )
                    )
                }

                is ConversationRuntimeToolRoutingResult.Rejected -> {
                    val toolResultMessageId = runtimeMessageId(task.id, "tool-routing-error")
                    val toolResultMessage = Conversation.Message(
                        id = toolResultMessageId,
                        conversationId = conversationId,
                        role = Conversation.Message.Role.USER,
                        content = routing.errors.map { error ->
                            ContentItem.ToolResult(
                                toolUseId = error.toolCallId,
                                toolName = error.toolName,
                                result = listOf(ContentItem.ToolResult.Data.Text(error.message)),
                                isError = true,
                            )
                        },
                        providerMetadata = buildJsonObject {
                            put("synthetic", true)
                            put("syntheticKind", "tool_routing_error")
                        },
                        createdAt = Clock.System.now(),
                    )
                    if (addRuntimeMessageIfMissing(conversationId, toolResultMessage)) {
                        emit(toolResultMessage)
                    }
                    submitContinuationTask(
                        toolResultProcessingTask(
                            conversationId = conversationId,
                            rootUserMessageId = payload.rootUserMessageId,
                            toolResultMessageId = toolResultMessageId,
                            agentDefinitionId = payload.agentDefinitionId,
                            iteration = payload.iteration,
                            returnDirect = false,
                        )
                    )
                }
            }
        } else {
            submitPendingMemoryRecallIfNeeded(
                conversationId = conversationId,
                rootUserMessageId = payload.rootUserMessageId,
                agentDefinitionId = payload.agentDefinitionId,
                nextLlmIteration = payload.iteration + 1,
                automaticMemoryRecallEnabled = context.automaticMemoryRecallEnabled,
            )
        }
    }

    private fun runToolExecutionStep(
        task: ConversationRuntimeTask,
        worker: ConversationRuntimeWorkerIdentity,
        payload: ConversationRuntimeTask.Payload.ToolExecution,
    ): Flow<Conversation.Message> = flow {
        val conversationId = task.conversationId
        if (!awaitExecutionCanContinue(conversationId)) {
            return@flow
        }

        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val project = conversationService.getProject(conversationId)
        val target = task.requirements.target
            ?: error("Tool execution task ${task.id.value} has no exact execution target")
        require(target.workerId == worker.workerId) {
            "Tool execution task ${task.id.value} targets worker ${target.workerId.value}, " +
                "but is running on ${worker.workerId.value}"
        }
        val workspaceContext = target.workspaceMountId?.let { mountId ->
            workspaceService.resolveExecution(mountId).also { resolved ->
                require(resolved.project.id == project.id) {
                    "Tool execution workspace mount ${mountId.value} belongs to project " +
                        "${resolved.project.id.value}, not ${project.id.value}"
                }
                require(resolved.mount.workerId == worker.workerId.value) {
                    "Tool execution workspace mount ${mountId.value} belongs to worker " +
                        "${resolved.mount.workerId}, not ${worker.workerId.value}"
                }
            }
        }
        val toolResultMessageId = runtimeMessageId(task.id, "result")
        val existingToolResult = conversationService.loadCurrentMessages(conversationId)
            .firstOrNull { it.id == toolResultMessageId }

        if (existingToolResult == null) {
            val toolContext = ToolExecutionContext(
                buildMap {
                    put(TOOL_CONTEXT_CONVERSATION_ID, conversationId.value)
                    put(TOOL_CONTEXT_THREAD_ID, conversation.currentThread.value)
                    put(TOOL_CONTEXT_TARGET_MESSAGE_ID, payload.rootUserMessageId.value)
                    put(TOOL_CONTEXT_PROJECT_ID, project.id.value)
                    put(TOOL_CONTEXT_WORKER_ID, worker.workerId.value)
                    put(TOOL_CONTEXT_AGENT_DEFINITION_ID, payload.agentDefinitionId.value)
                    put(
                        TOOL_CONTEXT_MEMORY_RESULT_DELIVERY,
                        TOOL_CONTEXT_MEMORY_RESULT_DELIVERY_AUTOMATIC,
                    )
                    workspaceContext?.let { resolved ->
                        put("workspaceId", resolved.workspace.id.value)
                        put("workspaceMountId", resolved.mount.id.value)
                        put("workspaceRootPath", resolved.mount.rootPath)
                    }
                }
            )
            ensureRuntimeTaskOwner(conversationId, task.id, worker)
            clearRuntimeToolExecutions(conversationId, task.id, worker)
            val executionResult = parallelToolExecutor.executeParallel(
                toolCalls = payload.toolCalls,
                toolContext = toolContext,
                runtimeTaskId = task.id,
                worker = worker,
                expectedTarget = target,
                onToolExecutionChanged = { execution ->
                    upsertRuntimeToolExecution(conversationId, execution)
                },
            )
            ensureRuntimeTaskOwner(conversationId, task.id, worker)

            val toolResultMessage = Conversation.Message(
                id = toolResultMessageId,
                conversationId = conversationId,
                role = Conversation.Message.Role.USER,
                content = executionResult.results,
                createdAt = Clock.System.now(),
            )
            if (addRuntimeMessageIfMissing(conversationId, toolResultMessage)) {
                emit(toolResultMessage)
            }
        } else {
            log.info {
                "Skipping already persisted tool execution result: " +
                    "conversation=${conversationId.value} task=${task.id.value} message=${toolResultMessageId.value}"
            }
        }
        ensureRuntimeTaskOwner(conversationId, task.id, worker)
        clearRuntimeToolExecutions(conversationId, task.id, worker)
        if (!awaitExecutionCanContinue(conversationId)) {
            return@flow
        }

        submitContinuationTask(
            toolResultProcessingTask(
                conversationId = conversationId,
                rootUserMessageId = payload.rootUserMessageId,
                toolResultMessageId = toolResultMessageId,
                agentDefinitionId = payload.agentDefinitionId,
                iteration = payload.iteration,
                returnDirect = payload.returnDirect,
            )
        )
    }

    private fun runToolResultProcessingStep(
        task: ConversationRuntimeTask,
        worker: ConversationRuntimeWorkerIdentity,
        payload: ConversationRuntimeTask.Payload.ToolResultProcessing,
    ): Flow<Conversation.Message> = flow {
        val conversationId = task.conversationId
        if (!awaitExecutionCanContinue(conversationId)) {
            return@flow
        }

        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val context = buildConversationRuntimeContext(payload.agentDefinitionId, conversation, worker)
        val toolResultMessage = conversationService.loadCurrentMessages(conversationId)
            .firstOrNull { it.id == payload.toolResultMessageId }
            ?: throw IllegalStateException(
                "Tool result message not found: conversation=${conversationId.value} " +
                    "message=${payload.toolResultMessageId.value}"
            )

        ensureRuntimeTaskOwner(conversationId, task.id, worker)
        if (context.automaticMemoryRememberEnabled) {
            routeMessageThroughMemoryRouter(
                conversationId,
                conversation.currentThread,
                toolResultMessage,
                context.agent,
                context.runtimeContext,
                context.memorySystemPrompts,
                context.memoryPipelineTools,
            )
        }
        if (!awaitExecutionCanContinue(conversationId) || payload.returnDirect) {
            return@flow
        }

        emitQueuedRuntimeMessagesAtSafePoint(
            conversationId = conversationId,
            runtimeTaskId = task.id,
            worker = worker,
            placement = QueuedMessagePlacement.AFTER_TOOL_RESULT,
            conversation = conversation,
            runtimeContext = context.runtimeContext,
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
                    agentDefinitionId = payload.agentDefinitionId,
                    iteration = payload.iteration + 1,
                )
            )
        }
    }

    private fun runMemoryRecallStep(
        task: ConversationRuntimeTask,
        worker: ConversationRuntimeWorkerIdentity,
        payload: ConversationRuntimeTask.Payload.MemoryRecall,
    ): Flow<Conversation.Message> = flow {
        val conversationId = task.conversationId
        if (!awaitExecutionCanContinue(conversationId)) {
            return@flow
        }

        ensureRuntimeTaskOwner(conversationId, task.id, worker)
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val context = buildConversationRuntimeContext(payload.agentDefinitionId, conversation, worker)
        val currentMessages = conversationService.loadCurrentMessages(conversationId)
        if (!currentMessages.hasPendingMemoryRecall(payload.targetMessageId)) {
            log.info {
                "Skipping memory recall without pending marker: conversation=${conversationId.value} target=${payload.targetMessageId.value}"
            }
            return@flow
        }
        if (currentMessages.hasCompletedMemoryRecall(payload.targetMessageId)) {
            log.info {
                "Skipping already completed memory recall: conversation=${conversationId.value} target=${payload.targetMessageId.value}"
            }
            return@flow
        }
        val targetMessage = currentMessages.firstOrNull { it.id == payload.targetMessageId }
            ?: throw IllegalStateException("Memory recall target message not found: ${payload.targetMessageId.value}")

        val runtimeMemoryResult = runCatching {
            memoryApplicationService.buildRuntimeMemoryReadResult(
                conversationId = conversationId,
                threadId = conversation.currentThread,
                targetMessage = targetMessage,
                threadMessages = currentMessages,
                runtimeContext = context.runtimeContext,
            )
        }.onFailure { error ->
            if (error is CancellationException) {
                throw error
            }
            if (error is MemoryNamespaceRecallAccessException) {
                throw error
            }
            log.warn(error) {
                "Async memory recall failed: conversation=${conversationId.value} target=${targetMessage.id.value} error=${error.message}"
            }
            if (java.lang.Boolean.getBoolean("gromozeka.memory.routing.failFast")) {
                throw error
            }
        }.getOrNull()

        buildSyntheticMemoryToolPair(
            conversationId = conversationId,
            targetMessage = targetMessage,
            toolName = MEMORY_ENRICH_CONTEXT_TOOL_NAME,
            arguments = buildJsonObject {
                put("target", "previous_user_message")
                put("target_message_id", targetMessage.id.value)
                put("mode", "automatic_runtime_context_enrichment_async_completed")
            },
            resultText = MemoryToolResultRenderer.enrichContextResultJsonString(runtimeMemoryResult),
            syntheticPhase = SYNTHETIC_MEMORY_PHASE_COMPLETED_ASYNC,
        ).forEach { syntheticMessage ->
            ensureRuntimeTaskOwner(conversationId, task.id, worker)
            if (addRuntimeMessageIfMissing(conversationId, syntheticMessage)) {
                emit(syntheticMessage)
            }
        }

        log.info {
            "Async memory recall completed: conversation=${conversationId.value} target=${targetMessage.id.value} " +
                "memoryPromptPresent=${!runtimeMemoryResult?.runtimePrompt.isNullOrBlank()} " +
                "memoryPromptChars=${runtimeMemoryResult?.runtimePrompt?.length ?: 0}"
        }

        if (awaitExecutionCanContinue(conversationId)) {
            submitContinuationTask(
                llmCallTask(
                    conversationId = conversationId,
                    rootUserMessageId = payload.rootUserMessageId,
                    agentDefinitionId = payload.agentDefinitionId,
                    iteration = payload.followUpIteration,
                )
            )
        }
    }

    private fun runExecutionIncidentStep(
        task: ConversationRuntimeTask,
        worker: ConversationRuntimeWorkerIdentity,
        payload: ConversationRuntimeTask.Payload.ExecutionIncident,
    ): Flow<Conversation.Message> = flow {
        val conversationId = task.conversationId
        ensureRuntimeTaskOwner(conversationId, task.id, worker)
        val incident = runtimeCoordinator.findTaskIncident(conversationId, payload.sourceTaskId)
            ?: throw IllegalStateException(
                "Runtime execution incident not found: " +
                    "conversation=${conversationId.value} task=${payload.sourceTaskId.value}"
            )

        when (val sourcePayload = incident.task.payload) {
            is ConversationRuntimeTask.Payload.ToolExecution -> {
                val toolResultMessageId = runtimeMessageId(incident.task.id, "result")
                val existingToolResult = conversationService.loadCurrentMessages(conversationId)
                    .firstOrNull { it.id == toolResultMessageId }
                if (existingToolResult == null) {
                    val resultText = executionIncidentResult(incident)
                    val toolResultMessage = Conversation.Message(
                        id = toolResultMessageId,
                        conversationId = conversationId,
                        role = Conversation.Message.Role.USER,
                        content = sourcePayload.toolCalls.map { toolCall ->
                            ContentItem.ToolResult(
                                toolUseId = toolCall.id,
                                toolName = toolCall.call.name,
                                result = listOf(ContentItem.ToolResult.Data.Text(resultText)),
                                isError = true,
                            )
                        },
                        providerMetadata = buildJsonObject {
                            put("synthetic", true)
                            put("syntheticKind", "runtime_execution_incident")
                            put("sourceRuntimeTaskId", incident.task.id.value)
                            put("incidentKind", incident.kind.name)
                        },
                        createdAt = incident.occurredAt,
                    )
                    ensureRuntimeTaskOwner(conversationId, task.id, worker)
                    if (addRuntimeMessageIfMissing(conversationId, toolResultMessage)) {
                        emit(toolResultMessage)
                    }
                }

                if (awaitExecutionCanContinue(conversationId)) {
                    submitContinuationTask(
                        toolResultProcessingTask(
                            conversationId = conversationId,
                            rootUserMessageId = sourcePayload.rootUserMessageId,
                            toolResultMessageId = toolResultMessageId,
                            agentDefinitionId = sourcePayload.agentDefinitionId,
                            iteration = sourcePayload.iteration,
                            returnDirect = false,
                        )
                    )
                }
            }
            is ConversationRuntimeTask.Payload.ExecutionIncident -> Unit
            else -> {
                if (sourcePayload is ConversationRuntimeTask.Payload.UserTurn) {
                    ensureRuntimeTaskOwner(conversationId, task.id, worker)
                    if (addRuntimeMessageIfMissing(conversationId, sourcePayload.userMessage)) {
                        emit(sourcePayload.userMessage)
                    }
                }
                val notification = Conversation.Message(
                    id = runtimeMessageId(task.id, "notification"),
                    conversationId = conversationId,
                    role = Conversation.Message.Role.SYSTEM,
                    content = listOf(
                        ContentItem.UserMessage(executionIncidentSystemNotification(incident))
                    ),
                    providerMetadata = buildJsonObject {
                        put("synthetic", true)
                        put("syntheticKind", "runtime_execution_incident")
                        put("sourceRuntimeTaskId", incident.task.id.value)
                        put("incidentKind", incident.kind.name)
                    },
                    createdAt = incident.occurredAt,
                )
                ensureRuntimeTaskOwner(conversationId, task.id, worker)
                if (addRuntimeMessageIfMissing(conversationId, notification)) {
                    emit(notification)
                }
            }
        }
    }

    private fun runMemoryRunCompletionStep(
        task: ConversationRuntimeTask,
        worker: ConversationRuntimeWorkerIdentity,
        payload: ConversationRuntimeTask.Payload.MemoryRunCompletion,
    ): Flow<Conversation.Message> = flow {
        val conversationId = task.conversationId
        if (!awaitExecutionCanContinue(conversationId)) return@flow
        ensureRuntimeTaskOwner(conversationId, task.id, worker)

        val statusResult = memoryToolApplicationService.memoryRunStatus(payload.runId.value)
        val syntheticMessages = buildSyntheticMemoryToolPair(
            conversationId = conversationId,
            syntheticTargetId = payload.runId.value,
            targetMetadataName = "runId",
            toolName = payload.statusToolName,
            arguments = buildJsonObject {
                put("run_id", payload.runId.value)
                put("include_children", true)
            },
            resultText = statusResult,
            syntheticPhase = SYNTHETIC_MEMORY_PHASE_COMPLETED_ASYNC,
        )
        syntheticMessages.forEach { syntheticMessage ->
            ensureRuntimeTaskOwner(conversationId, task.id, worker)
            if (addRuntimeMessageIfMissing(conversationId, syntheticMessage)) {
                emit(syntheticMessage)
            }
        }

        if (awaitExecutionCanContinue(conversationId)) {
            val resultMessage = syntheticMessages.last()
            submitContinuationTask(
                llmCallTask(
                    conversationId = conversationId,
                    rootUserMessageId = resultMessage.id,
                    agentDefinitionId = payload.agentDefinitionId,
                    iteration = 1,
                )
            )
        }
    }

    private fun executionIncidentResult(incident: ConversationRuntimeTaskIncident): String =
        buildJsonObject {
            put("status", incident.kind.name.lowercase())
            put("runtime_task_id", incident.task.id.value)
            put("incident_kind", incident.kind.name)
            put(
                "execution_started",
                incident.kind == ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN,
            )
            incident.executionStartedAt?.let { put("execution_started_at", it.toString()) }
            put("occurred_at", incident.occurredAt.toString())
            put("message", incident.message)
            incident.errorType?.let { put("error_type", it) }
            incident.worker?.let { worker ->
                put("worker_id", worker.workerId.value)
                put("worker_session_id", worker.sessionId.value)
            }
            put("automatic_retry_performed", false)
        }.toString()

    private fun executionIncidentSystemNotification(incident: ConversationRuntimeTaskIncident): String =
        buildString {
            append("<system-reminder>\n")
            append("A runtime execution incident occurred. ")
            append("Task ")
            append(incident.task.id.value)
            append(" ended with ")
            append(incident.kind.name)
            append(". ")
            append(incident.message)
            when (incident.kind) {
                ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED ->
                    append("\nExecution did not start and no automatic retry was performed.\n")
                ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN -> {
                    append("\nExecution may have produced partial or complete side effects. ")
                    append("No automatic retry was performed. Do not assume success or failure ")
                    append("without an explicit result.\n")
                }
            }
            append("</system-reminder>")
        }

    private data class ConversationRuntimeStepContext(
        val runtimeContext: RuntimeEnvironmentContext.ProjectBound,
        val agent: AgentDefinition,
        val runtime: AiRuntime,
        val provider: AiProvider,
        val modelName: String,
        val toolCatalog: DistributedAiToolCatalogSnapshot,
        val memoryPipelineTools: List<AiToolCallback>,
        val memorySystemPrompts: List<String>,
        val runtimeSystemPrompts: List<String>,
        val assistantResponseFormat: com.gromozeka.domain.model.ai.AiModelConfiguration.AssistantResponseFormat,
        val autoCompactionThresholdTokens: Int?,
        val automaticMemoryRememberEnabled: Boolean,
        val automaticMemoryRecallEnabled: Boolean,
    ) {
        val project get() = runtimeContext.project
    }

    private suspend fun buildConversationRuntimeContext(
        agentDefinitionId: AgentDefinition.Id,
        conversation: Conversation,
        worker: ConversationRuntimeWorkerIdentity,
    ): ConversationRuntimeStepContext {
        val agent = agentDomainService.findById(agentDefinitionId)
            ?: throw IllegalStateException(
                "Agent not found for conversation ${conversation.id.value}: ${agentDefinitionId.value}"
            )
        require(agent.type is AgentDefinition.Type.Builtin || agent.projectId == conversation.projectId) {
            "Agent ${agentDefinitionId.value} does not belong to conversation project ${conversation.projectId.value}"
        }
        val project = conversationService.getProject(conversation.id)
        val runtimeContext = RuntimeEnvironmentContext.ProjectBound(
            project = project,
            workerId = worker.workerId.value,
        )
        val resolvedRuntime = settingsProvider.resolveAiRuntime(agent.runtimeSelection)
        val provider = resolvedRuntime.connection.kind.provider
        val modelName = resolvedRuntime.modelConfiguration.providerModelId
        log.info {
            "Agent config: name=${agent.name}, modelName=$modelName, provider=$provider, " +
                "runtimeOverrides=${agent.runtimeOverrides}"
        }

        val runtime = aiRuntimeProvider.getRuntime(
            agent.runtimeSelection,
            null,
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
        val baseToolCatalog = distributedToolCatalog.snapshot(project)
        val agentSkillRuntime = agentSkillRuntimeCatalogService.prepare(
            agent = agent,
            projectId = project.id,
            runtimeWorkerId = worker.workerId,
            toolCatalog = baseToolCatalog,
        )
        val toolCatalog = agentSkillRuntime.toolCatalog
        val memoryPipelineTools = aiToolProvider.getTools()
            .supportedBy(runtimeWorkerDescriptor.capabilities)
            .withoutMemoryManagementTools()
        val baseSystemPrompts = agentPromptAssemblyService.assembleSystemPrompt(agent, runtimeContext)
        val assistantResponseFormat = resolvedRuntime.modelConfiguration.assistantResponseFormat
        val runtimeSystemPrompts = buildList {
            addAll(baseSystemPrompts)
            add(toolCatalog.environmentPrompt)
            agentSkillRuntime.systemPrompt?.let(::add)
            AssistantResponseFormatContract.instruction(assistantResponseFormat)?.let(::add)
        }

        return ConversationRuntimeStepContext(
            runtimeContext = runtimeContext,
            agent = agent,
            runtime = runtime,
            provider = provider,
            modelName = modelName,
            toolCatalog = toolCatalog,
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
        runtimeContext: RuntimeEnvironmentContext,
        userMessage: Conversation.Message,
        agent: AgentDefinition,
        memorySystemPrompts: List<String>,
        memoryPipelineTools: List<AiToolCallback>,
        automaticMemoryRememberEnabled: Boolean,
        automaticMemoryRecallEnabled: Boolean,
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
                runtimeContext,
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

        if (automaticMemoryRecallEnabled) {
            buildSyntheticMemoryToolPair(
                conversationId = conversationId,
                targetMessage = userMessage,
                toolName = MEMORY_ENRICH_CONTEXT_TOOL_NAME,
                arguments = buildJsonObject {
                    put("target", "previous_user_message")
                    put("target_message_id", userMessage.id.value)
                    put("mode", "automatic_runtime_context_enrichment_async_pending")
                },
                resultText = MemoryToolResultRenderer.pendingEnrichContextResultJsonString(),
                syntheticPhase = SYNTHETIC_MEMORY_PHASE_PENDING,
            ).forEach { syntheticMessage ->
                if (addRuntimeMessageIfMissing(conversationId, syntheticMessage)) {
                    emittedMessages.add(syntheticMessage)
                }
            }
        }

        log.info {
            "Memory auto wiring: conversation=${conversationId.value} autoRemember=$automaticMemoryRememberEnabled autoRecall=$automaticMemoryRecallEnabled " +
                "systemPrompts=${memorySystemPrompts.size} rememberTriggered=$automaticMemoryRememberEnabled recallQueued=$automaticMemoryRecallEnabled"
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
        val accepted = runtimeCoordinator.submit(task)
        if (!accepted) {
            throw IllegalStateException("Conversation runtime rejected continuation task: ${task.id.value}")
        }
        publishRuntimeSnapshot(task.conversationId)
    }

    private suspend fun submitPendingMemoryRecallIfNeeded(
        conversationId: Conversation.Id,
        rootUserMessageId: Conversation.Message.Id,
        agentDefinitionId: AgentDefinition.Id,
        nextLlmIteration: Int,
        automaticMemoryRecallEnabled: Boolean,
    ) {
        if (!automaticMemoryRecallEnabled) {
            return
        }
        val targetMessageId = conversationService.loadCurrentMessages(conversationId)
            .nextPendingMemoryRecallTarget()
            ?: return
        submitContinuationTask(
            memoryRecallTask(
                conversationId = conversationId,
                rootUserMessageId = rootUserMessageId,
                targetMessageId = targetMessageId,
                agentDefinitionId = agentDefinitionId,
                followUpIteration = nextLlmIteration,
            )
        )
    }

    private fun llmCallTask(
        conversationId: Conversation.Id,
        rootUserMessageId: Conversation.Message.Id,
        agentDefinitionId: AgentDefinition.Id,
        iteration: Int,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${rootUserMessageId.value}:llm:$iteration"),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.LlmCall(
                rootUserMessageId = rootUserMessageId,
                agentDefinitionId = agentDefinitionId,
                iteration = iteration,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "conversation:${conversationId.value}:runtime:${rootUserMessageId.value}:llm:$iteration",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.LLM_RUNTIME,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
            ),
            createdAt = Clock.System.now(),
        )

    private fun memoryRecallTask(
        conversationId: Conversation.Id,
        rootUserMessageId: Conversation.Message.Id,
        targetMessageId: Conversation.Message.Id,
        agentDefinitionId: AgentDefinition.Id,
        followUpIteration: Int,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${targetMessageId.value}:memory-recall"),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.MemoryRecall(
                rootUserMessageId = rootUserMessageId,
                targetMessageId = targetMessageId,
                agentDefinitionId = agentDefinitionId,
                followUpIteration = followUpIteration,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "conversation:${conversationId.value}:runtime:${targetMessageId.value}:memory-recall",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(ConversationRuntimeWorkerCapability.MEMORY_PIPELINE),
            ),
            createdAt = Clock.System.now(),
        )

    private suspend fun toolExecutionTask(
        conversationId: Conversation.Id,
        rootUserMessageId: Conversation.Message.Id,
        agentDefinitionId: AgentDefinition.Id,
        iteration: Int,
        toolCalls: List<ContentItem.ToolCall>,
        routing: ConversationRuntimeToolRoutingResult.Accepted,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${rootUserMessageId.value}:tools:$iteration"),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.ToolExecution(
                rootUserMessageId = rootUserMessageId,
                agentDefinitionId = agentDefinitionId,
                iteration = iteration,
                toolCalls = toolCalls,
                returnDirect = routing.returnDirect,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "conversation:${conversationId.value}:runtime:${rootUserMessageId.value}:tools:$iteration",
            requirements = routing.requirements,
            createdAt = Clock.System.now(),
        )

    private fun toolResultProcessingTask(
        conversationId: Conversation.Id,
        rootUserMessageId: Conversation.Message.Id,
        toolResultMessageId: Conversation.Message.Id,
        agentDefinitionId: AgentDefinition.Id,
        iteration: Int,
        returnDirect: Boolean,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${rootUserMessageId.value}:tool-result-processing:$iteration"),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.ToolResultProcessing(
                rootUserMessageId = rootUserMessageId,
                toolResultMessageId = toolResultMessageId,
                agentDefinitionId = agentDefinitionId,
                iteration = iteration,
                returnDirect = returnDirect,
            ),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey =
                "conversation:${conversationId.value}:runtime:${rootUserMessageId.value}:tool-result-processing:$iteration",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(
                    ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
                ),
            ),
            createdAt = Clock.System.now(),
        )

    private companion object {
        const val MAX_TOOL_LOOP_ITERATIONS = 200
        const val SYNTHETIC_MEMORY_PHASE_COMPLETED = "completed"
        const val SYNTHETIC_MEMORY_PHASE_PENDING = "pending"
        const val SYNTHETIC_MEMORY_PHASE_COMPLETED_ASYNC = "completed_async"
    }

    private suspend fun routeMessageThroughMemoryRouter(
        conversationId: Conversation.Id,
        threadId: Conversation.Thread.Id,
        message: Conversation.Message,
        agent: AgentDefinition,
        runtimeContext: RuntimeEnvironmentContext,
        systemPrompts: List<String>,
        tools: List<AiToolCallback>,
    ): DirectStructuredMemoryWriteResult? {
        return runCatching {
            memoryMessageRoutingApplicationService.routeMessage(
                conversationId = conversationId,
                threadId = threadId,
                message = message,
                agent = agent,
                runtimeContext = runtimeContext,
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
        worker: ConversationRuntimeWorkerIdentity,
        placement: QueuedMessagePlacement,
    ): List<ConversationRuntimeTask> {
        val tasks = runtimeCoordinator.takeActiveInsertions(conversationId, runtimeTaskId, worker, placement)
        if (tasks.isNotEmpty()) {
            publishRuntimeSnapshot(conversationId)
        }
        return tasks
    }

    private suspend fun ensureRuntimeTaskOwner(
        conversationId: Conversation.Id,
        runtimeTaskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
    ) {
        val accepted = runtimeCoordinator.confirmActiveTaskOwner(conversationId, runtimeTaskId, worker)
        if (!accepted) {
            throw IllegalStateException(
                "Conversation runtime task ownership was lost before side effect: " +
                    "conversation=${conversationId.value} task=${runtimeTaskId.value} worker=$worker"
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
                    "conversation=${conversationId.value} task=${execution.runtimeTaskId?.value} worker=${execution.worker}"
            )
        }
        publishRuntimeSnapshot(conversationId)
    }

    private suspend fun clearRuntimeToolExecutions(
        conversationId: Conversation.Id,
        runtimeTaskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
    ) {
        if (runtimeCoordinator.clearToolExecutions(conversationId, runtimeTaskId, worker)) {
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
        worker: ConversationRuntimeWorkerIdentity,
        placement: QueuedMessagePlacement,
        conversation: Conversation,
        runtimeContext: RuntimeEnvironmentContext,
        memorySystemPrompts: List<String>,
        memoryPipelineTools: List<AiToolCallback>,
        automaticMemoryRememberEnabled: Boolean,
        automaticMemoryRecallEnabled: Boolean,
    ): List<Conversation.Message> {
        val queuedMessages = popQueuedRuntimeMessages(conversationId, runtimeTaskId, worker, placement)
        if (queuedMessages.isEmpty()) {
            return emptyList()
        }

        return queuedMessages.flatMap { queued ->
            appendQueuedUserMessage(
                conversationId = conversationId,
                conversation = conversation,
                runtimeContext = runtimeContext,
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
        runtimeContext: RuntimeEnvironmentContext,
        queued: ConversationRuntimeTask,
        memorySystemPrompts: List<String>,
        memoryPipelineTools: List<AiToolCallback>,
        automaticMemoryRememberEnabled: Boolean,
        automaticMemoryRecallEnabled: Boolean,
    ): List<Conversation.Message> {
        val emittedMessages = mutableListOf<Conversation.Message>()
        val userTurn = queued.requireUserTurn()
        val userMessage = userTurn.userMessage
        val agent = agentDomainService.findById(userTurn.agentDefinitionId)
            ?: throw IllegalStateException(
                "Agent not found for queued message ${userMessage.id.value}: ${userTurn.agentDefinitionId.value}"
            )

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
            routeMessageThroughMemoryRouter(
                conversationId,
                conversation.currentThread,
                userMessage,
                agent,
                runtimeContext,
                memorySystemPrompts,
                memoryPipelineTools,
            )
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

        if (automaticMemoryRecallEnabled) {
            val recallMessages = buildSyntheticMemoryToolPair(
                conversationId = conversationId,
                targetMessage = userMessage,
                toolName = MEMORY_ENRICH_CONTEXT_TOOL_NAME,
                arguments = buildJsonObject {
                    put("target", "previous_user_message")
                    put("target_message_id", userMessage.id.value)
                    put("mode", "automatic_runtime_context_enrichment_async_pending")
                },
                resultText = MemoryToolResultRenderer.pendingEnrichContextResultJsonString(),
                syntheticPhase = SYNTHETIC_MEMORY_PHASE_PENDING,
            )
            recallMessages.forEach { syntheticMessage ->
                if (addRuntimeMessageIfMissing(conversationId, syntheticMessage)) {
                    emittedMessages.add(syntheticMessage)
                }
            }
        }

        return emittedMessages
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
        syntheticPhase: String = SYNTHETIC_MEMORY_PHASE_COMPLETED,
    ): List<Conversation.Message> =
        buildSyntheticMemoryToolPair(
            conversationId = conversationId,
            syntheticTargetId = targetMessage.id.value,
            targetMetadataName = "targetMessageId",
            toolName = toolName,
            arguments = arguments,
            resultText = resultText,
            syntheticPhase = syntheticPhase,
        )

    private fun buildSyntheticMemoryToolPair(
        conversationId: Conversation.Id,
        syntheticTargetId: String,
        targetMetadataName: String,
        toolName: String,
        arguments: JsonObject,
        resultText: String,
        syntheticPhase: String,
    ): List<Conversation.Message> {
        val phaseIdSuffix = if (syntheticPhase == SYNTHETIC_MEMORY_PHASE_COMPLETED) "" else ":$syntheticPhase"
        val phaseToolCallSuffix = if (syntheticPhase == SYNTHETIC_MEMORY_PHASE_COMPLETED) "" else "_${stableIdentifierSlug(syntheticPhase)}"
        val toolCallId = ContentItem.ToolCall.Id(
            "mem_${stableIdentifierSlug(syntheticTargetId)}_${stableIdentifierSlug(toolName)}$phaseToolCallSuffix"
        )
        val createdAt = Clock.System.now()
        val toolCallMessage = Conversation.Message(
            id = Conversation.Message.Id("$syntheticTargetId:memory:$toolName$phaseIdSuffix:call"),
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
                put("syntheticToolName", toolName)
                put("syntheticPhase", syntheticPhase)
                put(targetMetadataName, syntheticTargetId)
            },
            createdAt = createdAt,
        )

        val toolResultMessage = Conversation.Message(
            id = Conversation.Message.Id("$syntheticTargetId:memory:$toolName$phaseIdSuffix:result"),
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
                put("syntheticToolName", toolName)
                put("syntheticPhase", syntheticPhase)
                put(targetMetadataName, syntheticTargetId)
            },
            createdAt = createdAt,
        )

        return listOf(toolCallMessage, toolResultMessage)
    }

    private fun List<Conversation.Message>.nextPendingMemoryRecallTarget(): Conversation.Message.Id? {
        val completedTargets = mapNotNull { message ->
            message.syntheticMemoryTargetIdOrNull(
                toolName = MEMORY_ENRICH_CONTEXT_TOOL_NAME,
                phase = SYNTHETIC_MEMORY_PHASE_COMPLETED_ASYNC,
            )
        }.toSet()
        return asSequence()
            .mapNotNull { message ->
                message.syntheticMemoryTargetIdOrNull(
                    toolName = MEMORY_ENRICH_CONTEXT_TOOL_NAME,
                    phase = SYNTHETIC_MEMORY_PHASE_PENDING,
                )
            }
            .firstOrNull { targetMessageId -> targetMessageId !in completedTargets }
    }

    private fun List<Conversation.Message>.hasPendingMemoryRecall(targetMessageId: Conversation.Message.Id): Boolean =
        any { message ->
            message.syntheticMemoryTargetIdOrNull(
                toolName = MEMORY_ENRICH_CONTEXT_TOOL_NAME,
                phase = SYNTHETIC_MEMORY_PHASE_PENDING,
            ) == targetMessageId
        }

    private fun List<Conversation.Message>.hasCompletedMemoryRecall(targetMessageId: Conversation.Message.Id): Boolean =
        any { message ->
            message.syntheticMemoryTargetIdOrNull(
                toolName = MEMORY_ENRICH_CONTEXT_TOOL_NAME,
                phase = SYNTHETIC_MEMORY_PHASE_COMPLETED_ASYNC,
            ) == targetMessageId
        }

    private fun Conversation.Message.syntheticMemoryTargetIdOrNull(
        toolName: String,
        phase: String,
    ): Conversation.Message.Id? {
        if (providerMetadata.stringValue("syntheticKind") != "memory") {
            return null
        }
        if (providerMetadata.stringValue("syntheticToolName") != toolName) {
            return null
        }
        if (providerMetadata.stringValue("syntheticPhase") != phase) {
            return null
        }
        return providerMetadata.stringValue("targetMessageId")
            ?.takeIf { it.isNotBlank() }
            ?.let(Conversation.Message::Id)
    }

    private fun JsonObject.stringValue(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun stableIdentifierSlug(value: String): String =
        value.filter { it.isLetterOrDigit() }.take(48).ifBlank { "x" }

}
