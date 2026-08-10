package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.WorkspaceMount
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ConversationRuntimeSchedulingState(
    val conversationId: Conversation.Id,
    val executionState: ConversationExecutionState? = null,
    val activeTask: ConversationRuntimeTask? = null,
    val activeInsertions: List<ConversationRuntimeTask> = emptyList(),
    val continuationTask: ConversationRuntimeTask? = null,
    val pendingTasks: List<ConversationRuntimeTask> = emptyList(),
    val pendingTurnTerminationInstructions: List<Conversation.Message.Instruction.PreviousTurnTerminated> = emptyList(),
    val incidents: List<ConversationRuntimeTaskIncident> = emptyList(),
    val completedIdempotencyKeys: Set<String> = emptySet(),
) {
    init {
        require(executionState == null || executionState.conversationId == conversationId) {
            "Conversation runtime execution state belongs to another conversation"
        }
        require((activeTask == null) == (executionState?.activeTaskId == null)) {
            "Conversation runtime active task and execution state must agree"
        }
        require(activeTask == null || executionState?.activeTaskId == activeTask.id) {
            "Conversation runtime active task id does not match execution state"
        }
        require(activeInsertions.isEmpty() || activeTask != null) {
            "Conversation runtime insertions require an active task"
        }
        require(continuationTask == null || activeTask == null) {
            "Conversation runtime cannot hold an active task and a continuation"
        }
        require(continuationTask == null || continuationTask.isContinuation()) {
            "Conversation runtime continuation slot accepts continuation tasks only"
        }
        require(pendingTasks.all(ConversationRuntimeTask::isRootInput)) {
            "Conversation runtime pending queue accepts root inputs only"
        }
        require(activeInsertions.all(ConversationRuntimeTask::isRootInput)) {
            "Conversation runtime insertion reservation accepts root inputs only"
        }
        require(
            pendingTurnTerminationInstructions.map { it.turnId }.distinct().size ==
                pendingTurnTerminationInstructions.size
        ) {
            "Conversation runtime can keep only one pending termination instruction per turn"
        }

        val scheduledTasks = listOfNotNull(activeTask, continuationTask) + activeInsertions + pendingTasks
        require(scheduledTasks.all { it.conversationId == conversationId }) {
            "Conversation runtime tasks belong to another conversation"
        }
        require(scheduledTasks.map { it.id }.distinct().size == scheduledTasks.size) {
            "Conversation runtime task ids must be unique within a conversation"
        }
        require(scheduledTasks.map { it.idempotencyKey }.distinct().size == scheduledTasks.size) {
            "Conversation runtime idempotency keys must be unique within a conversation"
        }
        require(
            activeTask != null ||
                continuationTask != null ||
                pendingTasks.none { it.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT }
        ) {
            "Conversation runtime safe-point input cannot remain without an active turn"
        }
    }

    fun submit(
        task: ConversationRuntimeTask,
        now: Instant,
    ): ConversationRuntimeStateTransition<Boolean> {
        require(task.conversationId == conversationId) {
            "Conversation runtime task belongs to another conversation"
        }
        require(task.isRootInput()) {
            "Conversation runtime continuations must be installed by completing their parent task"
        }
        if (executionState?.controlState == ConversationExecutionState.ControlState.STOPPING ||
            executionState?.controlState == ConversationExecutionState.ControlState.INTERRUPTING
        ) {
            return unchanged(false)
        }
        if (task.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT && activeTask == null) {
            return unchanged(false)
        }

        val scheduledTasks = listOfNotNull(activeTask, continuationTask) + activeInsertions + pendingTasks
        val userMessageId = task.userMessageIdOrNull()
        if (task.idempotencyKey in completedIdempotencyKeys ||
            scheduledTasks.any {
                it.idempotencyKey == task.idempotencyKey ||
                    (userMessageId != null && it.userMessageIdOrNull() == userMessageId)
            }
        ) {
            return unchanged(false)
        }

        val submittedTask = task.withTurnTerminationInstructions(pendingTurnTerminationInstructions)
        return changed(
            copy(
                executionState = executionState ?: idleRunningState(now),
                pendingTasks = pendingTasks + submittedTask,
                pendingTurnTerminationInstructions = if (task.payload is ConversationRuntimeTask.Payload.UserTurn) {
                    emptyList()
                } else {
                    pendingTurnTerminationInstructions
                },
            ),
            true,
        )
    }

    fun updatePendingUserTurn(task: ConversationRuntimeTask): ConversationRuntimeStateTransition<Boolean> {
        require(task.conversationId == conversationId) {
            "Conversation runtime task belongs to another conversation"
        }
        require(task.payload is ConversationRuntimeTask.Payload.UserTurn && task.isRootInput()) {
            "Only a pending root user turn can be updated"
        }
        if (executionState?.controlState == ConversationExecutionState.ControlState.STOPPING ||
            executionState?.controlState == ConversationExecutionState.ControlState.INTERRUPTING ||
            (task.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT && activeTask == null)
        ) {
            return unchanged(false)
        }
        val index = pendingTasks.indexOfFirst { it.userMessageIdOrNull() == task.userMessageIdOrNull() }
        if (index < 0) return unchanged(false)

        val existing = pendingTasks[index]
        require(existing.id == task.id &&
            existing.idempotencyKey == task.idempotencyKey &&
            existing.turnId == task.turnId
        ) {
            "Updating a queued user turn cannot change its runtime identity"
        }
        val updatedTask = task.withTurnTerminationInstructions(existing.turnTerminationInstructions())
        return changed(
            copy(pendingTasks = pendingTasks.toMutableList().apply { this[index] = updatedTask }),
            true,
        )
    }

    fun claim(
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        executorCapabilities: Set<ConversationRuntimeCapability>,
        workerWorkspaceMountIds: Set<WorkspaceMount.Id>,
        now: Instant,
    ): ConversationRuntimeStateTransition<ConversationRuntimeTask?> {
        val currentState = executionState
        if (currentState?.activeTaskId == taskId) {
            val currentTask = activeTask ?: return unchanged(null)
            if (currentState.activeExecutor != executor ||
                currentState.activeTaskStartedAt != null ||
                currentState.controlState == ConversationExecutionState.ControlState.STOPPING ||
                currentState.controlState == ConversationExecutionState.ControlState.INTERRUPTING ||
                !currentTask.requirements.isSatisfiedBy(
                    executor,
                    executorCapabilities,
                    workerWorkspaceMountIds,
                )
            ) {
                return unchanged(null)
            }
            return unchanged(currentTask)
        }
        if (currentState != null &&
            (currentState.controlState != ConversationExecutionState.ControlState.RUNNING ||
                currentState.activeTaskId != null)
        ) {
            return unchanged(null)
        }

        val pendingIndex = pendingTasks.indexOfFirst {
            it.placement == QueuedMessagePlacement.END_OF_TURN
        }
        val task = when {
            continuationTask?.id == taskId -> continuationTask
            pendingIndex >= 0 && pendingTasks[pendingIndex].id == taskId -> pendingTasks[pendingIndex]
            else -> return unchanged(null)
        }
        if (!task.requirements.isSatisfiedBy(executor, executorCapabilities, workerWorkspaceMountIds)) {
            return unchanged(null)
        }

        return changed(
            copy(
                executionState = (currentState ?: idleRunningState(now)).copy(
                    controlState = ConversationExecutionState.ControlState.RUNNING,
                    activeTaskId = task.id,
                    activeExecutor = executor,
                    activeTaskStartedAt = null,
                    updatedAt = now,
                ),
                activeTask = task,
                continuationTask = continuationTask?.takeUnless { it.id == task.id },
                pendingTasks = if (continuationTask?.id == task.id) {
                    pendingTasks
                } else {
                    pendingTasks.toMutableList().apply { removeAt(pendingIndex) }
                },
            ),
            task,
        )
    }

    fun markActiveTaskStarted(
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        startedAt: Instant,
    ): ConversationRuntimeStateTransition<Boolean> {
        val currentState = executionState ?: return unchanged(false)
        if (currentState.activeTaskId != taskId || currentState.activeExecutor != executor) {
            return unchanged(false)
        }
        if (currentState.activeTaskStartedAt != null) {
            return unchanged(true)
        }
        return changed(
            copy(
                executionState = currentState.copy(
                    activeTaskStartedAt = startedAt,
                    updatedAt = startedAt,
                )
            ),
            true,
        )
    }

    fun confirmActiveTaskOwner(
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
    ): Boolean =
        executionState?.activeTaskId == taskId && executionState.activeExecutor == executor

    fun completeActiveTask(
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        outcome: ConversationRuntimeTaskOutcome,
        now: Instant,
    ): ConversationRuntimeStateTransition<Boolean> {
        val currentState = executionState ?: return unchanged(false)
        val currentTask = activeTask ?: return unchanged(false)
        if (currentState.activeTaskId != taskId ||
            currentState.activeExecutor != executor ||
            currentState.activeTaskStartedAt == null
        ) {
            return unchanged(false)
        }

        val continuation = (outcome as? ConversationRuntimeTaskOutcome.Continue)?.nextTask
        continuation?.let { nextTask ->
            require(nextTask.conversationId == conversationId) {
                "Conversation runtime continuation belongs to another conversation"
            }
            require(nextTask.turnId == currentTask.turnId && nextTask.parentTaskId == currentTask.id) {
                "Conversation runtime continuation does not descend from active task ${currentTask.id.value}"
            }
            check(continuationTask == null) {
                "Conversation runtime already has a continuation for ${conversationId.value}"
            }
        }

        val completedControlState = when (currentState.controlState) {
            ConversationExecutionState.ControlState.PAUSE_REQUESTED ->
                ConversationExecutionState.ControlState.PAUSED
            else -> currentState.controlState
        }
        val terminal = completedControlState == ConversationExecutionState.ControlState.STOPPING ||
            completedControlState == ConversationExecutionState.ControlState.INTERRUPTING
        val promotedPendingTasks = if (outcome is ConversationRuntimeTaskOutcome.CompleteTurn && !terminal) {
            pendingTasks
                .filter { it.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT }
                .map { it.copy(placement = QueuedMessagePlacement.END_OF_TURN) } +
                pendingTasks.filterNot { it.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT }
        } else {
            pendingTasks
        }

        return changed(
            copy(
                executionState = currentState.copy(
                    controlState = completedControlState,
                    activeTaskId = null,
                    activeExecutor = null,
                    activeTaskStartedAt = null,
                    updatedAt = now,
                ),
                activeTask = null,
                activeInsertions = emptyList(),
                continuationTask = continuation?.takeUnless { terminal },
                pendingTasks = promotedPendingTasks,
                completedIdempotencyKeys = completedIdempotencyKeys +
                    currentTask.idempotencyKey +
                    activeInsertions.map { it.idempotencyKey },
            ),
            true,
        )
    }

    fun recordActiveTaskIncident(
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        kind: ConversationRuntimeTaskIncident.Kind,
        message: String,
        errorType: String?,
        occurredAt: Instant,
    ): ConversationRuntimeStateTransition<ConversationRuntimeTaskIncident?> {
        val currentState = executionState ?: return unchanged(null)
        val currentTask = activeTask ?: return unchanged(null)
        if (currentState.activeTaskId != taskId || currentState.activeExecutor != executor) {
            return unchanged(null)
        }
        check(
            (kind == ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN) ==
                (currentState.activeTaskStartedAt != null)
        ) {
            "Runtime incident kind does not match execution start boundary: task=${currentTask.id.value} kind=$kind"
        }

        val incident = ConversationRuntimeTaskIncident(
            task = currentTask,
            kind = kind,
            message = message,
            errorType = errorType,
            executor = currentState.activeExecutor,
            executionStartedAt = currentState.activeTaskStartedAt,
            occurredAt = occurredAt,
        )
        val terminal = currentState.controlState == ConversationExecutionState.ControlState.STOPPING ||
            currentState.controlState == ConversationExecutionState.ControlState.INTERRUPTING
        if (terminal) {
            return changed(
                clearOperationalState().copy(
                    incidents = incidents + incident,
                    completedIdempotencyKeys = completedIdempotencyKeys + currentTask.idempotencyKey,
                ),
                incident,
            )
        }

        val requeuedInsertions = activeInsertions.map {
            it.copy(placement = QueuedMessagePlacement.END_OF_TURN)
        }
        val incidentTask = incidentTaskOrNull(incident)
        val nextPendingTasks = listOfNotNull(incidentTask) + requeuedInsertions + pendingTasks
        return changed(
            copy(
                executionState = if (nextPendingTasks.isEmpty()) {
                    null
                } else {
                    currentState.copy(
                        controlState = when (currentState.controlState) {
                            ConversationExecutionState.ControlState.PAUSE_REQUESTED ->
                                ConversationExecutionState.ControlState.PAUSED
                            else -> currentState.controlState
                        },
                        activeTaskId = null,
                        activeExecutor = null,
                        activeTaskStartedAt = null,
                        updatedAt = occurredAt,
                    )
                },
                activeTask = null,
                activeInsertions = emptyList(),
                continuationTask = null,
                pendingTasks = nextPendingTasks,
                incidents = incidents + incident,
                completedIdempotencyKeys = completedIdempotencyKeys + currentTask.idempotencyKey,
            ),
            incident,
        )
    }

    fun recordPendingTaskDeliveryFailure(
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        message: String,
        errorType: String?,
        occurredAt: Instant,
    ): ConversationRuntimeStateTransition<ConversationRuntimeTaskIncident?> {
        val task = when {
            continuationTask?.id == taskId -> continuationTask
            else -> pendingTasks.firstOrNull { it.id == taskId }
        } ?: return unchanged(null)
        val incident = ConversationRuntimeTaskIncident(
            task = task,
            kind = ConversationRuntimeTaskIncident.Kind.DELIVERY_FAILED,
            message = message,
            errorType = errorType,
            executor = executor,
            executionStartedAt = null,
            occurredAt = occurredAt,
        )
        val remainingTasks = pendingTasks.filterNot { it.id == task.id }
        val incidentTask = incidentTaskOrNull(incident)
        val nextPendingTasks = listOfNotNull(incidentTask) + remainingTasks
        return changed(
            copy(
                executionState = if (nextPendingTasks.isEmpty()) {
                    null
                } else {
                    executionState ?: idleRunningState(occurredAt)
                },
                continuationTask = continuationTask?.takeUnless { it.id == task.id },
                pendingTasks = nextPendingTasks,
                incidents = incidents + incident,
                completedIdempotencyKeys = completedIdempotencyKeys + task.idempotencyKey,
            ),
            incident,
        )
    }

    fun finishIfIdle(): ConversationRuntimeStateTransition<Boolean> {
        val currentState = executionState ?: return unchanged(false)
        if (activeTask != null) return unchanged(false)
        if (currentState.controlState == ConversationExecutionState.ControlState.STOPPING ||
            currentState.controlState == ConversationExecutionState.ControlState.INTERRUPTING
        ) {
            return changed(clearOperationalState(), true)
        }
        if (continuationTask != null || pendingTasks.isNotEmpty()) {
            return unchanged(false)
        }
        return changed(copy(executionState = null), true)
    }

    fun requestPause(now: Instant): ConversationRuntimeStateTransition<Boolean> {
        val currentState = executionState ?: return unchanged(false)
        return when (currentState.controlState) {
            ConversationExecutionState.ControlState.RUNNING -> {
                val nextControlState = if (activeTask == null) {
                    ConversationExecutionState.ControlState.PAUSED
                } else {
                    ConversationExecutionState.ControlState.PAUSE_REQUESTED
                }
                changed(
                    copy(
                        executionState = currentState.copy(
                            controlState = nextControlState,
                            updatedAt = now,
                        )
                    ),
                    true,
                )
            }
            ConversationExecutionState.ControlState.PAUSE_REQUESTED,
            ConversationExecutionState.ControlState.PAUSED -> unchanged(true)
            ConversationExecutionState.ControlState.STOPPING,
            ConversationExecutionState.ControlState.INTERRUPTING -> unchanged(false)
        }
    }

    fun markPaused(now: Instant): ConversationRuntimeStateTransition<Boolean> {
        val currentState = executionState ?: return unchanged(false)
        if (currentState.controlState != ConversationExecutionState.ControlState.PAUSE_REQUESTED ||
            activeTask != null
        ) {
            return unchanged(false)
        }
        return changed(
            copy(
                executionState = currentState.copy(
                    controlState = ConversationExecutionState.ControlState.PAUSED,
                    updatedAt = now,
                )
            ),
            true,
        )
    }

    fun requestResume(now: Instant): ConversationRuntimeStateTransition<Boolean> {
        val currentState = executionState ?: return unchanged(false)
        if (currentState.controlState != ConversationExecutionState.ControlState.PAUSED &&
            currentState.controlState != ConversationExecutionState.ControlState.PAUSE_REQUESTED
        ) {
            return unchanged(false)
        }
        return changed(
            copy(
                executionState = currentState.copy(
                    controlState = ConversationExecutionState.ControlState.RUNNING,
                    updatedAt = now,
                )
            ),
            true,
        )
    }

    fun requestTerminalState(
        controlState: ConversationExecutionState.ControlState,
        now: Instant,
    ): ConversationRuntimeStateTransition<Boolean> {
        require(controlState == ConversationExecutionState.ControlState.STOPPING ||
            controlState == ConversationExecutionState.ControlState.INTERRUPTING
        ) {
            "Conversation runtime terminal request must stop or interrupt"
        }
        val currentState = executionState
        if (currentState == null && activeTask == null && continuationTask == null && pendingTasks.isEmpty()) {
            return unchanged(false)
        }
        val effectiveControlState = if (
            controlState == ConversationExecutionState.ControlState.INTERRUPTING ||
            currentState?.controlState == ConversationExecutionState.ControlState.INTERRUPTING
        ) {
            ConversationExecutionState.ControlState.INTERRUPTING
        } else {
            ConversationExecutionState.ControlState.STOPPING
        }
        val currentTurnInstruction = (activeTask ?: continuationTask)?.let { currentTask ->
            Conversation.Message.Instruction.PreviousTurnTerminated(
                turnId = currentTask.turnId.value,
                reason = when (effectiveControlState) {
                    ConversationExecutionState.ControlState.INTERRUPTING ->
                        Conversation.TurnTerminationReason.INTERRUPTED
                    else -> Conversation.TurnTerminationReason.STOPPED
                },
                occurredAt = now,
            )
        }
        val nextPendingTurnTerminationInstructions = mergeTurnTerminationInstructions(
            pendingTurnTerminationInstructions +
                pendingTasks.flatMap { it.turnTerminationInstructions() } +
                listOfNotNull(currentTurnInstruction)
        )
        if (activeTask == null) {
            return changed(
                clearOperationalState().copy(
                    pendingTurnTerminationInstructions = nextPendingTurnTerminationInstructions,
                ),
                true,
            )
        }
        return changed(
            copy(
                executionState = checkNotNull(currentState).copy(
                    controlState = effectiveControlState,
                    updatedAt = now,
                ),
                continuationTask = null,
                pendingTasks = emptyList(),
                pendingTurnTerminationInstructions = nextPendingTurnTerminationInstructions,
            ),
            true,
        )
    }

    fun abort(): ConversationRuntimeStateTransition<Unit> =
        changed(clearOperationalState(), Unit)

    fun cancelByMessageId(messageId: Conversation.Message.Id): ConversationRuntimeStateTransition<Boolean> {
        val removedTask = pendingTasks.firstOrNull { it.userMessageIdOrNull() == messageId }
            ?: return unchanged(false)
        return changed(
            copy(
                pendingTasks = pendingTasks.filterNot { it.userMessageIdOrNull() == messageId },
                pendingTurnTerminationInstructions = mergeTurnTerminationInstructions(
                    pendingTurnTerminationInstructions + removedTask.turnTerminationInstructions()
                ),
            ),
            true,
        )
    }

    fun claimActiveInsertions(
        taskId: ConversationRuntimeTask.Id,
        executor: ConversationRuntimeExecutorIdentity,
        placement: QueuedMessagePlacement,
    ): ConversationRuntimeStateTransition<List<ConversationRuntimeTask>> {
        if (!confirmActiveTaskOwner(taskId, executor)) return unchanged(emptyList())
        if (activeInsertions.isNotEmpty()) {
            check(activeInsertions.all { it.placement == placement }) {
                "Conversation runtime insertions were already claimed for another safe point"
            }
            return unchanged(activeInsertions)
        }
        val ready = pendingTasks.filter { it.placement == placement }
        if (ready.isEmpty()) return unchanged(emptyList())
        val readyIds = ready.mapTo(mutableSetOf()) { it.id }
        return changed(
            copy(
                activeInsertions = ready,
                pendingTasks = pendingTasks.filterNot { it.id in readyIds },
            ),
            ready,
        )
    }

    fun readyWorkItem(): ConversationRuntimeWorkItem? {
        val currentState = executionState
        if (currentState?.activeTaskId != null) return null
        if (currentState != null && currentState.controlState != ConversationExecutionState.ControlState.RUNNING) {
            return null
        }
        val task = continuationTask ?: pendingTasks.firstOrNull {
            it.placement == QueuedMessagePlacement.END_OF_TURN
        } ?: return null
        return ConversationRuntimeWorkItem(
            conversationId = conversationId,
            reason = ConversationRuntimeWorkItem.Reason.TASK_SUBMITTED,
            taskId = task.id,
            requirements = task.requirements,
            createdAt = task.createdAt,
        )
    }

    fun listPending(): List<ConversationRuntimeTask> =
        listOfNotNull(continuationTask) + pendingTasks

    fun findIncident(taskId: ConversationRuntimeTask.Id): ConversationRuntimeTaskIncident? =
        incidents.lastOrNull { it.task.id == taskId }

    private fun idleRunningState(now: Instant): ConversationExecutionState =
        ConversationExecutionState(
            conversationId = conversationId,
            controlState = ConversationExecutionState.ControlState.RUNNING,
            activeTaskId = null,
            updatedAt = now,
        )

    private fun incidentTaskOrNull(incident: ConversationRuntimeTaskIncident): ConversationRuntimeTask? {
        if (incident.task.payload is ConversationRuntimeTask.Payload.ExecutionIncident) return null
        return ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("${incident.task.id.value}:incident"),
            conversationId = conversationId,
            actorUserId = incident.task.actorUserId,
            payload = ConversationRuntimeTask.Payload.ExecutionIncident(incident.task.id),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "${incident.task.idempotencyKey}:incident",
            requirements = ConversationRuntimeTaskRequirements(
                capabilities = setOf(ConversationRuntimeCapability.CONVERSATION_TURN),
                target = ConversationRuntimeTaskTarget.Server,
            ),
            createdAt = incident.occurredAt,
        )
    }

    private fun clearOperationalState(): ConversationRuntimeSchedulingState =
        copy(
            executionState = null,
            activeTask = null,
            activeInsertions = emptyList(),
            continuationTask = null,
            pendingTasks = emptyList(),
        )

    private fun ConversationRuntimeTask.withTurnTerminationInstructions(
        instructions: List<Conversation.Message.Instruction.PreviousTurnTerminated>,
    ): ConversationRuntimeTask {
        val userTurn = payload as? ConversationRuntimeTask.Payload.UserTurn ?: return this
        if (instructions.isEmpty()) return this
        val message = userTurn.userMessage
        val mergedInstructions = mergeTurnTerminationInstructions(
            message.instructions.filterIsInstance<Conversation.Message.Instruction.PreviousTurnTerminated>() +
                instructions
        )
        val otherInstructions = message.instructions.filterNot {
            it is Conversation.Message.Instruction.PreviousTurnTerminated
        }
        return copy(
            payload = userTurn.copy(
                userMessage = message.copy(instructions = mergedInstructions + otherInstructions),
            )
        )
    }

    private fun ConversationRuntimeTask.turnTerminationInstructions():
        List<Conversation.Message.Instruction.PreviousTurnTerminated> =
        (payload as? ConversationRuntimeTask.Payload.UserTurn)
            ?.userMessage
            ?.instructions
            ?.filterIsInstance<Conversation.Message.Instruction.PreviousTurnTerminated>()
            .orEmpty()

    private fun mergeTurnTerminationInstructions(
        instructions: List<Conversation.Message.Instruction.PreviousTurnTerminated>,
    ): List<Conversation.Message.Instruction.PreviousTurnTerminated> =
        instructions.fold(emptyList()) { merged, instruction ->
            val existingIndex = merged.indexOfFirst { it.turnId == instruction.turnId }
            if (existingIndex < 0) {
                merged + instruction
            } else {
                val existing = merged[existingIndex]
                val preferred = if (
                    instruction.reason == Conversation.TurnTerminationReason.INTERRUPTED &&
                    existing.reason != Conversation.TurnTerminationReason.INTERRUPTED
                ) {
                    instruction
                } else {
                    existing
                }
                merged.toMutableList().apply { this[existingIndex] = preferred }
            }
        }

    private fun <T> unchanged(result: T): ConversationRuntimeStateTransition<T> =
        ConversationRuntimeStateTransition(this, result, changed = false)

    private fun <T> changed(
        state: ConversationRuntimeSchedulingState,
        result: T,
    ): ConversationRuntimeStateTransition<T> =
        ConversationRuntimeStateTransition(state, result, changed = true)
}

data class ConversationRuntimeStateTransition<out T>(
    val state: ConversationRuntimeSchedulingState,
    val result: T,
    val changed: Boolean,
)
