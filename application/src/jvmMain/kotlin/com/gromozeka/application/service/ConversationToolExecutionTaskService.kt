package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskOutcome
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeStateSyncService
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.WorkerToolExecutionClient
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY_AUTOMATIC
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_NAMESPACE
import com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_TARGET_MESSAGE_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_THREAD_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKER_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKSPACE_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKSPACE_MOUNT_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKSPACE_ROOT_PATH
import com.gromozeka.domain.tool.ToolExecutionContext
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Clock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
class ConversationToolExecutionTaskService(
    private val conversationService: ConversationDomainService,
    private val conversationMessageAppender: ConversationRuntimeMessageAppender,
    private val workspaceService: WorkspaceDomainService,
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeStateSyncService: ConversationRuntimeStateSyncService,
    private val parallelToolExecutor: ParallelToolExecutor,
    private val workerAccessService: WorkerAccessService,
    private val workerTargetResolver: ConversationRuntimeWorkerTargetResolver,
    private val workerToolExecutionClient: WorkerToolExecutionClient,
    private val artifactService: ConversationArtifactApplicationService,
    private val toolSecretResolutionService: ToolSecretResolutionService,
) {
    private val log = KLoggers.logger(this)

    suspend fun run(
        task: ConversationRuntimeTask,
        executor: ConversationRuntimeExecutorIdentity,
        payload: ConversationRuntimeTask.Payload.ToolExecution,
        emitMessage: suspend (Conversation.Message) -> Unit,
    ): ConversationRuntimeTaskOutcome {
        val conversationId = task.conversationId
        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val project = conversationService.getProject(conversationId)
        require(task.requirements.target.matches(executor)) {
            "Tool execution task ${task.id.value} targets ${task.requirements.target} but is running on $executor"
        }
        val toolResultMessageId = runtimeMessageId(task.id, "result")
        val existingToolResult = conversationService.loadCurrentMessages(conversationId)
            .firstOrNull { it.id == toolResultMessageId }

        if (existingToolResult == null) {
            val modelToolNamesByCallId = payload.toolCalls.associate { it.id.value to it.call.name }
            val executionToolCalls = payload.toolCalls.withExecutionToolNames(
                payload.executionToolNamesByCallId
            )
            val baseToolContext = ToolExecutionContext(
                buildMap {
                    put(TOOL_CONTEXT_CONVERSATION_ID, conversationId.value)
                    put(TOOL_CONTEXT_THREAD_ID, conversation.currentThread.value)
                    put(TOOL_CONTEXT_TARGET_MESSAGE_ID, payload.rootUserMessageId.value)
                    put(TOOL_CONTEXT_PROJECT_ID, project.id.value)
                    put(TOOL_CONTEXT_MEMORY_NAMESPACE, MemoryNamespace.forProject(project.id).value)
                    put(TOOL_CONTEXT_AGENT_DEFINITION_ID, payload.agentDefinitionId.value)
                    task.actorUserId?.let { put(TOOL_CONTEXT_USER_ID, it.value) }
                    put(
                        TOOL_CONTEXT_MEMORY_RESULT_DELIVERY,
                        TOOL_CONTEXT_MEMORY_RESULT_DELIVERY_AUTOMATIC,
                    )
                }
            )
            val toolContextsByTarget = mutableMapOf<ConversationRuntimeTaskTarget, ToolExecutionContext>()
            payload.executionTargetsByCallId.values.distinct().forEach { target ->
                toolContextsByTarget[target] = prepareToolContext(
                    target = target,
                    projectId = project.id,
                    base = baseToolContext,
                )
            }
            val resolvedSecretsByToolCallId = toolSecretResolutionService.resolve(
                userId = task.actorUserId,
                toolCalls = executionToolCalls,
            )
            ensureRuntimeTaskOwner(conversationId, task.id, executor)
            clearRuntimeToolExecutions(conversationId, task.id, executor)
            val modelToolCallsById = payload.toolCalls.associateBy { it.id.value }
            val executionResult = executeParallelByTarget(
                toolCalls = executionToolCalls,
                executionTargetsByCallId = payload.executionTargetsByCallId,
            ) { target, targetToolCalls ->
                val targetCallIds = targetToolCalls.mapTo(mutableSetOf()) { it.id.value }
                val targetSecrets = resolvedSecretsByToolCallId.filterKeys(targetCallIds::contains)
                when (target) {
                    ConversationRuntimeTaskTarget.Server ->
                        parallelToolExecutor.executeParallel(
                            toolCalls = targetToolCalls,
                            toolContext = toolContextsByTarget.getValue(target),
                            runtimeTaskId = task.id,
                            executor = executor,
                            expectedTarget = target,
                            resolvedSecretsByToolCallId = targetSecrets,
                            onToolExecutionChanged = { execution ->
                                upsertRuntimeToolExecution(
                                    conversationId,
                                    execution.withModelToolName(modelToolNamesByCallId),
                                )
                            },
                        )

                    is ConversationRuntimeTaskTarget.Worker -> {
                        val modelToolCalls = targetToolCalls.map { modelToolCallsById.getValue(it.id.value) }
                        val startedAt = markRemoteToolExecutionsRunning(
                            conversationId = conversationId,
                            task = task,
                            executor = executor,
                            toolCalls = modelToolCalls,
                        )
                        try {
                            val targetIdentity = workerTargetResolver.requireRegistered(
                                target.workerId,
                                ConversationRuntimeCapability.TOOL_EXECUTION,
                            )
                            workerToolExecutionClient.execute(
                                target = targetIdentity,
                                executionTarget = target,
                                toolCalls = targetToolCalls,
                                toolContext = toolContextsByTarget.getValue(target),
                                resolvedSecretsByToolCallId = targetSecrets,
                            ).also { result ->
                                markRemoteToolExecutionsCompleted(
                                    conversationId = conversationId,
                                    task = task,
                                    executor = executor,
                                    results = result.results.map { it.withModelToolName(modelToolNamesByCallId) },
                                    startedAt = startedAt,
                                )
                            }.let {
                                ToolExecutionResult(
                                    results = it.results,
                                    returnDirect = it.returnDirect,
                                )
                            }
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            markRemoteToolExecutionsFailed(
                                conversationId = conversationId,
                                task = task,
                                executor = executor,
                                toolCalls = modelToolCalls,
                                startedAt = startedAt,
                            )
                            log.warn(error) {
                                "Worker tool execution returned no result; reporting an unknown outcome to the model: " +
                                    "conversation=${conversationId.value} worker=${target.workerId.value}"
                            }
                            workerToolExecutionFailure(targetToolCalls, error)
                        }
                    }
                }
            }
            ensureRuntimeTaskOwner(conversationId, task.id, executor)
            val persistedResults = artifactService.persistAndCommitToolResults(
                conversation = conversation,
                createdByUserId = task.actorUserId,
                results = executionResult.results,
            ).map { result -> result.withModelToolName(modelToolNamesByCallId) }

            val toolResultMessage = Conversation.Message(
                id = toolResultMessageId,
                conversationId = conversationId,
                role = Conversation.Message.Role.USER,
                content = persistedResults,
                createdAt = Clock.System.now(),
            )
            if (addRuntimeMessageIfMissing(conversationId, toolResultMessage)) {
                emitMessage(toolResultMessage)
            }
        } else {
            log.info {
                "Skipping already persisted tool execution result: " +
                    "conversation=${conversationId.value} task=${task.id.value} message=${toolResultMessageId.value}"
            }
        }
        ensureRuntimeTaskOwner(conversationId, task.id, executor)
        clearRuntimeToolExecutions(conversationId, task.id, executor)
        return ConversationRuntimeTaskOutcome.Continue(
            toolResultProcessingTask(
                parentTask = task,
                conversationId = conversationId,
                rootUserMessageId = payload.rootUserMessageId,
                toolResultMessageId = toolResultMessageId,
                agentDefinitionId = payload.agentDefinitionId,
                iteration = payload.iteration,
                returnDirect = payload.returnDirect,
                actorUserId = task.actorUserId,
            ),
        )
    }

    private suspend fun prepareToolContext(
        target: ConversationRuntimeTaskTarget,
        projectId: Project.Id,
        base: ToolExecutionContext,
    ): ToolExecutionContext = when (target) {
        ConversationRuntimeTaskTarget.Server -> base
        is ConversationRuntimeTaskTarget.Worker -> {
            workerAccessService.requireProjectAccess(target.workerId, projectId)
            var context = base.withValue(TOOL_CONTEXT_WORKER_ID, target.workerId.value)
            target.workspaceMountId?.let { mountId ->
                val resolved = workspaceService.resolveExecution(mountId)
                require(resolved.project.id == projectId) {
                    "Tool execution workspace mount ${mountId.value} belongs to project " +
                        "${resolved.project.id.value}, not ${projectId.value}"
                }
                require(resolved.mount.workerId == target.workerId.value) {
                    "Tool execution workspace mount ${mountId.value} belongs to executor " +
                        "${resolved.mount.workerId}, not ${target.workerId.value}"
                }
                context = context
                    .withValue(TOOL_CONTEXT_WORKSPACE_ID, resolved.workspace.id.value)
                    .withValue(TOOL_CONTEXT_WORKSPACE_MOUNT_ID, resolved.mount.id.value)
                    .withValue(TOOL_CONTEXT_WORKSPACE_ROOT_PATH, resolved.mount.rootPath)
            }
            context
        }
    }

    private fun toolResultProcessingTask(
        parentTask: ConversationRuntimeTask,
        conversationId: Conversation.Id,
        rootUserMessageId: Conversation.Message.Id,
        toolResultMessageId: Conversation.Message.Id,
        agentDefinitionId: AgentDefinition.Id,
        iteration: Int,
        returnDirect: Boolean,
        actorUserId: User.Id?,
    ): ConversationRuntimeTask =
        ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${rootUserMessageId.value}:tool-result-processing:$iteration"),
            conversationId = conversationId,
            turnId = parentTask.turnId,
            parentTaskId = parentTask.id,
            actorUserId = actorUserId,
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
                    ConversationRuntimeCapability.CONVERSATION_TURN,
                    ConversationRuntimeCapability.MEMORY_PIPELINE,
                ),
                target = ConversationRuntimeTaskTarget.Server,
            ),
            createdAt = Clock.System.now(),
        )

    private suspend fun ensureRuntimeTaskOwner(
        conversationId: Conversation.Id,
        runtimeTaskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
    ) {
        val accepted = runtimeCoordinator.confirmActiveTaskOwner(conversationId, runtimeTaskId, executor)
        if (!accepted) {
            throw IllegalStateException(
                "Conversation runtime task ownership was lost before side effect: " +
                    "conversation=${conversationId.value} task=${runtimeTaskId.value} executor=$executor"
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
                    "conversation=${conversationId.value} task=${execution.runtimeTaskId?.value} " +
                    "executor=${execution.executor}"
            )
        }
        publishRuntimeSnapshot(conversationId)
    }

    private suspend fun markRemoteToolExecutionsRunning(
        conversationId: Conversation.Id,
        task: ConversationRuntimeTask,
        executor: ConversationRuntimeExecutorIdentity,
        toolCalls: List<Conversation.Message.ContentItem.ToolCall>,
    ): kotlin.time.Instant {
        val startedAt = Clock.System.now()
        toolCalls.forEach { toolCall ->
            upsertRuntimeToolExecution(
                conversationId,
                ConversationRuntimeToolExecution(
                    toolCallId = toolCall.id,
                    toolName = toolCall.call.name,
                    status = ConversationRuntimeToolExecution.Status.RUNNING,
                    runtimeTaskId = task.id,
                    executor = executor,
                    startedAt = startedAt,
                ),
            )
        }
        return startedAt
    }

    private suspend fun markRemoteToolExecutionsCompleted(
        conversationId: Conversation.Id,
        task: ConversationRuntimeTask,
        executor: ConversationRuntimeExecutorIdentity,
        results: List<Conversation.Message.ContentItem.ToolResult>,
        startedAt: kotlin.time.Instant,
    ) {
        val completedAt = Clock.System.now()
        results.forEach { result ->
            upsertRuntimeToolExecution(
                conversationId,
                ConversationRuntimeToolExecution(
                    toolCallId = result.toolUseId,
                    toolName = result.toolName,
                    status = if (result.isError) {
                        ConversationRuntimeToolExecution.Status.FAILED
                    } else {
                        ConversationRuntimeToolExecution.Status.COMPLETED
                    },
                    runtimeTaskId = task.id,
                    executor = executor,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    isError = result.isError,
                ),
            )
        }
    }

    private suspend fun markRemoteToolExecutionsFailed(
        conversationId: Conversation.Id,
        task: ConversationRuntimeTask,
        executor: ConversationRuntimeExecutorIdentity,
        toolCalls: List<Conversation.Message.ContentItem.ToolCall>,
        startedAt: kotlin.time.Instant,
    ) {
        val failedAt = Clock.System.now()
        toolCalls.forEach { toolCall ->
            upsertRuntimeToolExecution(
                conversationId,
                ConversationRuntimeToolExecution(
                    toolCallId = toolCall.id,
                    toolName = toolCall.call.name,
                    status = ConversationRuntimeToolExecution.Status.FAILED,
                    runtimeTaskId = task.id,
                    executor = executor,
                    startedAt = startedAt,
                    completedAt = failedAt,
                    isError = true,
                ),
            )
        }
    }

    private suspend fun clearRuntimeToolExecutions(
        conversationId: Conversation.Id,
        runtimeTaskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
    ) {
        if (runtimeCoordinator.clearToolExecutions(conversationId, runtimeTaskId, executor)) {
            publishRuntimeSnapshot(conversationId)
        }
    }

    private suspend fun publishRuntimeSnapshot(conversationId: Conversation.Id) {
        try {
            runtimeStateSyncService.invalidate(conversationId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn(error) {
                "Failed to publish live runtime snapshot; clients can recover from snapshot reload: " +
                    "conversation=${conversationId.value} error=${error.message}"
            }
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
        conversationMessageAppender.appendRuntimeMessage(conversationId, message)
        return true
    }

    private fun runtimeMessageId(
        taskId: ConversationRuntimeTask.Id,
        suffix: String,
    ): Conversation.Message.Id =
        Conversation.Message.Id("${taskId.value}:$suffix")

    private fun ConversationRuntimeTaskTarget.matches(executor: ConversationRuntimeExecutorIdentity): Boolean =
        when (this) {
            ConversationRuntimeTaskTarget.Server -> executor is ConversationRuntimeExecutorIdentity.Server
            is ConversationRuntimeTaskTarget.Worker ->
                executor is ConversationRuntimeExecutorIdentity.Worker &&
                    executor.identity.workerId == workerId
        }
}

internal suspend fun executeParallelByTarget(
    toolCalls: List<Conversation.Message.ContentItem.ToolCall>,
    executionTargetsByCallId: Map<String, ConversationRuntimeTaskTarget>,
    executeTarget: suspend (
        ConversationRuntimeTaskTarget,
        List<Conversation.Message.ContentItem.ToolCall>,
    ) -> ToolExecutionResult,
): ToolExecutionResult {
    val toolCallIds = toolCalls.map { it.id.value }
    require(toolCallIds.distinct().size == toolCallIds.size) {
        "Parallel target execution requires unique tool call ids"
    }
    require(executionTargetsByCallId.keys == toolCallIds.toSet()) {
        "Parallel target execution requires one exact target for every tool call"
    }

    val targetResults = coroutineScope {
        toolCalls
            .groupBy { executionTargetsByCallId.getValue(it.id.value) }
            .map { (target, calls) -> async { executeTarget(target, calls) } }
            .awaitAll()
    }
    val unorderedResults = targetResults.flatMap(ToolExecutionResult::results)
    val resultsByCallId = unorderedResults.associateBy { it.toolUseId.value }
    require(resultsByCallId.size == unorderedResults.size) {
        "Parallel target execution returned duplicate tool result ids"
    }
    require(resultsByCallId.keys == toolCallIds.toSet()) {
        "Parallel target execution must return one result for every tool call"
    }
    return ToolExecutionResult(
        results = toolCallIds.map(resultsByCallId::getValue),
        returnDirect = targetResults.all(ToolExecutionResult::returnDirect),
    )
}

internal fun List<Conversation.Message.ContentItem.ToolCall>.withExecutionToolNames(
    executionToolNamesByCallId: Map<String, String>,
): List<Conversation.Message.ContentItem.ToolCall> =
    map { toolCall ->
        val executionName = executionToolNamesByCallId[toolCall.id.value]
            ?: toolCall.call.name
        toolCall.copy(call = toolCall.call.copy(name = executionName))
    }

internal fun Conversation.Message.ContentItem.ToolResult.withModelToolName(
    modelToolNamesByCallId: Map<String, String>,
): Conversation.Message.ContentItem.ToolResult =
    modelToolNamesByCallId[toolUseId.value]
        ?.takeIf { modelName -> modelName != toolName }
        ?.let { modelName ->
            copy(
                toolName = modelName,
                executionToolName = executionToolName ?: toolName,
            )
        }
        ?: this

private fun ConversationRuntimeToolExecution.withModelToolName(
    modelToolNamesByCallId: Map<String, String>,
): ConversationRuntimeToolExecution =
    modelToolNamesByCallId[toolCallId.value]
        ?.let { modelName -> copy(toolName = modelName) }
        ?: this

internal fun workerToolExecutionFailure(
    toolCalls: List<Conversation.Message.ContentItem.ToolCall>,
    error: Throwable,
): ToolExecutionResult {
    val detail = error.message
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(MAX_WORKER_TOOL_FAILURE_DETAIL_LENGTH)
    val message = buildString {
        append("Worker tool execution did not return a result")
        detail?.let { append(": ").append(it) }
        append(". The operation may have started, so its outcome is unknown. ")
        append("Do not retry automatically; inspect the current state before continuing.")
    }
    return ToolExecutionResult(
        results = toolCalls.map { toolCall ->
            Conversation.Message.ContentItem.ToolResult(
                toolUseId = toolCall.id,
                toolName = toolCall.call.name,
                result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text(message)),
                isError = true,
            )
        },
        returnDirect = false,
    )
}

private const val MAX_WORKER_TOOL_FAILURE_DETAIL_LENGTH = 2_000

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
class WorkerConversationRuntimeTaskRunner(
    private val toolExecutionTaskService: ConversationToolExecutionTaskService,
) : ConversationRuntimeTaskRunner {
    override suspend fun runRuntimeTask(
        task: ConversationRuntimeTask,
        executor: ConversationRuntimeExecutorIdentity,
        emitMessage: suspend (Conversation.Message) -> Unit,
    ): ConversationRuntimeTaskOutcome =
        when (val payload = task.payload) {
            is ConversationRuntimeTask.Payload.ToolExecution ->
                toolExecutionTaskService.run(task, executor, payload, emitMessage)

            else -> error(
                "Worker cannot execute Server-owned runtime task ${payload::class.simpleName}: ${task.id.value}"
            )
        }
}
