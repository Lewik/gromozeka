package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.jvm.JvmInline

/**
 * Durable conversation runtime task.
 *
 * A task is the public boundary between UI commands, runtime scheduling, and workers.
 * Storage implementations keep it durably until one exact executor claims it.
 */
@Serializable
data class ConversationRuntimeTask(
    val id: Id,
    val conversationId: Conversation.Id,
    val turnId: ConversationRuntimeTurnId = ConversationRuntimeTurnId(id.value),
    val parentTaskId: Id? = null,
    val actorUserId: User.Id? = null,
    val payload: Payload,
    val placement: QueuedMessagePlacement,
    val idempotencyKey: String,
    val requirements: ConversationRuntimeTaskRequirements,
    val createdAt: Instant,
) {
    init {
        require(id.value.isNotBlank()) { "Conversation runtime task id must not be blank" }
        require(idempotencyKey.isNotBlank()) { "Conversation runtime task idempotency key must not be blank" }
        require((parentTaskId == null) == payload.isRootInput()) {
            "Conversation runtime task ${id.value} must have root/continuation lineage matching ${payload::class.simpleName}"
        }
        require(requirements.capabilities.containsAll(payload.requiredCapabilities())) {
            "Conversation runtime task ${id.value} requirements do not satisfy ${payload::class.simpleName}"
        }
        userMessageOrNull()?.let { message ->
            require(message.conversationId == conversationId) {
                "Conversation runtime task ${id.value} user message belongs to another conversation"
            }
        }
        if (payload is Payload.ToolExecution) {
            require(requirements.target == ConversationRuntimeTaskTarget.Server) {
                "Conversation runtime tool orchestration must remain Server-owned"
            }
        }
    }

    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    sealed interface Payload {
        @Serializable
        @SerialName("post_message")
        data class PostMessage(
            val userMessage: Conversation.Message,
        ) : Payload

        @Serializable
        @SerialName("agent_invocation")
        data class AgentInvocation(
            val userMessage: Conversation.Message,
            val agentDefinitionId: AgentDefinition.Id,
        ) : Payload

        @Serializable
        @SerialName("history_mutation")
        data class HistoryMutation(
            val mutation: ConversationHistoryMutation,
        ) : Payload

        @Serializable
        @SerialName("llm_call")
        data class LlmCall(
            val rootUserMessageId: Conversation.Message.Id,
            val agentDefinitionId: AgentDefinition.Id,
            val iteration: Int,
        ) : Payload {
            init {
                require(iteration >= 1) { "Conversation LLM call iteration must be positive" }
            }
        }

        @Serializable
        @SerialName("tool_execution")
        data class ToolExecution(
            val rootUserMessageId: Conversation.Message.Id,
            val agentDefinitionId: AgentDefinition.Id,
            val iteration: Int,
            val toolCalls: List<ContentItem.ToolCall>,
            val returnDirect: Boolean,
            val executionTargetsByCallId: Map<String, ConversationRuntimeTaskTarget>,
            val executionToolNamesByCallId: Map<String, String> = emptyMap(),
        ) : Payload {
            init {
                require(iteration >= 1) { "Conversation tool execution iteration must be positive" }
                require(toolCalls.isNotEmpty()) { "Conversation tool execution task must contain at least one tool call" }
                val toolCallIds = toolCalls.map { it.id.value }
                require(toolCallIds.distinct().size == toolCallIds.size) {
                    "Conversation tool execution task must contain unique tool call ids"
                }
                require(executionTargetsByCallId.keys == toolCallIds.toSet()) {
                    "Conversation tool execution targets must match every tool call exactly"
                }
                require(executionToolNamesByCallId.keys.all { callId ->
                    toolCalls.any { it.id.value == callId }
                }) {
                    "Conversation tool execution name mapping references an unknown tool call"
                }
            }
        }

        @Serializable
        @SerialName("tool_result_processing")
        data class ToolResultProcessing(
            val rootUserMessageId: Conversation.Message.Id,
            val toolResultMessageId: Conversation.Message.Id,
            val agentDefinitionId: AgentDefinition.Id,
            val iteration: Int,
            val returnDirect: Boolean,
        ) : Payload {
            init {
                require(iteration >= 1) { "Conversation tool result iteration must be positive" }
            }
        }

        @Serializable
        @SerialName("memory_recall")
        data class MemoryRecall(
            val rootUserMessageId: Conversation.Message.Id,
            val targetMessageId: Conversation.Message.Id,
            val agentDefinitionId: AgentDefinition.Id,
            val followUpIteration: Int,
        ) : Payload {
            init {
                require(followUpIteration >= 1) { "Conversation memory recall follow-up iteration must be positive" }
            }
        }

        @Serializable
        @SerialName("memory_run_completion")
        data class MemoryRunCompletion(
            val runId: MemoryRun.Id,
            val agentDefinitionId: AgentDefinition.Id,
            val statusToolName: String,
        ) : Payload {
            init {
                require(statusToolName.isNotBlank()) { "Memory run status tool name must not be blank" }
            }
        }

        @Serializable
        @SerialName("background_activity_completion")
        data class BackgroundActivityCompletion(
            val sourceKey: String,
        ) : Payload {
            init {
                require(sourceKey.isNotBlank()) { "Background activity completion source key must not be blank" }
            }
        }

        @Serializable
        @SerialName("execution_incident")
        data class ExecutionIncident(
            val sourceTaskId: Id,
        ) : Payload
    }

    fun requireAgentInvocation(): Payload.AgentInvocation =
        payload as? Payload.AgentInvocation
            ?: error("Conversation runtime task ${id.value} is not an agent invocation: ${payload::class.simpleName}")

    fun userMessageOrNull(): Conversation.Message? = when (val payload = payload) {
        is Payload.PostMessage -> payload.userMessage
        is Payload.AgentInvocation -> payload.userMessage
        else -> null
    }

    fun userMessageIdOrNull(): Conversation.Message.Id? = userMessageOrNull()?.id

    fun isRootInput(): Boolean = payload.isRootInput()

    fun isContinuation(): Boolean = !isRootInput()

    private fun Payload.isRootInput(): Boolean =
        when (this) {
            is Payload.PostMessage,
            is Payload.AgentInvocation,
            is Payload.HistoryMutation,
            is Payload.MemoryRunCompletion,
            is Payload.BackgroundActivityCompletion,
            is Payload.ExecutionIncident -> true

            is Payload.LlmCall,
            is Payload.ToolExecution,
            is Payload.ToolResultProcessing,
            is Payload.MemoryRecall -> false
        }

    private fun Payload.requiredCapabilities(): Set<ConversationRuntimeCapability> =
        when (this) {
            is Payload.PostMessage -> setOf(ConversationRuntimeCapability.CONVERSATION_TURN)
            is Payload.AgentInvocation -> setOf(
                ConversationRuntimeCapability.CONVERSATION_TURN,
                ConversationRuntimeCapability.MEMORY_PIPELINE,
            )
            is Payload.HistoryMutation -> when (mutation) {
                is ConversationHistoryMutation.Compact ->
                    if (mutation.strategy == SquashType.CONCATENATE) {
                        setOf(ConversationRuntimeCapability.CONVERSATION_TURN)
                    } else {
                        setOf(
                            ConversationRuntimeCapability.CONVERSATION_TURN,
                            ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                        )
                    }
                is ConversationHistoryMutation.Edit,
                is ConversationHistoryMutation.Delete,
                -> setOf(ConversationRuntimeCapability.CONVERSATION_TURN)
            }
            is Payload.LlmCall -> setOf(
                ConversationRuntimeCapability.AI_REQUEST_RESPONSE,
                ConversationRuntimeCapability.MEMORY_PIPELINE,
            )
            is Payload.ToolExecution -> setOf(ConversationRuntimeCapability.TOOL_EXECUTION)
            is Payload.ToolResultProcessing -> setOf(
                ConversationRuntimeCapability.CONVERSATION_TURN,
                ConversationRuntimeCapability.MEMORY_PIPELINE,
            )
            is Payload.MemoryRecall -> setOf(ConversationRuntimeCapability.MEMORY_PIPELINE)
            is Payload.MemoryRunCompletion -> setOf(
                ConversationRuntimeCapability.CONVERSATION_TURN,
                ConversationRuntimeCapability.MEMORY_PIPELINE,
            )
            is Payload.BackgroundActivityCompletion -> setOf(ConversationRuntimeCapability.CONVERSATION_TURN)
            is Payload.ExecutionIncident -> setOf(ConversationRuntimeCapability.CONVERSATION_TURN)
        }
}

