package com.gromozeka.bot.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageContent( //todo rename to Content and move inside ChatMessage
    val content: String,
    val type: Type,
    val annotationsJson: String,
) {

    enum class Type {
        TEXT,
        IMAGE,
        IMAGE_URL,
        REFUSAL,
        UNKNOWN,
    }
}