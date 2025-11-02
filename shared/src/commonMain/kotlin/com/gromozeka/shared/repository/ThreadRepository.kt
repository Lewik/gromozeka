package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Conversation
import kotlin.time.Instant

interface ThreadRepository {
    suspend fun save(thread: Conversation.Thread): Conversation.Thread
    suspend fun findById(id: Conversation.Thread.Id): Conversation.Thread?
    suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.Thread>
    suspend fun delete(id: Conversation.Thread.Id)
    suspend fun updateTimestamp(id: Conversation.Thread.Id, updatedAt: Instant)
}
