package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationUnreadState
import com.gromozeka.domain.model.User

interface ConversationUnreadStateRepository {
    suspend fun load(userId: User.Id): ConversationUnreadState

    suspend fun markUnread(
        conversationId: Conversation.Id,
        userIds: Set<User.Id>,
    ): Set<User.Id>

    suspend fun markRead(
        conversationId: Conversation.Id,
        userId: User.Id,
    ): Boolean
}
