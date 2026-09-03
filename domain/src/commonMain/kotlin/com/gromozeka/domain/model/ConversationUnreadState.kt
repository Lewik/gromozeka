package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ConversationUnreadState(
    val conversationIds: Set<Conversation.Id> = emptySet(),
)
