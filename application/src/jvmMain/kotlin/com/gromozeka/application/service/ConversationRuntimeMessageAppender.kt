package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation

/**
 * Persists messages for a runtime task that already owns serialized conversation execution.
 * This operation must not be exposed directly to remote clients.
 */
interface ConversationRuntimeMessageAppender {
    /** Atomic ordered append; no observer can see half a tool-call/result pair. */
    suspend fun appendRuntimeMessages(conversationId: Conversation.Id, messages: List<Conversation.Message>): List<Conversation.Message>

    suspend fun appendRuntimeMessage(
        conversationId: Conversation.Id,
        message: Conversation.Message,
    ): Conversation?
}