@Serializable
@JvmInline
value class ConversationRuntimeTurnId(val value: String) {
    init {
        require(value.isNotBlank()) { "Conversation runtime turn id must not be blank" }
    }
}

sealed interface ConversationRuntimeTaskOutcome {
    data object CompleteTurn : ConversationRuntimeTaskOutcome

    data object CompleteWithoutNotification : ConversationRuntimeTaskOutcome

    data class HistoryChanged(
        val kind: ConversationHistoryMutationKind,
    ) : ConversationRuntimeTaskOutcome

    data class Continue(
        val nextTask: ConversationRuntimeTask,
    ) : ConversationRuntimeTaskOutcome {
        init {
            require(nextTask.isContinuation()) {
                "Conversation runtime continuation outcome must contain a continuation task"
            }
        }
    }
}

@Serializable
@JsonClassDiscriminator("mutationType")
sealed interface ConversationHistoryMutation {
    @Serializable
    @SerialName("edit")
    data class Edit(
        val messageId: Conversation.Message.Id,
        val newContent: List<Conversation.Message.ContentItem>,
    ) : ConversationHistoryMutation

    @Serializable
    @SerialName("delete")
    data class Delete(
        val messageIds: List<Conversation.Message.Id>,
    ) : ConversationHistoryMutation

