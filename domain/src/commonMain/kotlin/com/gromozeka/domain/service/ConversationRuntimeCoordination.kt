package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Durable conversation runtime task.
 *
 * A task is the public boundary between UI commands, runtime scheduling, and workers.
 * Storage/queue implementations may keep it in memory, DB, RabbitMQ, or another broker,
 * but downstream code must treat it as an independently claimable unit of work.
 */
@Serializable
data class ConversationRuntimeTask(
    val id: Id,
    val conversationId: Conversation.Id,
    val payload: Payload,
    val placement: QueuedMessagePlacement,
    val idempotencyKey: String,
    val requirements: ConversationRuntimeTaskRequirements = ConversationRuntimeTaskRequirements(
        capabilities = setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN),
    ),
    val createdAt: Instant,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    sealed interface Payload {
        @Serializable
        @SerialName("user_turn")
        data class UserTurn(
            val userMessage: Conversation.Message,
            val agent: AgentDefinition,
        ) : Payload

        @Serializable
        @SerialName("llm_call")
        data class LlmCall(
            val rootUserMessageId: Conversation.Message.Id,
            val agent: AgentDefinition,
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
            val agent: AgentDefinition,
            val iteration: Int,
            val toolCalls: List<ContentItem.ToolCall>,
        ) : Payload {
            init {
                require(iteration >= 1) { "Conversation tool execution iteration must be positive" }
                require(toolCalls.isNotEmpty()) { "Conversation tool execution task must contain at least one tool call" }
            }
        }

        @Serializable
        @SerialName("memory_recall")
        data class MemoryRecall(
            val rootUserMessageId: Conversation.Message.Id,
            val targetMessageId: Conversation.Message.Id,
            val agent: AgentDefinition,
            val followUpIteration: Int,
        ) : Payload {
            init {
                require(followUpIteration >= 1) { "Conversation memory recall follow-up iteration must be positive" }
            }
        }
    }

    fun requireUserTurn(): Payload.UserTurn =
        payload as? Payload.UserTurn
            ?: error("Conversation runtime task ${id.value} is not a user-turn task: ${payload::class.simpleName}")

    fun userTurnOrNull(): Payload.UserTurn? = payload as? Payload.UserTurn

    fun userMessageIdOrNull(): Conversation.Message.Id? = userTurnOrNull()?.userMessage?.id

    fun isInternalRuntimeStep(): Boolean = payload !is Payload.UserTurn
}

/**
 * Worker selection contract for distributed runtime implementations.
 *
 * `capabilities` says what the worker must be able to do.
 * `affinity` narrows execution to a machine/project/workspace when local state matters.
 */
@Serializable
data class ConversationRuntimeTaskRequirements(
    val capabilities: Set<ConversationRuntimeWorkerCapability>,
    val affinity: ConversationRuntimeWorkerAffinity? = null,
) {
    init {
        require(capabilities.isNotEmpty()) { "Conversation runtime task must require at least one worker capability" }
    }

    fun isSatisfiedBy(
        workerCapabilities: Set<ConversationRuntimeWorkerCapability>,
        workerAffinities: Set<ConversationRuntimeWorkerAffinity>,
    ): Boolean =
        workerCapabilities.containsAll(capabilities) &&
            (affinity == null || affinity in workerAffinities)
}

@Serializable
enum class ConversationRuntimeWorkerCapability {
    CONVERSATION_TURN,
    LLM_RUNTIME,
    TOOL_EXECUTION,
    LOCAL_AGENT_TOOL,
    MEMORY_PIPELINE,
}

val DefaultConversationRuntimeWorkerCapabilities: Set<ConversationRuntimeWorkerCapability> = setOf(
    ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
    ConversationRuntimeWorkerCapability.LLM_RUNTIME,
    ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
    ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
    ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
)

@Serializable
data class ConversationRuntimeWorkerAffinity(
    val kind: Kind,
    val value: String,
) {
    @Serializable
    enum class Kind {
        MACHINE,
        PROJECT,
        WORKSPACE,
        USER,
    }
}

@Serializable
data class ConversationRuntimeWorkerDescriptor(
    val id: String? = null,
    val capabilities: Set<ConversationRuntimeWorkerCapability> = DefaultConversationRuntimeWorkerCapabilities,
    val affinities: Set<ConversationRuntimeWorkerAffinity> = emptySet(),
)

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
    val activeWorkerId: String? = null,
    val updatedAt: Instant,
) {
    @Serializable
    enum class ControlState {
        RUNNING,
        PAUSE_REQUESTED,
        PAUSED,
        STOPPING,
        INTERRUPTING,
    }
}

