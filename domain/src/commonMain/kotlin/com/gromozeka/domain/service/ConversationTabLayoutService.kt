package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationTabLayout
import kotlinx.coroutines.flow.Flow

interface ConversationTabLayoutService {
    suspend fun snapshot(): ConversationTabLayout

    suspend fun open(conversationId: Conversation.Id): ConversationTabLayout

    suspend fun close(conversationId: Conversation.Id): ConversationTabLayout

    fun observe(): Flow<ConversationTabLayout>
}