    @Serializable
    @SerialName("compact")
    data class Compact(
        val messageIds: List<Conversation.Message.Id>,
        val strategy: SquashType,
    ) : ConversationHistoryMutation
}

@Serializable
enum class ConversationHistoryMutationKind {
    EDIT,
    DELETE,
    COMPACT,
}

/**
 * Exact execution contract for conversation orchestration.
 *
 * Worker-side effects are represented by [ConversationRuntimeTask.Payload.ToolExecution.executionTargetsByCallId];
 * the durable conversation task itself remains Server-owned.
 */
@Serializable
data class ConversationRuntimeTaskRequirements(
    val capabilities: Set<ConversationRuntimeCapability>,
    val target: ConversationRuntimeTaskTarget,
) {
    init {
        require(capabilities.isNotEmpty()) { "Conversation runtime task must require at least one executor capability" }
        if (ConversationRuntimeCapability.LOCAL_AGENT_TOOL in capabilities) {
            require(ConversationRuntimeCapability.TOOL_EXECUTION in capabilities) {
                "Local agent tool capability requires tool execution capability"
            }
        }
    }

    fun isSatisfiedBy(
        executor: ConversationRuntimeExecutorIdentity,
        executorCapabilities: Set<ConversationRuntimeCapability>,
        workerWorkspaceMountIds: Set<WorkspaceMount.Id>,
    ): Boolean =
        executorCapabilities.containsAll(capabilities) &&
            when (val exactTarget = target) {
                ConversationRuntimeTaskTarget.Server ->
                    executor is ConversationRuntimeExecutorIdentity.Server

                is ConversationRuntimeTaskTarget.Worker ->
                    executor is ConversationRuntimeExecutorIdentity.Worker &&
                        exactTarget.workerId == executor.identity.workerId &&
                        (
                            exactTarget.workspaceMountId == null ||
                                exactTarget.workspaceMountId in workerWorkspaceMountIds
                            )
            }
}

@Serializable
@JsonClassDiscriminator("targetKind")
sealed interface ConversationRuntimeTaskTarget {
    @Serializable
    @SerialName("server")
    data object Server : ConversationRuntimeTaskTarget

