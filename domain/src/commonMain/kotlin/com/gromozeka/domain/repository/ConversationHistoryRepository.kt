package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationMessageSelection

data class PositionedConversationMessage(val position: Int, val message: Conversation.Message)

interface ConversationHistoryRepository {
    /** Timestamp of the last linked message by position, for each existing thread; null for an empty thread. Never loads message bodies. */
    suspend fun latestMessageTimes(threadIds: Set<Conversation.Thread.Id>): Map<Conversation.Thread.Id, kotlin.time.Instant?>
    suspend fun page(threadId: Conversation.Thread.Id, before: Int?, after: Int?, limit: Int): List<PositionedConversationMessage>
    /** Canonical message body. */
    suspend fun clientMessage(threadId: Conversation.Thread.Id, messageId: Conversation.Message.Id): PositionedConversationMessage?
    suspend fun position(threadId: Conversation.Thread.Id, messageId: Conversation.Message.Id): Int?
    suspend fun message(threadId: Conversation.Thread.Id, messageId: Conversation.Message.Id): PositionedConversationMessage?
    /** Provider-managed tool results may be separated from their calls; ordinary pairs are read from adjacent page rows. */
    suspend fun providerManagedToolResults(
        threadId: Conversation.Thread.Id,
        callIds: Set<String>,
    ): List<PositionedConversationMessage>
    suspend fun selectedIds(threadId: Conversation.Thread.Id, selection: ConversationMessageSelection): List<Conversation.Message.Id>
    suspend fun latestUserMessage(threadId: Conversation.Thread.Id): PositionedConversationMessage?
    suspend fun latestUserActivity(threadId: Conversation.Thread.Id, userId: com.gromozeka.domain.model.User.Id): com.gromozeka.domain.model.ConversationUserActivity?
    /** Latest matching user-instruction id, using body-free context metadata. */
    suspend fun latestUserInstructionId(threadId: Conversation.Thread.Id, instructionIds: Set<String>): String?
}
