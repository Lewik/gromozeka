package com.gromozeka.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ConversationTabLayout(
    val conversationIds: List<Conversation.Id> = emptyList(),
    val revision: Long = 0,
    val updatedAt: Instant? = null,
) {
    init {
        require(conversationIds.distinct().size == conversationIds.size) {
            "Conversation tab layout must not contain duplicate conversations"
        }
        require(revision >= 0) { "Conversation tab layout revision must not be negative" }
    }
}