    @Serializable
    @SerialName("worker")
    data class Worker(
        val workerId: ConversationRuntimeWorkerId,
        val workspaceMountId: WorkspaceMount.Id? = null,
        val requestPolicy: WorkerRequestPolicy? = null,
    ) : ConversationRuntimeTaskTarget
}

@Serializable
enum class ConversationRuntimeCapability {
    CONVERSATION_TURN,
    AI_REQUEST_RESPONSE,
    AUDIO_CAPTURE,
    TOOL_EXECUTION,
    LOCAL_AGENT_TOOL,
    COMPUTER_USE,
    MEMORY_PIPELINE,
}

@Serializable
@JvmInline
value class ConversationRuntimeWorkerId(val value: String) {
    init {
        require(value.isNotBlank()) { "Conversation runtime worker id must not be blank" }
    }
}

@Serializable
@JvmInline
value class ConversationRuntimeWorkerSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Conversation runtime worker session id must not be blank" }
    }
}

@Serializable
data class ConversationRuntimeWorkerIdentity(
    val workerId: ConversationRuntimeWorkerId,
    val sessionId: ConversationRuntimeWorkerSessionId,
)

@Serializable
@JvmInline
value class ConversationRuntimeServerSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Conversation runtime Server session id must not be blank" }
    }
}

@Serializable
@JsonClassDiscriminator("executorKind")
sealed interface ConversationRuntimeExecutorIdentity {
    @Serializable
    @SerialName("server")
    data class Server(
        val sessionId: ConversationRuntimeServerSessionId,
    ) : ConversationRuntimeExecutorIdentity

    @Serializable
    @SerialName("worker")
    data class Worker(
        val identity: ConversationRuntimeWorkerIdentity,
    ) : ConversationRuntimeExecutorIdentity
}

data class ConversationRuntimeExecutorDescriptor(
    val identity: ConversationRuntimeExecutorIdentity,
    val capabilities: Set<ConversationRuntimeCapability>,
) {
    init {
        require(capabilities.isNotEmpty()) { "Conversation runtime executor must declare at least one capability" }
    }
}

@Serializable
data class ConversationRuntimeWorkerDescriptor(
    val id: ConversationRuntimeWorkerId,
    val capabilities: Set<ConversationRuntimeCapability>,
    val tools: List<AiToolDescriptor> = emptyList(),
    val environmentProfile: WorkerEnvironmentProfile,
) {
    init {
        require(capabilities.isNotEmpty()) { "Conversation runtime worker must declare at least one capability" }
        validateWorkerCapabilities(capabilities)
        require(tools.isEmpty() || ConversationRuntimeCapability.TOOL_EXECUTION in capabilities) {
            "A worker advertising tools must declare TOOL_EXECUTION"
        }
        require(tools.all { capabilities.containsAll(it.metadata.requiredRuntimeCapabilities) }) {
            "A worker must declare every capability required by its advertised tools"
        }
        require(tools.map { it.definition.name }.distinct().size == tools.size) {
            "Conversation runtime worker tool names must be unique"
        }
    }
}

@Serializable
data class ConversationRuntimeWorkerRegistration(
    val identity: ConversationRuntimeWorkerIdentity,
    val capabilities: Set<ConversationRuntimeCapability>,
    val tools: List<AiToolDescriptor>,
    val environmentProfile: WorkerEnvironmentProfile,
    val version: String,
    val startedAt: Instant,
    val lastHeartbeatAt: Instant,
    val stoppedAt: Instant? = null,
) {
    init {
        require(capabilities.isNotEmpty()) { "Conversation runtime worker must declare at least one capability" }
        validateWorkerCapabilities(capabilities)
        require(tools.isEmpty() || ConversationRuntimeCapability.TOOL_EXECUTION in capabilities) {
            "A worker advertising tools must declare TOOL_EXECUTION"
        }
        require(tools.all { capabilities.containsAll(it.metadata.requiredRuntimeCapabilities) }) {
            "A worker must declare every capability required by its advertised tools"
        }
        require(tools.map { it.definition.name }.distinct().size == tools.size) {
            "Registered worker tool names must be unique"
        }
        require(version.isNotBlank()) { "Conversation runtime worker version must not be blank" }
        require(lastHeartbeatAt >= startedAt) { "Conversation runtime worker heartbeat cannot precede startup" }
        require(stoppedAt == null || stoppedAt >= startedAt) {
            "Conversation runtime worker stop time cannot precede startup"
        }
    }

    fun isOnline(staleBefore: Instant): Boolean =
        stoppedAt == null && lastHeartbeatAt >= staleBefore
}

