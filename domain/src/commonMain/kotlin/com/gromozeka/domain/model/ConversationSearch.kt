package com.gromozeka.domain.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

const val CONVERSATION_SEARCH_DEFAULT_LIMIT = 20
const val CONVERSATION_SEARCH_MAX_LIMIT = 50

@Serializable
data class ConversationSearchRequest(
    val query: String,
    val projectId: Project.Id? = null,
    val conversationId: Conversation.Id? = null,
    val roles: Set<Conversation.Message.Role> = setOf(
        Conversation.Message.Role.USER,
        Conversation.Message.Role.ASSISTANT,
    ),
    val threadScope: ThreadScope = ThreadScope.CURRENT,
    val includeMetadataMatches: Boolean = true,
    val limit: Int = CONVERSATION_SEARCH_DEFAULT_LIMIT,
    val cursor: String? = null,
) {
    init {
        require(query.isNotBlank()) { "Conversation search query must not be blank" }
        require(query.length <= 240) { "Conversation search query must not exceed 240 characters" }
        require(limit in 1..CONVERSATION_SEARCH_MAX_LIMIT) {
            "Conversation search limit must be between 1 and $CONVERSATION_SEARCH_MAX_LIMIT"
        }
        require(roles.isNotEmpty()) { "Conversation search roles must not be empty" }
        require(roles.all { it == Conversation.Message.Role.USER || it == Conversation.Message.Role.ASSISTANT }) {
            "Conversation search supports only USER and ASSISTANT roles"
        }
        require(cursor == null || cursor.length <= 512) { "Conversation search cursor is too long" }
    }

    @Serializable
    enum class ThreadScope {
        CURRENT,
        ALL,
    }
}

@Serializable
data class ConversationSearchPage(
    val hits: List<ConversationSearchHit>,
    val nextCursor: String? = null,
)

@Serializable
data class ConversationSearchHit(
    val project: Project,
    val conversation: Conversation,
    val matchKind: MatchKind,
    val threadId: Conversation.Thread.Id,
    val messageId: Conversation.Message.Id? = null,
    val role: Conversation.Message.Role? = null,
    val matchedAt: Instant,
    val excerpt: String,
) {
    @Serializable
    enum class MatchKind {
        CONVERSATION,
        PROJECT,
        MESSAGE,
    }
}
