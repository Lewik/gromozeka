package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import com.gromozeka.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UserConversationTabLayoutService {
    suspend fun snapshot(userId: User.Id): ConversationTabLayout

    suspend fun open(userId: User.Id, conversationId: Conversation.Id): ConversationTabLayout

    suspend fun close(userId: User.Id, conversationId: Conversation.Id): ConversationTabLayout

    suspend fun removeConversation(conversationId: Conversation.Id)

    fun observe(userId: User.Id): Flow<ConversationTabLayout>
}
