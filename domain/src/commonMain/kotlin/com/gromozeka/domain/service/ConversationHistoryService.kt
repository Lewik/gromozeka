package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationMessageSelection
import com.gromozeka.domain.model.ConversationHistoryPage
import com.gromozeka.domain.model.ConversationHistoryPageRequest
import com.gromozeka.domain.model.SquashType

interface ConversationHistoryService {
    suspend fun loadPage(
        conversationId: Conversation.Id,
        request: ConversationHistoryPageRequest = ConversationHistoryPageRequest(),
    ): ConversationHistoryPage

    suspend fun loadMessage(conversationId: Conversation.Id, messageId: Conversation.Message.Id): Conversation.Message

    suspend fun selectMessageIds(
        conversationId: Conversation.Id,
        selection: ConversationMessageSelection,
    ): List<Conversation.Message.Id>

    suspend fun latestUserMessage(conversationId: Conversation.Id): Conversation.Message?

    suspend fun editMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
        newContent: List<Conversation.Message.ContentItem>,
    ): Conversation?

    suspend fun deleteMessages(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
    ): Conversation?

    suspend fun compactMessages(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
        strategy: SquashType,
    ): Conversation
}
