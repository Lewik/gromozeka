package com.gromozeka.application.service

import com.gromozeka.domain.service.*
import com.gromozeka.shared.uuid.uuid7
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.BackgroundActivityOrigin
import com.gromozeka.domain.repository.BackgroundActivityOriginRepository
import kotlin.time.Instant

internal class TestBackgroundActivityOrigins : BackgroundActivityOriginRepository {
    private val values = mutableMapOf<BackgroundActivityOrigin.Key, BackgroundActivityOrigin>()
    override suspend fun bindWhileRequestPending(origin: BackgroundActivityOrigin, at: Instant) {
        check(values[origin.key]?.let { it == origin } != false)
        values[origin.key] = origin
    }
    override suspend fun find(conversationId: Conversation.Id, keys: Set<BackgroundActivityOrigin.Key>) =
        values.filter { (key, origin) -> key in keys && origin.conversationId == conversationId }
}

internal suspend fun commitBackgroundBatch(
    coordinator: ConversationRuntimeCoordinator,
    batch: BackgroundActivityCompletionApplicationService.Batch,
) {
    val conversationId = batch.messages.first().conversationId
    val executor = ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("batch-test"))
    suspend fun claim(task: ConversationRuntimeTask) {
        checkNotNull(coordinator.claimDeliveredTask(conversationId, task.id, executor, ConversationRuntimeCapability.entries.toSet(), emptySet()))
        check(coordinator.markActiveTaskStarted(conversationId, task.id, executor, kotlin.time.Clock.System.now()))
    }
    for (pending in coordinator.listPending(conversationId)) {
        check(pending.payload is ConversationRuntimeTask.Payload.ExecutionIncident)
        claim(pending)
        check(coordinator.completeActiveTask(conversationId, pending.id, executor, ConversationRuntimeTaskOutcome.CompleteTurn))
    }
    val id = "delivery-${uuid7()}"
    val task = ConversationRuntimeTask(ConversationRuntimeTask.Id(id), conversationId, actorUserId = batch.actorUserId,
        payload = ConversationRuntimeTask.Payload.BackgroundActivityCompletion(batch.sourceKeys.first(), batch.agentDefinitionId),
        placement = QueuedMessagePlacement.END_OF_TURN, idempotencyKey = id,
        requirements = ConversationRuntimeTaskRequirements(setOf(ConversationRuntimeCapability.CONVERSATION_TURN), ConversationRuntimeTaskTarget.Server),
        createdAt = kotlin.time.Clock.System.now())
    check(coordinator.submit(task)); claim(task)
    check(coordinator.completeActiveTask(conversationId, task.id, executor,
        ConversationRuntimeTaskOutcome.CompleteBackgroundDelivery(listOf(batch.receipt))))
}