@Serializable
data class ConversationRuntimeToolExecution(
    val toolCallId: ContentItem.ToolCall.Id,
    val toolName: String,
    val status: Status,
    val runtimeTaskId: ConversationRuntimeTask.Id?,
    val workerId: String? = null,
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
data class ConversationRuntimeTaskFailure(
    val task: ConversationRuntimeTask,
    val reason: Reason,
    val message: String,
    val workerId: String?,
    val failedAt: Instant,
) {
    @Serializable
    enum class Reason {
        EXECUTION_FAILED,
        DELIVERY_FAILED,
    }
}

@Serializable
data class ConversationRuntimeTraceEntry(
    val sequence: Long,
    val conversationId: Conversation.Id,
    val taskId: ConversationRuntimeTask.Id?,
    val workerId: String?,
    val kind: Kind,
    val status: Status,
    val message: String? = null,
    val createdAt: Instant,
) {
    @Serializable
    enum class Kind {
        TASK_SUBMITTED,
        TASK_CLAIMED,
        TASK_COMPLETED,
        TASK_FAILED,
        TASK_CANCELLED,
        CONTROL_REQUESTED,
        TOOL_EXECUTION,
        COMMAND_TASK,
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
    val pendingTasks: List<ConversationRuntimeTask>,
    val toolExecutions: List<ConversationRuntimeToolExecution> = emptyList(),
    val commandTasks: List<CommandTask> = emptyList(),
    val failedTasks: List<ConversationRuntimeTaskFailure> = emptyList(),
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

@Serializable
data class ConversationRuntimeWorkOutboxEntry(
    val sequence: Long,
    val item: ConversationRuntimeWorkItem,
    val createdAt: Instant,
    val publishedAt: Instant? = null,
    val publishWorkerId: String? = null,
    val publishLeaseUntil: Instant? = null,
)

interface ConversationRuntimeWorkDelivery {
    val item: ConversationRuntimeWorkItem
    val retryCount: Int
    val isFinalRetry: Boolean

    suspend fun ack()

    suspend fun retry()

    suspend fun fail()
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
    data class MessageEmitted(
        override val conversationId: Conversation.Id,
        val taskId: ConversationRuntimeTask.Id?,
        val message: Conversation.Message,
        override val cursorSequence: Long? = null,
    ) : ConversationRuntimeEvent

    @Serializable
    data class ExecutionCompleted(
        override val conversationId: Conversation.Id,
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

interface ConversationRuntimeWorkQueue {
    val deliveries: Flow<ConversationRuntimeWorkDelivery>

    suspend fun submit(item: ConversationRuntimeWorkItem)
}

@Serializable
data class ConversationRuntimeEventLogEntry(
    val sequence: Long,
    val conversationId: Conversation.Id,
    val event: ConversationRuntimeEvent,
    val createdAt: Instant,
    val publishedAt: Instant? = null,
    val publishWorkerId: String? = null,
    val publishLeaseUntil: Instant? = null,
)

interface ConversationRuntimeCoordinator {
    suspend fun submit(task: ConversationRuntimeTask): Boolean

    suspend fun claimDeliveredTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
        workerCapabilities: Set<ConversationRuntimeWorkerCapability> = setOf(
            ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
        ),
        workerAffinities: Set<ConversationRuntimeWorkerAffinity> = emptySet(),
    ): ConversationRuntimeTask?

    suspend fun completeActiveTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
    ): Boolean

    suspend fun confirmActiveTaskOwner(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
    ): Boolean

    suspend fun failActiveTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
        message: String,
        type: String?,
    ): Boolean

    suspend fun failPendingTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
        message: String,
        type: String?,
    ): Boolean

    suspend fun finishIfIdle(conversationId: Conversation.Id): Boolean

    suspend fun upsertToolExecution(
        conversationId: Conversation.Id,
        execution: ConversationRuntimeToolExecution,
    ): Boolean

    suspend fun clearToolExecutions(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
    ): Boolean

    suspend fun upsertCommandTask(task: CommandTask): Boolean

    suspend fun findCommandTask(
        conversationId: Conversation.Id,
        taskId: CommandTask.Id,
    ): CommandTask?

    suspend fun requestPause(conversationId: Conversation.Id): Boolean
    suspend fun markPaused(conversationId: Conversation.Id): Boolean
    suspend fun requestResume(conversationId: Conversation.Id): Boolean
    suspend fun requestStop(conversationId: Conversation.Id): Boolean
    suspend fun requestInterrupt(conversationId: Conversation.Id): Boolean
    suspend fun fail(conversationId: Conversation.Id)
    suspend fun abort(conversationId: Conversation.Id)
    suspend fun find(conversationId: Conversation.Id): ConversationExecutionState?

    suspend fun cancelByMessageId(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
    ): Boolean

    suspend fun takeActiveInsertions(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        workerId: String,
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

    suspend fun claimUnpublishedEventLogEntries(
        workerId: String,
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<ConversationRuntimeEventLogEntry>

    suspend fun markEventLogEntryPublished(
        conversationId: Conversation.Id,
        sequence: Long,
        workerId: String,
        publishedAt: Instant,
    ): Boolean

    suspend fun claimUnpublishedWorkItems(
        workerId: String,
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<ConversationRuntimeWorkOutboxEntry>

    suspend fun markWorkItemPublished(
        conversationId: Conversation.Id,
        sequence: Long,
        workerId: String,
        publishedAt: Instant,
    ): Boolean

    suspend fun releasePublishedWorkItem(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
    ): Boolean
}
