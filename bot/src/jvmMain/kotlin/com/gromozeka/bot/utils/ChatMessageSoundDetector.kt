package com.gromozeka.bot.utils

import com.gromozeka.shared.domain.conversation.ConversationTree

object ChatMessageSoundDetector {

    fun shouldPlayErrorSound(message: ConversationTree.Message?): Boolean {
        if (message == null) return false

        return message.content.any { contentItem ->
            when (contentItem) {
                // System errors
                is ConversationTree.Message.ContentItem.System -> {
                    contentItem.level == ConversationTree.Message.ContentItem.System.SystemLevel.ERROR
                }

                // Tool results with errors
                is ConversationTree.Message.ContentItem.ToolResult -> {
                    contentItem.isError
                }

                else -> false
            }
        }
    }

    fun shouldPlayMessageSound(message: ConversationTree.Message?): Boolean {
        if (message == null) return false

        // Skip if this is an error (error sound takes precedence)
        if (shouldPlayErrorSound(message)) return false

        return when (message.role) {
            // Assistant messages
            ConversationTree.Message.Role.ASSISTANT -> true

            // System messages (non-error)
            ConversationTree.Message.Role.SYSTEM -> true

            // Tool results (non-error) - these are typically user type but not actual user input
            ConversationTree.Message.Role.USER -> {
                // Only if it contains tool results (not actual user input)
                message.content.any { it is ConversationTree.Message.ContentItem.ToolResult }
            }
        }
    }
}