interface ConversationRuntimeWorkerRegistry {
    suspend fun register(
        registration: ConversationRuntimeWorkerRegistration,
        staleBefore: Instant,
    ): Boolean

    suspend fun heartbeat(
        identity: ConversationRuntimeWorkerIdentity,
        at: Instant,
    ): Boolean

    suspend fun updateTools(
        identity: ConversationRuntimeWorkerIdentity,
        tools: List<AiToolDescriptor>,
        at: Instant,
    ): Boolean

    suspend fun unregister(
        identity: ConversationRuntimeWorkerIdentity,
        at: Instant,
    ): Boolean

    suspend fun find(workerId: ConversationRuntimeWorkerId): ConversationRuntimeWorkerRegistration?

    suspend fun list(): List<ConversationRuntimeWorkerRegistration>
}

interface ConversationRuntimeWorkerTargetResolver {
    suspend fun requireRegistered(
        workerId: ConversationRuntimeWorkerId,
        capability: ConversationRuntimeCapability,
    ): ConversationRuntimeWorkerIdentity

    suspend fun requireOnline(
        workerId: ConversationRuntimeWorkerId,
        capability: ConversationRuntimeCapability,
    ): ConversationRuntimeWorkerIdentity
}

@Serializable
enum class ConversationRuntimeControlAction {
    PAUSE,
    RESUME,
    STOP,
    INTERRUPT,
}

@Serializable
data class ConversationExecutionState(
    val conversationId: Conversation.Id,
    val controlState: ControlState,
    val activeTaskId: ConversationRuntimeTask.Id?,
    val activeExecutor: ConversationRuntimeExecutorIdentity? = null,
    val activeTaskStartedAt: Instant? = null,
    val updatedAt: Instant,
) {
    init {
        require(activeTaskId != null || activeExecutor == null) {
            "Conversation runtime cannot have an active executor without an active task"
        }
        require(activeTaskId != null || activeTaskStartedAt == null) {
            "Conversation runtime cannot have an execution start without an active task"
        }
        require(activeTaskStartedAt == null || activeExecutor != null) {
            "Conversation runtime cannot start execution without an active executor"
        }
    }

    @Serializable
    enum class ControlState {
        RUNNING,
        PAUSE_REQUESTED,
        PAUSED,
        STOPPING,
        INTERRUPTING,
    }
}

private fun validateWorkerCapabilities(capabilities: Set<ConversationRuntimeCapability>) {
    require(
        capabilities.none {
            it == ConversationRuntimeCapability.CONVERSATION_TURN ||
                it == ConversationRuntimeCapability.MEMORY_PIPELINE
        }
    ) {
        "Conversation orchestration and memory pipeline capabilities belong to Server"
    }
    require(
        ConversationRuntimeCapability.LOCAL_AGENT_TOOL !in capabilities ||
            ConversationRuntimeCapability.TOOL_EXECUTION in capabilities
    ) {
        "LOCAL_AGENT_TOOL requires TOOL_EXECUTION"
    }
    require(
        ConversationRuntimeCapability.COMPUTER_USE !in capabilities ||
            ConversationRuntimeCapability.TOOL_EXECUTION in capabilities
    ) {
        "COMPUTER_USE requires TOOL_EXECUTION"
    }
}

