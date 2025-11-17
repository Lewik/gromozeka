package com.gromozeka.bot.utils

import com.gromozeka.domain.model.Conversation

object ChatMessageSoundDetector {

    fun shouldPlayErrorSound(message: Conversation.Message?): Boolean {
        if (message == null) return false

        return message.content.any { contentItem ->
            when (contentItem) {
                // System errors
                is Conversation.Message.ContentItem.System -> {
                    contentItem.level == Conversation.Message.ContentItem.System.SystemLevel.ERROR
                }

                // Tool results with errors
                is Conversation.Message.ContentItem.ToolResult -> {
                    contentItem.isError
                }

                else -> false
            }
        }
    }

    fun shouldPlayMessageSound(message: Conversation.Message?): Boolean {
        if (message == null) return false

        // Skip if this is an error (error sound takes precedence)
        if (shouldPlayErrorSound(message)) return false

        return when (message.role) {
            // Assistant messages
            Conversation.Message.Role.ASSISTANT -> true

            // System messages (non-error)
            Conversation.Message.Role.SYSTEM -> true

            // Tool results (non-error) - these are typically user type but not actual user input
            Conversation.Message.Role.USER -> {
                // Only if it contains tool results (not actual user input)
                message.content.any { it is Conversation.Message.ContentItem.ToolResult }
            }
        }
    }
}