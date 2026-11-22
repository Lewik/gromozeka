package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult
import com.gromozeka.domain.model.recordRetainedOpenAiReplay
import com.gromozeka.domain.model.withDurableRuntimeObservationBoundary
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
        preparedCompactionId: String? = null,
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
        preparedCompactionId: String?,
    ): Conversation {
        val result = message.content.singleOrNull() as? ContextCompactionResult
            ?: error("A compaction commit must contain exactly one checkpoint")
        require(result.state == Conversation.Message.BlockState.COMPLETE) { "Cannot commit an incomplete compaction" }
        require(result.historyTransition == null) { "A new compaction cannot supply its own history transition" }
        val minimum = if (result.strategy == ContextCompactionResult.Strategy.CONCATENATE) 2 else 1
        when (result.coverage) {
            ContextCompactionResult.Coverage.SELECTED_MESSAGES -> {
                require(result.sourceBoundaryMessageId == null) { "A selective checkpoint cannot have a source boundary" }
                require(result.sourceMessageIds.size >= minimum) { "Need at least $minimum source message(s) to compact" }
                require(result.sourceMessageIds.distinct().size == result.sourceMessageIds.size) {
                    "Duplicate compaction source message IDs are not allowed"
                }
            }
            ContextCompactionResult.Coverage.ALL_PREVIOUS -> {
                require(result.sourceMessageIds.isEmpty()) { "A full checkpoint must use one source boundary instead of message IDs" }
                requireNotNull(result.sourceBoundaryMessageId) { "A full checkpoint must identify its source boundary" }
            }
        }
        val conversationId = message.conversationId
        val conversation = conversationRepository.findById(conversationId)
            ?: error("Conversation not found: ${conversationId.value}")
        // A durable checkpoint ID makes a replay after commit but before task completion a no-op.
        if (threadMessageRepository.contains(conversation.currentThread, message.id)) {
            val stored = threadMessageRepository.compactionBodies(conversation.currentThread, listOf(message.id)).single()
            val checkpoint = stored.content.single() as ContextCompactionResult
            check(checkpoint.historyTransition?.sourceThreadId == expectedThreadId &&
                checkpoint.payload == result.payload && checkpoint.sourceMessageIds == result.sourceMessageIds &&
                checkpoint.sourceBoundaryMessageId == result.sourceBoundaryMessageId && checkpoint.coverage == result.coverage &&
                checkpoint.providerScope == result.providerScope && checkpoint.strategy == result.strategy &&
                checkpoint.origin == result.origin && checkpoint.promptTemplate == result.promptTemplate) {
                "Compaction ID belongs to another operation"
            }
            return conversation
        }
        check(conversation.currentThread == expectedThreadId) { "Conversation changed while compaction was generated" }

        val messages = threadMessageRepository.compactionHeaders(expectedThreadId)
        val selected = when (result.coverage) {
            ContextCompactionResult.Coverage.SELECTED_MESSAGES -> {
                val sourceIdSet = result.sourceMessageIds.toSet()
                messages.filter { it.id in sourceIdSet }.also {
                    require(it.size == sourceIdSet.size) {
                        "Some compaction source messages are not in thread ${expectedThreadId.value}"
                    }
                    require(it.map(CompactionMessageHeader::id) == result.sourceMessageIds) {
                        "Compaction source messages must be in current conversation order"
                    }
                }
            }
            ContextCompactionResult.Coverage.ALL_PREVIOUS -> {
                val boundary = messages.indexOfFirst { it.id == result.sourceBoundaryMessageId }
                require(boundary >= 0) { "Compaction source boundary is not in thread ${expectedThreadId.value}" }
                messages.take(boundary + 1)
            }
        }
        require(selected.size >= minimum) { "Need at least $minimum source message(s) to compact" }
        require(selected.none { it.streaming }) { "Cannot compact streaming messages" }
        val sourceIdSet = selected.mapTo(linkedSetOf(), CompactionMessageHeader::id)
        require(toolCallPairingService.includePairedHeaders(messages, sourceIdSet) == sourceIdSet) {
            "Compaction source messages must include complete tool call/result pairs"
        }
        val sourceEnd = messages.indexOfFirst { it.id == selected.last().id }
        val lastFullCheckpoint = messages.indexOfLast { it.node.isFull }
        if (result.coversAllPrevious) {
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
        val suffix = messages.subList(sourceEnd + 1, messages.size)
        val previous = messages.getOrNull(lastFullCheckpoint)?.retainedReplay
        var compactionMessage = message.copy(content = listOf(result.copy(
            invalidatedReplayMessageIds = (result.invalidatedReplayMessageIds + invalidatedReplay).distinct(),
            historyTransition = ContextCompactionResult.HistoryTransition(expectedThreadId, newThread.id),
        )))
        if (selected.any { it.durableRuntimeObservationBoundary }) {
            compactionMessage = compactionMessage.withDurableRuntimeObservationBoundary()
        }
        compactionMessage = compactionMessage.recordRetainedOpenAiReplay(previous, suffix)
        // A selective checkpoint must remain after any existing full checkpoint, otherwise
        // it would disappear behind that checkpoint or split its protected prefix.
        val insertionAnchorId = messages[maxOf(sourceEnd, lastFullCheckpoint)].id
        check(conversationCompactionRepository.commitIfCurrent(
            expectedThreadId = expectedThreadId, compactionMessage = compactionMessage,
            newThread = newThread, insertionAnchorId = insertionAnchorId, preparedCompactionId = preparedCompactionId,
        )) { "Conversation changed or preparation was cancelled while compaction was committed" }

        val updated = conversationRepository.findById(conversationId)
            ?: error("Conversation disappeared after compaction: ${conversationId.value}")
        stateChanges.publish(DeclarativeStateKey.projectConversations(updated.projectId))
        return updated
    }
}