@Serializable
data class ConversationRuntimeToolExecution(
    val toolCallId: ContentItem.ToolCall.Id,
    val toolName: String,
    val status: Status,
    val runtimeTaskId: ConversationRuntimeTask.Id?,
    val executor: ConversationRuntimeExecutorIdentity,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val isError: Boolean? = null,
) {
    @Serializable
    enum class Status {
        RUNNING,
        COMPLETED,
        FAILED,
    }
}

@Serializable
data class ConversationRuntimeMemoryOperation(
    val runId: MemoryRun.Id,
    val operation: String,
    val status: MemoryRun.Status,
    val summary: String,
    val progress: MemoryRun.Progress? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val updatedAt: Instant,
)

@Serializable
data class ConversationRuntimeTaskIncident(
    val task: ConversationRuntimeTask,
    val kind: Kind,
    val message: String,
    val errorType: String? = null,
    val executor: ConversationRuntimeExecutorIdentity?,
    val executionStartedAt: Instant?,
    val occurredAt: Instant,
) {
    init {
        require((kind == Kind.OUTCOME_UNKNOWN) == (executionStartedAt != null)) {
            "Only an execution which crossed its start boundary can have an unknown outcome"
        }
    }

    @Serializable
    enum class Kind {
        DELIVERY_FAILED,
        OUTCOME_UNKNOWN,
    }
}

@Serializable
data class ConversationRuntimeActiveTaskAssignment(
    val conversationId: Conversation.Id,
    val task: ConversationRuntimeTask,
    val executor: ConversationRuntimeExecutorIdentity,
    val startedAt: Instant?,
)

@Serializable
data class ConversationRuntimeTraceEntry(
    val sequence: Long,
    val conversationId: Conversation.Id,
    val taskId: ConversationRuntimeTask.Id?,
    val executor: ConversationRuntimeExecutorIdentity?,
    val kind: Kind,
    val status: Status,
    val message: String? = null,
    val createdAt: Instant,
) {
    @Serializable
    enum class Kind {
        TASK_SUBMITTED,
        TASK_CLAIMED,
        TASK_STARTED,
        TASK_IN_DOUBT,
        TASK_COMPLETED,
        TASK_FAILED,
        TASK_CANCELLED,
        CONTROL_REQUESTED,
        TOOL_EXECUTION,
        COMMAND_TASK,
        COMMAND_MONITOR,
        EVENT_PUBLISHED,
    }

    @Serializable
    enum class Status {
        STARTED,
        UPDATED,
        COMPLETED,
        FAILED,
        CANCELLED,
    }
}

/**
 * Backend-owned read model for clients.
 *
 * Clients may filter or format this state, but should not reconstruct runtime truth from local UI events.
 */
@Serializable
data class ConversationRuntimeSnapshot(
    val revision: Long,
    val conversationId: Conversation.Id,
    val state: ConversationExecutionState?,
    val activeTask: ConversationRuntimeTask? = null,
    val activeInsertions: List<ConversationRuntimeTask> = emptyList(),
    val continuationTask: ConversationRuntimeTask? = null,
    val pendingTasks: List<ConversationRuntimeTask>,
    val toolExecutions: List<ConversationRuntimeToolExecution> = emptyList(),
    val memoryOperations: List<ConversationRuntimeMemoryOperation> = emptyList(),
    val commandTasks: List<CommandTask> = emptyList(),
    val commandMonitors: List<CommandMonitor> = emptyList(),
    val incidents: List<ConversationRuntimeTaskIncident> = emptyList(),
    val trace: List<ConversationRuntimeTraceEntry> = emptyList(),
    val lastEventSequence: Long = 0,
)

@Serializable
data class ConversationRuntimeWorkItem(
    val conversationId: Conversation.Id,
    val reason: Reason,
    val taskId: ConversationRuntimeTask.Id,
    val requirements: ConversationRuntimeTaskRequirements,
    val createdAt: Instant,
) {
    @Serializable
    enum class Reason {
        TASK_SUBMITTED,
    }
}

sealed interface ConversationRuntimeSchedulingSignal {
    data object ListenerReady : ConversationRuntimeSchedulingSignal

