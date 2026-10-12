package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.ConversationPinCreation
import com.gromozeka.domain.repository.ConversationPinRepository
import kotlin.time.Instant

/** Prompt tests forbid every mutation; storage behavior is covered by actual PostgreSQL tests. */
internal class ReadOnlyTestPins : ConversationPinRepository {
    var values: List<ConversationPin> = emptyList()
    val reads = mutableListOf<Conversation.Id>()
    var failure: Exception? = null
    override suspend fun list(conversationId: Conversation.Id): List<ConversationPin> {
        reads += conversationId
        failure?.let { throw it }
        return values.filter { it.conversationId == conversationId }.toList()
    }
    override suspend fun get(conversationId: Conversation.Id, pinId: ConversationPin.Id): ConversationPin? = error("Pin get is not a prompt read")
    override suspend fun create(conversationId: Conversation.Id, actor: User.Id, text: String, source: ConversationPinSource?, creationKey: String?): ConversationPinCreation = error("Pin writes forbidden")
    override suspend fun update(conversationId: Conversation.Id, pinId: ConversationPin.Id, expectedRevision: Long, text: String): ConversationPin = error("Pin writes forbidden")
    override suspend fun delete(conversationId: Conversation.Id, pinId: ConversationPin.Id, expectedRevision: Long): Unit = error("Pin writes forbidden")
}

internal fun emptyConversationPinnedPrompt() = ConversationPinnedPrompt(ReadOnlyTestPins())
internal fun testPin(owner: Conversation.Id, text: String, ordinal: Long = 1) = ConversationPin(
    ConversationPin.Id("PRIVATE_PIN_ID_$ordinal"), owner, text, ordinal, 1,
    Instant.fromEpochMilliseconds(123), Instant.fromEpochMilliseconds(123),
    ConversationPinSource(Conversation.Id("PRIVATE_SOURCE_ID"), Conversation.Message.Id("PRIVATE_MESSAGE_ID")),
)
