package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Conversation
import kotlin.time.Instant

interface ConversationMessageBatchRepository {
    /** Append all bodies and ordered links atomically. Return only newly linked messages. */
    suspend fun append(
        conversationId: Conversation.Id,
        expectedThreadId: Conversation.Thread.Id,
        messages: List<Conversation.Message>,
        at: Instant,
    ): List<Conversation.Message>
}

/** Replaying a durable message ID may have a new attempt timestamp, but cannot change its body. */
fun Conversation.Message.samePersistentBodyAs(other: Conversation.Message): Boolean =
    conversationId == other.conversationId && originalIds == other.originalIds && replyTo == other.replyTo &&
        role == other.role && author == other.author && content == other.content && instructions == other.instructions &&
        providerMetadata == other.providerMetadata && error == other.error
