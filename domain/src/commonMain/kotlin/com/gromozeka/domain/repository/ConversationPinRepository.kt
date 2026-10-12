package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationPin
import com.gromozeka.domain.model.ConversationPinSource
import com.gromozeka.domain.model.User

/** One ordered catalog read; mutations serialize on the owning Conversation row. */
interface ConversationPinRepository {
    suspend fun list(conversationId: Conversation.Id): List<ConversationPin>
    suspend fun get(conversationId: Conversation.Id, pinId: ConversationPin.Id): ConversationPin?
    suspend fun create(
        conversationId: Conversation.Id,
        actor: User.Id,
        text: String,
        source: ConversationPinSource? = null,
        creationKey: String? = null,
    ): ConversationPinCreation
    suspend fun update(conversationId: Conversation.Id, pinId: ConversationPin.Id, expectedRevision: Long, text: String): ConversationPin
    suspend fun delete(conversationId: Conversation.Id, pinId: ConversationPin.Id, expectedRevision: Long)
}

/** Replays return the existing live pin; replay after deletion fails and never resurrects it. */
data class ConversationPinCreation(val pin: ConversationPin, val created: Boolean)
