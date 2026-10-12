package com.gromozeka.domain.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationPin
import com.gromozeka.domain.model.ConversationPinSource
import kotlinx.coroutines.flow.Flow

/** Client facade. The remote boundary supplies the authenticated actor, never a form field. */
interface ConversationPinService {
    fun observe(conversationId: Conversation.Id): Flow<List<ConversationPin>>
    suspend fun list(conversationId: Conversation.Id): List<ConversationPin>
    suspend fun get(conversationId: Conversation.Id, pinId: ConversationPin.Id): ConversationPin?
    suspend fun create(
        conversationId: Conversation.Id,
        text: String,
        source: ConversationPinSource? = null,
        creationKey: String? = null,
    ): ConversationPin
    suspend fun update(
        conversationId: Conversation.Id,
        pinId: ConversationPin.Id,
        expectedRevision: Long,
        text: String,
    ): ConversationPin
    suspend fun delete(conversationId: Conversation.Id, pinId: ConversationPin.Id, expectedRevision: Long)
}
