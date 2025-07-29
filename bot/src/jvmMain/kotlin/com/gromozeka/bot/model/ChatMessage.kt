package com.gromozeka.bot.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String,
    val role: Role,
    val content: List<ChatMessageContent>,
    val timestamp: Instant,
    val metadataType: MetadataType,
) {
    enum class Role {
        USER,
        ASSISTANT,
    }

    enum class MetadataType {
        IDE_CONTEXT,
        NONE,
    }
}
