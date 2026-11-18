package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Durable preparation receipt. Original history stays in its immutable thread. */
@Serializable
data class ProactiveCompaction(
    val id: String,
    val conversationId: Conversation.Id,
    val sourceTaskId: ConversationRuntimeTask.Id,
    val sourceTurnId: ConversationRuntimeTurnId,
    val executor: ConversationRuntimeExecutorIdentity,
    val agentId: AgentDefinition.Id,
    val actorUserId: com.gromozeka.domain.model.User.Id,
    val threadId: Conversation.Thread.Id,
    val capturedMessageCount: Int,
    val historyFingerprint: String,
    val scopeFingerprint: String,
    val inputTokens: Int,
    val sourceCount: Int,
    val retainedCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
    val status: Status = Status.RUNNING,
    val checkpoint: Conversation.Message? = null,
    val failureCode: String? = null,
    val outcomeUnknown: Boolean = false,
) {
    init {
        require(id.isNotBlank() && capturedMessageCount > 0 && sourceCount > 0 && retainedCount >= 0 && inputTokens >= 0)
        require(sourceCount + retainedCount == capturedMessageCount)
        require(historyFingerprint.matches(Regex("[0-9a-f]{64}")) && scopeFingerprint.matches(Regex("[0-9a-f]{64}")))
        require((status == Status.READY) == (checkpoint != null))
        require(checkpoint == null || (checkpoint.conversationId == conversationId && checkpoint.id.value == id))
    }

    @Serializable
    enum class Status { RUNNING, READY, FAILED, CANCELLED, DISCARDED, APPLIED }
    val pending: Boolean get() = status == Status.RUNNING || status == Status.READY
    val cancellable: Boolean get() = pending || (status == Status.FAILED && outcomeUnknown)
    fun cancel(now: Instant): ProactiveCompaction =
        if (cancellable) copy(status = Status.CANCELLED, checkpoint = null, updatedAt = now) else this
    fun applicationTask() = ConversationRuntimeTask(
        id = ConversationRuntimeTask.Id("$id:apply"), conversationId = conversationId, actorUserId = actorUserId,
        payload = ConversationRuntimeTask.Payload.ApplyPreparedCompaction(id), placement = QueuedMessagePlacement.END_OF_TURN,
        idempotencyKey = "compaction:$id:apply",
        requirements = ConversationRuntimeTaskRequirements(setOf(ConversationRuntimeCapability.CONVERSATION_TURN), ConversationRuntimeTaskTarget.Server),
        createdAt = updatedAt,
    )
    fun activity() = ProactiveCompactionActivity(id, sourceTaskId, status, inputTokens, sourceCount, retainedCount, createdAt, updatedAt, failureCode, outcomeUnknown)
}

/** Public status never exposes prepared content or configuration fingerprints. */
@Serializable
data class ProactiveCompactionActivity(
    val id: String,
    val sourceTaskId: ConversationRuntimeTask.Id,
    val status: ProactiveCompaction.Status,
    val inputTokens: Int,
    val sourceCount: Int,
    val retainedCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val failureCode: String? = null,
    val outcomeUnknown: Boolean = false,
) {
    val pending: Boolean get() = status == ProactiveCompaction.Status.RUNNING || status == ProactiveCompaction.Status.READY
    val cancellable: Boolean get() = pending || (status == ProactiveCompaction.Status.FAILED && outcomeUnknown)
}
