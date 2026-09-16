package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationMessageSelection

data class PositionedConversationMessage(val position: Int, val message: Conversation.Message)

interface ConversationHistoryRepository {
    suspend fun page(threadId: Conversation.Thread.Id, before: Int?, after: Int?, limit: Int): List<PositionedConversationMessage>
    suspend fun position(threadId: Conversation.Thread.Id, messageId: Conversation.Message.Id): Int?
    suspend fun message(threadId: Conversation.Thread.Id, messageId: Conversation.Message.Id): PositionedConversationMessage?
    suspend fun toolResults(threadId: Conversation.Thread.Id, callIds: Set<String>): List<PositionedConversationMessage>
    suspend fun selectedIds(threadId: Conversation.Thread.Id, selection: ConversationMessageSelection): List<Conversation.Message.Id>
    suspend fun latestUserMessage(threadId: Conversation.Thread.Id): PositionedConversationMessage?
}
