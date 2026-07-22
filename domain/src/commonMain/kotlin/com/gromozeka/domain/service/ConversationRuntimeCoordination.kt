package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
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
    val requirements: ConversationRuntimeTaskRequirements,
    val createdAt: Instant,
) {
    init {
        require(id.value.isNotBlank()) { "Conversation runtime task id must not be blank" }
        require(idempotencyKey.isNotBlank()) { "Conversation runtime task idempotency key must not be blank" }
        require(requirements.capabilities.containsAll(payload.requiredCapabilities())) {
            "Conversation runtime task ${id.value} requirements do not satisfy ${payload::class.simpleName}"
        }
        if (payload is Payload.UserTurn) {
            require(payload.userMessage.conversationId == conversationId) {
                "Conversation runtime task ${id.value} user message belongs to another conversation"
            }
        }
        if (payload is Payload.ToolExecution) {
            require(requirements.target != null) {
                "Conversation tool execution task ${id.value} requires an exact worker target"
            }
        }
    }

    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    sealed interface Payload {
        @Serializable
        @SerialName("user_turn")
        data class UserTurn(
            val userMessage: Conversation.Message,
            val agentDefinitionId: AgentDefinition.Id,
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
        ) : Payload {
            init {
                require(iteration >= 1) { "Conversation tool execution iteration must be positive" }
                require(toolCalls.isNotEmpty()) { "Conversation tool execution task must contain at least one tool call" }
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
        @SerialName("execution_incident")
        data class ExecutionIncident(
            val sourceTaskId: Id,
        ) : Payload
    }

    fun requireUserTurn(): Payload.UserTurn =
        payload as? Payload.UserTurn
            ?: error("Conversation runtime task ${id.value} is not a user-turn task: ${payload::class.simpleName}")

    fun userTurnOrNull(): Payload.UserTurn? = payload as? Payload.UserTurn

    fun userMessageIdOrNull(): Conversation.Message.Id? = userTurnOrNull()?.userMessage?.id

    fun isInternalRuntimeStep(): Boolean = payload !is Payload.UserTurn

    private fun Payload.requiredCapabilities(): Set<ConversationRuntimeWorkerCapability> =
        when (this) {
            is Payload.UserTurn -> setOf(
                ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
            )
            is Payload.LlmCall -> setOf(
                ConversationRuntimeWorkerCapability.LLM_RUNTIME,
                ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
            )
            is Payload.ToolExecution -> setOf(ConversationRuntimeWorkerCapability.TOOL_EXECUTION)
            is Payload.ToolResultProcessing -> setOf(
                ConversationRuntimeWorkerCapability.CONVERSATION_TURN,
                ConversationRuntimeWorkerCapability.MEMORY_PIPELINE,
            )
            is Payload.MemoryRecall -> setOf(ConversationRuntimeWorkerCapability.MEMORY_PIPELINE)
            is Payload.ExecutionIncident -> setOf(ConversationRuntimeWorkerCapability.CONVERSATION_TURN)
        }
}

/**
 * Worker selection contract for distributed runtime implementations.
 *
 * `capabilities` says what the worker must be able to do.
 * `target` pins execution to one worker and, when needed, one mounted workspace.
 */
@Serializable
data class ConversationRuntimeTaskRequirements(
    val capabilities: Set<ConversationRuntimeWorkerCapability>,
    val target: ConversationRuntimeTaskTarget? = null,
) {
    init {
        require(capabilities.isNotEmpty()) { "Conversation runtime task must require at least one worker capability" }
        if (ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL in capabilities) {
            require(ConversationRuntimeWorkerCapability.TOOL_EXECUTION in capabilities) {
                "Local agent tool capability requires tool execution capability"
            }
        }
    }

    fun isSatisfiedBy(
        workerId: ConversationRuntimeWorkerId,
        workerCapabilities: Set<ConversationRuntimeWorkerCapability>,
        workerWorkspaceMountIds: Set<WorkspaceMount.Id>,
    ): Boolean =
        workerCapabilities.containsAll(capabilities) &&
            (target == null || (
                target.workerId == workerId &&
                    (target.workspaceMountId == null || target.workspaceMountId in workerWorkspaceMountIds)
                ))
}

@Serializable
data class ConversationRuntimeTaskTarget(
    val workerId: ConversationRuntimeWorkerId,
    val workspaceMountId: WorkspaceMount.Id? = null,
)

@Serializable
enum class ConversationRuntimeWorkerCapability {
    CONVERSATION_TURN,
    LLM_RUNTIME,
    TOOL_EXECUTION,
    LOCAL_AGENT_TOOL,
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
data class ConversationRuntimeWorkerDescriptor(
    val id: ConversationRuntimeWorkerId,
    val capabilities: Set<ConversationRuntimeWorkerCapability>,
    val tools: List<AiToolDescriptor> = emptyList(),
) {
    init {
        require(capabilities.isNotEmpty()) { "Conversation runtime worker must declare at least one capability" }
        require(tools.isEmpty() || ConversationRuntimeWorkerCapability.TOOL_EXECUTION in capabilities) {
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
    val capabilities: Set<ConversationRuntimeWorkerCapability>,
    val tools: List<AiToolDescriptor>,
    val version: String,
    val startedAt: Instant,
    val lastHeartbeatAt: Instant,
    val stoppedAt: Instant? = null,
) {
    init {
        require(capabilities.isNotEmpty()) { "Conversation runtime worker must declare at least one capability" }
        require(tools.isEmpty() || ConversationRuntimeWorkerCapability.TOOL_EXECUTION in capabilities) {
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

    suspend fun unregister(
        identity: ConversationRuntimeWorkerIdentity,
        at: Instant,
    ): Boolean

    suspend fun find(workerId: ConversationRuntimeWorkerId): ConversationRuntimeWorkerRegistration?

    suspend fun list(): List<ConversationRuntimeWorkerRegistration>
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
    val activeWorker: ConversationRuntimeWorkerIdentity? = null,
    val activeTaskStartedAt: Instant? = null,
    val updatedAt: Instant,
) {
    init {
        require(activeTaskId != null || activeWorker == null) {
            "Conversation runtime cannot have an active worker without an active task"
        }
        require(activeTaskId != null || activeTaskStartedAt == null) {
            "Conversation runtime cannot have an execution start without an active task"
        }
        require(activeTaskStartedAt == null || activeWorker != null) {
            "Conversation runtime cannot start execution without an active worker"
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

@Serializable
data class ConversationRuntimeToolExecution(
    val toolCallId: ContentItem.ToolCall.Id,
    val toolName: String,
    val status: Status,
    val runtimeTaskId: ConversationRuntimeTask.Id?,
    val worker: ConversationRuntimeWorkerIdentity? = null,
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
data class ConversationRuntimeTaskIncident(
    val task: ConversationRuntimeTask,
    val kind: Kind,
    val message: String,
    val errorType: String? = null,
    val worker: ConversationRuntimeWorkerIdentity?,
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
    val worker: ConversationRuntimeWorkerIdentity,
    val startedAt: Instant?,
)

@Serializable
data class ConversationRuntimeTraceEntry(
    val sequence: Long,
    val conversationId: Conversation.Id,
    val taskId: ConversationRuntimeTask.Id?,
    val worker: ConversationRuntimeWorkerIdentity?,
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

@Serializable
data class ConversationRuntimeWorkOutboxEntry(
    val sequence: Long,
    val item: ConversationRuntimeWorkItem,
    val createdAt: Instant,
    val publishedAt: Instant? = null,
    val publishLeaseOwnerId: String? = null,
    val publishLeaseUntil: Instant? = null,
)

interface ConversationRuntimeWorkDelivery {
    val item: ConversationRuntimeWorkItem
    val redeliveryCount: Int
    val isFinalRedelivery: Boolean

    /**
     * Permanently settles the broker delivery. Returns only after the transport accepted the acknowledgement.
     */
    suspend fun acknowledge()

    /**
     * Redelivers transport work only while its runtime task is still unclaimed.
     */
    suspend fun redeliver()

    /**
     * Permanently rejects an undeliverable transport item.
     */
    suspend fun reject()
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

interface ConversationRuntimeWorkPublisher {
    suspend fun submit(item: ConversationRuntimeWorkItem)
}

interface ConversationRuntimeWorkConsumer {
    val deliveries: Flow<ConversationRuntimeWorkDelivery>
}

@Serializable
data class ConversationRuntimeEventLogEntry(
    val sequence: Long,
    val conversationId: Conversation.Id,
    val event: ConversationRuntimeEvent,
    val createdAt: Instant,
    val publishedAt: Instant? = null,
    val publishLeaseOwnerId: String? = null,
    val publishLeaseUntil: Instant? = null,
)

interface ConversationRuntimeCoordinator {
    suspend fun submit(task: ConversationRuntimeTask): Boolean

    /**
     * Atomically assigns a pending task to one worker session.
     *
     * The assignment has no expiry and cannot move to another session. Repeating the claim is idempotent only for
     * the exact same [worker].
     */
    suspend fun claimDeliveredTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
        workerCapabilities: Set<ConversationRuntimeWorkerCapability>,
        workerWorkspaceMountIds: Set<WorkspaceMount.Id>,
    ): ConversationRuntimeTask?

    suspend fun completeActiveTask(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
    ): Boolean

    /**
     * Records the exact boundary after broker acknowledgement and immediately before task code may run.
     */
    suspend fun markActiveTaskStarted(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
        startedAt: Instant,
    ): Boolean

    suspend fun confirmActiveTaskOwner(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
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
        worker: ConversationRuntimeWorkerIdentity,
        message: String,
        errorType: String? = null,
    ): ConversationRuntimeTaskIncident?

    /**
     * Permanently closes a claimed task which is known not to have crossed its execution-start boundary.
     */
    suspend fun recordClaimedTaskDeliveryFailure(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
        message: String,
        errorType: String? = null,
    ): ConversationRuntimeTaskIncident?

    /**
     * Permanently closes a task that could not reach a worker before any durable claim was created.
     */
    suspend fun recordPendingTaskDeliveryFailure(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
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
        worker: ConversationRuntimeWorkerIdentity,
    ): Boolean

    suspend fun upsertCommandTask(task: CommandTask): CommandTaskUpsertResult

    suspend fun findCommandTasks(): List<CommandTask>

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

    suspend fun takeActiveInsertions(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
        worker: ConversationRuntimeWorkerIdentity,
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
        leaseOwnerId: String,
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<ConversationRuntimeEventLogEntry>

    suspend fun markEventLogEntryPublished(
        conversationId: Conversation.Id,
        sequence: Long,
        leaseOwnerId: String,
        publishedAt: Instant,
    ): Boolean

    suspend fun claimUnpublishedWorkItems(
        leaseOwnerId: String,
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<ConversationRuntimeWorkOutboxEntry>

    suspend fun markWorkItemPublished(
        conversationId: Conversation.Id,
        sequence: Long,
        leaseOwnerId: String,
        publishedAt: Instant,
    ): Boolean

    suspend fun releasePublishedWorkItem(
        conversationId: Conversation.Id,
        taskId: ConversationRuntimeTask.Id,
    ): Boolean
}
