package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation

/**
 * Persists messages for a runtime task that already owns serialized conversation execution.
 * This operation must not be exposed directly to remote clients.
 */
interface ConversationRuntimeMessageAppender {
    suspend fun appendRuntimeMessage(
        conversationId: Conversation.Id,
        message: Conversation.Message,
    ): Conversation?
}