    data class Changed(
        val conversationId: Conversation.Id,
    ) : ConversationRuntimeSchedulingSignal
}

@Serializable
sealed interface ConversationRuntimeEvent {
    val conversationId: Conversation.Id
    val cursorSequence: Long?

    @Serializable
    data class SnapshotUpdated(
        override val conversationId: Conversation.Id,
        val snapshot: ConversationRuntimeSnapshot,
        override val cursorSequence: Long? = snapshot.lastEventSequence,
    ) : ConversationRuntimeEvent

    @Serializable
    data class ReplayCompleted(
        override val conversationId: Conversation.Id,
        override val cursorSequence: Long?,
    ) : ConversationRuntimeEvent

    @Serializable
    data class MessageEmitted(
        override val conversationId: Conversation.Id,
        val taskId: ConversationRuntimeTask.Id?,
        val message: Conversation.Message,
        override val cursorSequence: Long? = null,
    ) : ConversationRuntimeEvent

    @Serializable
    data class HistoryChanged(
        override val conversationId: Conversation.Id,
        val taskId: ConversationRuntimeTask.Id,
        val kind: ConversationHistoryMutationKind,
        override val cursorSequence: Long? = null,
    ) : ConversationRuntimeEvent

    @Serializable
    data class ExecutionCompleted(
        override val conversationId: Conversation.Id,
        val shouldNotifyUser: Boolean = true,
        override val cursorSequence: Long? = null,
    ) : ConversationRuntimeEvent

    @Serializable
    data class ExecutionFailed(
        override val conversationId: Conversation.Id,
        val message: String,
        val failureType: String? = null,
        override val cursorSequence: Long? = null,
    ) : ConversationRuntimeEvent
}

interface ConversationRuntimeEventSubscription {
    val events: Flow<ConversationRuntimeEvent>

    suspend fun close()
}

interface ConversationRuntimeEventBus {
    suspend fun subscribe(conversationId: Conversation.Id): ConversationRuntimeEventSubscription

    suspend fun publish(event: ConversationRuntimeEvent)
}

@Serializable
data class ConversationRuntimeEventLogEntry(
    val sequence: Long,
    val conversationId: Conversation.Id,
    val event: ConversationRuntimeEvent,
    val createdAt: Instant,
)

interface ConversationRuntimeCoordinator {
    /**
     * Local wakeups for scheduling-state changes. PostgreSQL remains the durable source of truth.
     *
     * A Server executor must drain [listReadyWorkItems] at startup before relying on these signals.
     */
    val schedulingSignals: Flow<ConversationRuntimeSchedulingSignal>

    suspend fun submit(task: ConversationRuntimeTask): Boolean

    suspend fun updatePendingMessageSubmission(task: ConversationRuntimeTask): Boolean

