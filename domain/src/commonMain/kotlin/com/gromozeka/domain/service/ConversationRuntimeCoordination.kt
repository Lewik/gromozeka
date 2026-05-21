package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
data class ConversationRuntimeCommand(
    val id: Id,
    val conversationId: Conversation.Id,
    val userMessage: Conversation.Message,
    val agent: AgentDefinition,
    val placement: QueuedMessagePlacement,
    val createdAt: Instant,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)
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
    val status: Status,
    val phase: Phase,
    val activeCommandId: ConversationRuntimeCommand.Id?,
    val activeWorkerId: String? = null,
    val leaseUntil: Instant? = null,
    val updatedAt: Instant,
) {
    @Serializable
    enum class Status {
        RUNNING,
        PAUSE_REQUESTED,
        PAUSED,
        STOPPING,
        INTERRUPTING,
    }

    @Serializable
    enum class Phase {
        BEFORE_LLM,
        RUNNING_LLM,
        AFTER_LLM,
        RUNNING_TOOL,
        AFTER_TOOL_RESULT,
        END_OF_TURN,
    }
}

@Serializable
sealed interface ConversationRuntimeEvent {
    val conversationId: Conversation.Id

    @Serializable
    data class MessageEmitted(
        override val conversationId: Conversation.Id,
        val commandId: ConversationRuntimeCommand.Id?,
        val message: Conversation.Message,
    ) : ConversationRuntimeEvent

    @Serializable
    data class ExecutionCompleted(
        override val conversationId: Conversation.Id,
    ) : ConversationRuntimeEvent

    @Serializable
    data class ExecutionFailed(
        override val conversationId: Conversation.Id,
        val message: String,
        val type: String? = null,
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

interface ConversationRuntimeCoordinator {
    suspend fun submit(command: ConversationRuntimeCommand): Boolean

    suspend fun claimNextTurn(
        conversationId: Conversation.Id,
        workerId: String,
        leaseUntil: Instant?,
    ): ConversationRuntimeCommand?

    suspend fun completeActiveTurn(conversationId: Conversation.Id)

    suspend fun finishIfIdle(conversationId: Conversation.Id): Boolean

    suspend fun markPhase(
        conversationId: Conversation.Id,
        phase: ConversationExecutionState.Phase,
    )

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
        placement: QueuedMessagePlacement,
    ): List<ConversationRuntimeCommand>

    suspend fun listPending(conversationId: Conversation.Id): List<ConversationRuntimeCommand>
}
