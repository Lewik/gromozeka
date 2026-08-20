package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.repository.ConversationCompactionRepository
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.service.DeclarativeStateChangePublisher
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Clock
import org.springframework.stereotype.Service

internal interface ContextCompactionCommitter {
    suspend fun commit(
        conversationId: Conversation.Id,
        expectedThreadId: Conversation.Thread.Id,
        result: Conversation.Message.ContentItem.ContextCompactionResult,
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
        conversationId: Conversation.Id,
        expectedThreadId: Conversation.Thread.Id,
        result: Conversation.Message.ContentItem.ContextCompactionResult,
    ): Conversation {
        require(result.sourceMessageIds.size >= 2) { "Need at least 2 source messages to compact" }
        require(result.sourceMessageIds.distinct().size == result.sourceMessageIds.size) {
            "Duplicate compaction source message IDs are not allowed"
        }
        val conversation = conversationRepository.findById(conversationId)
            ?: error("Conversation not found: ${conversationId.value}")
        check(conversation.currentThread == expectedThreadId) {
            "Conversation changed while compaction was generated"
        }

        val messages = threadMessageRepository.getMessagesByThread(expectedThreadId)
        val links = threadMessageRepository.getByThread(expectedThreadId)
        val sourceIdSet = result.sourceMessageIds.toSet()
        require(messages.count { it.id in sourceIdSet } == sourceIdSet.size) {
            "Some compaction source messages are not in thread ${expectedThreadId.value}"
        }
        require(toolCallPairingService.includePairedToolMessages(messages, sourceIdSet) == sourceIdSet) {
            "Compaction source messages must include complete tool call/result pairs"
        }
        ensureMessagesAreNotCoveredByCompaction(
            messages = messages,
            targetMessageIds = sourceIdSet,
            operation = "compact",
            allowLatestReadableCompaction = true,
        )
        val orderedSourceIds = links.map { it.messageId }.filter(sourceIdSet::contains)
        require(orderedSourceIds == result.sourceMessageIds) {
            "Compaction source messages must be in current conversation order"
        }

        val now = Clock.System.now()
        val compactionMessage = Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(result),
            createdAt = now,
        )
        val newThread = Conversation.Thread(
            id = Conversation.Thread.Id(uuid7()),
            conversationId = conversationId,
            originalThread = expectedThreadId,
            createdAt = now,
            updatedAt = now,
        )

        val lastSourceId = orderedSourceIds.last()
        var nextPosition = 0
        val newLinks = links.mapNotNull { link ->
            when {
                link.messageId == lastSourceId -> link.copy(
                    threadId = newThread.id,
                    messageId = compactionMessage.id,
                    position = nextPosition++,
                )
                link.messageId in sourceIdSet -> null
                else -> link.copy(threadId = newThread.id, position = nextPosition++)
            }
        }
        check(
            conversationCompactionRepository.commitIfCurrent(
                expectedThreadId = expectedThreadId,
                compactionMessage = compactionMessage,
                newThread = newThread,
                newLinks = newLinks,
            )
        ) { "Conversation changed while compaction was committed" }

        val updatedConversation = conversationRepository.findById(conversationId)
            ?: error("Conversation disappeared after compaction: ${conversationId.value}")
        stateChanges.publish(DeclarativeStateKey.projectConversations(updatedConversation.projectId))
        return updatedConversation
    }
}