    /**
     * Atomically assigns a pending task to one executor session.
     *
     * The assignment has no expiry and cannot move to another session. Repeating the claim is idempotent only for
     * the exact same [executor].
     */
    suspend fun claimDeliveredTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        executorCapabilities: Set<ConversationRuntimeCapability>,
        workerWorkspaceMountIds: Set<WorkspaceMount.Id>,
    ): ConversationRuntimeTask?

    suspend fun completeActiveTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        outcome: ConversationRuntimeTaskOutcome,
    ): Boolean

    /**
     * Records the exact boundary immediately before task code may run.
     */
    suspend fun markActiveTaskStarted(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        startedAt: Instant,
    ): Boolean

    suspend fun confirmActiveTaskOwner(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
    ): Boolean

    /**
     * Permanently closes a claimed task whose result cannot be proven.
     *
     * This never requeues or re-executes the source task. It records an incident and schedules incident handling so
     * the user or the main model can decide what to do next.
     */
    suspend fun markActiveTaskInDoubt(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        message: String,
        errorType: String? = null,
    ): ConversationRuntimeTaskIncident?

    /**
     * Permanently closes a claimed task which is known not to have crossed its execution-start boundary.
     */
    suspend fun recordClaimedTaskDeliveryFailure(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        message: String,
        errorType: String? = null,
    ): ConversationRuntimeTaskIncident?

    /**
     * Permanently closes a task that could not reach its executor before any durable claim was created.
     */
    suspend fun recordPendingTaskDeliveryFailure(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        message: String,
        errorType: String? = null,
    ): ConversationRuntimeTaskIncident?

    suspend fun listActiveTaskAssignments(): List<ConversationRuntimeActiveTaskAssignment>

    suspend fun findTaskIncident(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
    ): ConversationRuntimeTaskIncident?

    suspend fun finishIfIdle(conversationId: Conversation.Id): Boolean

    suspend fun upsertToolExecution(
        conversationId: Conversation.Id,
        execution: ConversationRuntimeToolExecution,
    ): Boolean

    suspend fun clearToolExecutions(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
    ): Boolean

    suspend fun upsertMemoryOperation(
        conversationId: Conversation.Id,
        operation: ConversationRuntimeMemoryOperation,
    ): Boolean

    suspend fun upsertCommandTask(task: CommandTask): CommandTaskUpsertResult

    suspend fun findCommandTasks(): List<CommandTask>

    suspend fun findCommandTasks(conversationId: Conversation.Id): List<CommandTask>

    suspend fun findCommandTask(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): CommandTask?

    suspend fun requestCommandTaskCancellation(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
        requestedAt: Instant,
    ): Boolean

    suspend fun requestCommandTaskCancellations(
        conversationId: Conversation.Id,
        requestedAt: Instant,
    ): Int

    suspend fun synchronizeCommandMonitor(
        monitor: CommandMonitor,
        events: List<CommandMonitorEvent> = emptyList(),
    ): CommandMonitorSyncResult

    suspend fun findCommandMonitors(): List<CommandMonitor>

    suspend fun findCommandMonitors(conversationId: Conversation.Id): List<CommandMonitor>

    suspend fun findCommandMonitor(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
    ): CommandMonitor?

    suspend fun findCommandMonitorEvents(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id? = null,
    ): List<CommandMonitorEvent>

    suspend fun markCommandMonitorEventsDelivered(
        conversationId: Conversation.Id,
        eventIds: Set<CommandMonitorEvent.Id>,
        deliveredAt: Instant,
    ): Boolean

    suspend fun markCommandMonitorTerminalNotificationDelivered(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
        deliveredAt: Instant,
    ): Boolean

    suspend fun requestCommandMonitorCancellation(
        conversationId: Conversation.Id,
        monitorId: CommandMonitor.Id,
        requestedAt: Instant,
    ): Boolean

    suspend fun requestPause(conversationId: Conversation.Id): Boolean
    suspend fun markPaused(conversationId: Conversation.Id): Boolean
    suspend fun requestResume(conversationId: Conversation.Id): Boolean
    suspend fun requestStop(conversationId: Conversation.Id): Boolean
    suspend fun requestInterrupt(conversationId: Conversation.Id): Boolean
    suspend fun abort(conversationId: Conversation.Id)
    suspend fun find(conversationId: Conversation.Id): ConversationExecutionState?

    suspend fun cancelByMessageId(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean

    suspend fun claimActiveInsertions(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        placement: QueuedMessagePlacement,
    ): List<ConversationRuntimeTask>

    suspend fun listPending(conversationId: Conversation.Id): List<ConversationRuntimeTask>

    suspend fun snapshot(conversationId: Conversation.Id): ConversationRuntimeSnapshot

    suspend fun recordEvent(event: ConversationRuntimeEvent): ConversationRuntimeEventLogEntry

    suspend fun listEventLogEntries(
        conversationId: Conversation.Id,
        afterSequence: Long?,
        limit: Int,
    ): List<ConversationRuntimeEventLogEntry>

    /**
     * Reads only indexed conversations whose next end-of-turn task is runnable.
     */
    suspend fun listReadyWorkItems(limit: Int): List<ConversationRuntimeWorkItem>
}
