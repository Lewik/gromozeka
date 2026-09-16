package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ConversationHistoryCursor(val threadId: Conversation.Thread.Id, val position: Int) {
    init { require(position >= 0) }
}

@Serializable
data class ConversationHistoryPageRequest(
    val before: ConversationHistoryCursor? = null,
    val after: ConversationHistoryCursor? = null,
    val around: Conversation.Message.Id? = null,
    val positionHint: Int? = null,
    val limit: Int = 50,
) {
    init {
        require(limit in 1..100)
        require(positionHint == null || (positionHint >= 0 && around != null))
        require(listOfNotNull(before, after, around).size <= 1)
    }
}

@Serializable
data class ConversationHistoryMessage(
    val position: Int,
    val message: Conversation.Message,
    val hasMoreContent: Boolean = false,
)

@Serializable
data class ConversationHistoryPage(
    val threadId: Conversation.Thread.Id,
    val messages: List<ConversationHistoryMessage>,
    val relatedMessages: List<ConversationHistoryMessage> = emptyList(),
    val older: ConversationHistoryCursor? = null,
    val newer: ConversationHistoryCursor? = null,
    val eventSequence: Long = 0,
    val reset: Boolean = false,
)

@Serializable
enum class ConversationMessageSelection { ALL, USER, ASSISTANT, THINKING, TOOL, PLAIN }
