package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Conversation

interface SquashOperationRepository {
    suspend fun save(operation: Conversation.SquashOperation): Conversation.SquashOperation
    suspend fun findById(id: Conversation.SquashOperation.Id): Conversation.SquashOperation?
    suspend fun findByResultMessage(messageId: Conversation.Message.Id): Conversation.SquashOperation?
    suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.SquashOperation>
}
