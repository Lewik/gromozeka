package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeCoordinator
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeEventBus
import com.gromozeka.domain.service.ConversationRuntimeExecutorIdentity
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
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
import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
class ConversationToolExecutionTaskService(
    private val conversationService: ConversationDomainService,
    private val workspaceService: WorkspaceDomainService,
    private val runtimeCoordinator: ConversationRuntimeCoordinator,
    private val runtimeEventBus: ConversationRuntimeEventBus,
    private val parallelToolExecutor: ParallelToolExecutor,
    private val workerAccessService: WorkerAccessService,
    private val workerTargetResolver: ConversationRuntimeWorkerTargetResolver,
    private val workerToolExecutionClient: WorkerToolExecutionClient,
) {
    private val log = KLoggers.logger(this)

    fun run(
        task: ConversationRuntimeTask,
        executor: ConversationRuntimeExecutorIdentity,
        payload: ConversationRuntimeTask.Payload.ToolExecution,
    ): Flow<Conversation.Message> = flow {
        val conversationId = task.conversationId
        if (!awaitExecutionCanContinue(conversationId)) {
            return@flow
        }

        val conversation = conversationService.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        val project = conversationService.getProject(conversationId)
        require(task.requirements.target.matches(executor)) {
            "Tool execution task ${task.id.value} targets ${task.requirements.target} but is running on $executor"
        }
        val target = payload.executionTarget
        val workerTarget = target as? ConversationRuntimeTaskTarget.Worker
        workerTarget?.let {
            workerAccessService.requireProjectAccess(it.workerId, project.id)
        }
        val workspaceContext = workerTarget?.workspaceMountId?.let { mountId ->
            workspaceService.resolveExecution(mountId).also { resolved ->
                require(resolved.project.id == project.id) {
                    "Tool execution workspace mount ${mountId.value} belongs to project " +
                        "${resolved.project.id.value}, not ${project.id.value}"
                }
                require(resolved.mount.workerId == workerTarget.workerId.value) {
                    "Tool execution workspace mount ${mountId.value} belongs to executor " +
                        "${resolved.mount.workerId}, not ${workerTarget.workerId.value}"
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
                    put(TOOL_CONTEXT_MEMORY_NAMESPACE, MemoryNamespace.forProject(project.id).value)
                    workerTarget?.let { put(TOOL_CONTEXT_WORKER_ID, it.workerId.value) }
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
            ensureRuntimeTaskOwner(conversationId, task.id, executor)
            clearRuntimeToolExecutions(conversationId, task.id, executor)
            val executionResult = when (target) {
                ConversationRuntimeTaskTarget.Server ->
                    parallelToolExecutor.executeParallel(
                        toolCalls = payload.toolCalls,
                        toolContext = toolContext,
                        runtimeTaskId = task.id,
                        executor = executor,
                        expectedTarget = target,
                        onToolExecutionChanged = { execution ->
                            upsertRuntimeToolExecution(conversationId, execution)
                        },
                    )

                is ConversationRuntimeTaskTarget.Worker -> {
                    val targetIdentity = workerTargetResolver.requireOnline(
                        target.workerId,
                        ConversationRuntimeCapability.TOOL_EXECUTION,
                    )
                    val startedAt = markRemoteToolExecutionsRunning(
                        conversationId = conversationId,
                        task = task,
                        executor = executor,
                        toolCalls = payload.toolCalls,
                    )
                    try {
                        workerToolExecutionClient.execute(
                            target = targetIdentity,
                            executionTarget = target,
                            toolCalls = payload.toolCalls,
                            toolContext = toolContext,
                        ).also { result ->
                            markRemoteToolExecutionsCompleted(
                                conversationId = conversationId,
                                task = task,
                                executor = executor,
                                results = result.results,
                                startedAt = startedAt,
                            )
                        }.let { ToolExecutionResult(it.results, it.returnDirect) }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        markRemoteToolExecutionsFailed(
                            conversationId = conversationId,
                            task = task,
                            executor = executor,
                            toolCalls = payload.toolCalls,
                            startedAt = startedAt,
                        )
                        throw error
                    }
                }
            }
            ensureRuntimeTaskOwner(conversationId, task.id, executor)

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
        ensureRuntimeTaskOwner(conversationId, task.id, executor)
        clearRuntimeToolExecutions(conversationId, task.id, executor)
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

    private suspend fun submitContinuationTask(task: ConversationRuntimeTask) {
        val accepted = runtimeCoordinator.submit(task)
        if (!accepted) {
            throw IllegalStateException("Conversation runtime rejected continuation task: ${task.id.value}")
        }
        publishRuntimeSnapshot(task.conversationId)
    }

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
    ): kotlinx.datetime.Instant {
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
        startedAt: kotlinx.datetime.Instant,
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
        startedAt: kotlinx.datetime.Instant,
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
            runtimeEventBus.publish(
                ConversationRuntimeEvent.SnapshotUpdated(
                    conversationId = conversationId,
                    snapshot = runtimeCoordinator.snapshot(conversationId),
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.warn(error) {
                "Failed to publish live runtime snapshot; clients can recover from snapshot reload: " +
                    "conversation=${conversationId.value} error=${error.message}"
            }
        }
    }

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

    private fun ConversationRuntimeTaskTarget.matches(executor: ConversationRuntimeExecutorIdentity): Boolean =
        when (this) {
            ConversationRuntimeTaskTarget.Server -> executor is ConversationRuntimeExecutorIdentity.Server
            is ConversationRuntimeTaskTarget.Worker ->
                executor is ConversationRuntimeExecutorIdentity.Worker &&
                    executor.identity.workerId == workerId
        }
}

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.worker.enabled"],
    havingValue = "true",
)
class WorkerConversationRuntimeTaskRunner(
    private val toolExecutionTaskService: ConversationToolExecutionTaskService,
) : ConversationRuntimeTaskRunner {
    override fun runRuntimeTask(
        task: ConversationRuntimeTask,
        executor: ConversationRuntimeExecutorIdentity,
    ): Flow<Conversation.Message> =
        when (val payload = task.payload) {
            is ConversationRuntimeTask.Payload.ToolExecution ->
                toolExecutionTaskService.run(task, executor, payload)

            else -> flow {
                error(
                    "Worker cannot execute Server-owned runtime task ${payload::class.simpleName}: ${task.id.value}"
                )
            }
        }
}
