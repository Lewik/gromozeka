package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult
import com.gromozeka.domain.model.isFullContextCompaction
import com.gromozeka.domain.repository.ConversationCompactionRepository
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.DeclarativeStateChangePublisher
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Clock
import org.springframework.stereotype.Service

/** The caller owns serialized Conversation Runtime execution, including the final cancellation check. */
internal interface ContextCompactionCommitter {
    suspend fun commit(
        expectedThreadId: Conversation.Thread.Id,
        message: Conversation.Message,
    ): Conversation
}

@Service
internal class ContextCompactionCommitService(
    private val conversationRepository: ConversationRepository,
    private val conversationCompactionRepository: ConversationCompactionRepository,
    private val threadMessageRepository: ThreadMessageRepository,
    private val toolCallPairingService: ToolCallPairingService,
    private val stateChanges: DeclarativeStateChangePublisher,
) : ContextCompactionCommitter {
    override suspend fun commit(
        expectedThreadId: Conversation.Thread.Id,
        message: Conversation.Message,
    ): Conversation {
        val result = message.content.singleOrNull() as? ContextCompactionResult
            ?: error("A compaction commit must contain exactly one checkpoint")
        require(result.state == Conversation.Message.BlockState.COMPLETE) { "Cannot commit an incomplete compaction" }
        require(result.historyTransition == null) { "A new compaction cannot supply its own history transition" }
        val minimum = if (result.strategy == ContextCompactionResult.Strategy.CONCATENATE) 2 else 1
        require(result.sourceMessageIds.size >= minimum) { "Need at least $minimum source message(s) to compact" }
        require(result.sourceMessageIds.distinct().size == result.sourceMessageIds.size) {
            "Duplicate compaction source message IDs are not allowed"
        }
        val conversationId = message.conversationId
        val conversation = conversationRepository.findById(conversationId)
            ?: error("Conversation not found: ${conversationId.value}")
        // A durable checkpoint ID makes a replay after commit but before task completion a no-op.
        if (threadMessageRepository.contains(conversation.currentThread, message.id)) {
            val stored = threadMessageRepository.getMessagesByThread(conversation.currentThread).single { it.id == message.id }
            val checkpoint = stored.content.single() as ContextCompactionResult
            check(checkpoint.historyTransition?.sourceThreadId == expectedThreadId &&
                checkpoint.payload == result.payload && checkpoint.sourceMessageIds == result.sourceMessageIds &&
                checkpoint.providerScope == result.providerScope && checkpoint.strategy == result.strategy &&
                checkpoint.origin == result.origin && checkpoint.promptTemplate == result.promptTemplate) {
                "Compaction ID belongs to another operation"
            }
            return conversation
        }
        check(conversation.currentThread == expectedThreadId) { "Conversation changed while compaction was generated" }

        val messages = threadMessageRepository.getMessagesByThread(expectedThreadId)
        val sourceIdSet = result.sourceMessageIds.toSet()
        require(messages.count { it.id in sourceIdSet } == sourceIdSet.size) {
            "Some compaction source messages are not in thread ${expectedThreadId.value}"
        }
        require(messages.filter { it.id in sourceIdSet }.all { source ->
            source.content.none { it.state == Conversation.Message.BlockState.STREAMING }
        }) { "Cannot compact streaming messages" }
        require(toolCallPairingService.includePairedToolMessages(messages, sourceIdSet) == sourceIdSet) {
            "Compaction source messages must include complete tool call/result pairs"
        }
        require(messages.filter { it.id in sourceIdSet }.map { it.id } == result.sourceMessageIds) {
            "Compaction source messages must be in current conversation order"
        }
        val sourceEnd = messages.indexOfLast { it.id in sourceIdSet }
        val lastFullCheckpoint = messages.indexOfLast { it.isFullContextCompaction() }
        if (result.coversAllPrevious) {
            require(messages.take(sourceEnd + 1).map { it.id } == result.sourceMessageIds) {
                "A full checkpoint must cover exactly the captured canonical prefix"
            }
            require(sourceEnd >= lastFullCheckpoint) { "Compaction input precedes the current full checkpoint" }
        }

        val now = Clock.System.now()
        val newThread = Conversation.Thread(
            id = Conversation.Thread.Id(uuid7()), conversationId = conversationId,
            originalThread = expectedThreadId, createdAt = now, updatedAt = now,
        )
        val invalidatedReplay = if (result.payload is ContextCompactionResult.Payload.ReadableSummary) {
            messages.drop(messages.indexOfFirst { it.id in sourceIdSet })
                .filter { it.role == Conversation.Message.Role.ASSISTANT && it.id !in sourceIdSet }.map { it.id }
        } else emptyList()
        val compactionMessage = message.copy(content = listOf(result.copy(
            invalidatedReplayMessageIds = (result.invalidatedReplayMessageIds + invalidatedReplay).distinct(),
            historyTransition = ContextCompactionResult.HistoryTransition(expectedThreadId, newThread.id),
        )))
        // A selective summary must remain after any existing full checkpoint, otherwise
        // it would disappear behind that checkpoint or split its protected prefix.
        val insertionAnchorId = messages[maxOf(sourceEnd, lastFullCheckpoint)].id
        check(conversationCompactionRepository.commitIfCurrent(
            expectedThreadId = expectedThreadId, compactionMessage = compactionMessage,
            newThread = newThread, insertionAnchorId = insertionAnchorId,
        )) { "Conversation changed while compaction was committed" }

        val updated = conversationRepository.findById(conversationId)
            ?: error("Conversation disappeared after compaction: ${conversationId.value}")
        stateChanges.publish(DeclarativeStateKey.projectConversations(updated.projectId))
        return updated
    }
}
