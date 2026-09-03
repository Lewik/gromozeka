package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationUnreadState
import com.gromozeka.domain.model.User
import kotlinx.coroutines.flow.Flow

interface ConversationUnreadStateService {
    suspend fun snapshot(): ConversationUnreadState

    suspend fun markRead(conversationId: Conversation.Id): ConversationUnreadState

    fun observe(): Flow<ConversationUnreadState>
}

interface UserConversationUnreadStateService {
    suspend fun snapshot(userId: User.Id): ConversationUnreadState

    suspend fun markRead(
        userId: User.Id,
        conversationId: Conversation.Id,
    ): ConversationUnreadState
}
