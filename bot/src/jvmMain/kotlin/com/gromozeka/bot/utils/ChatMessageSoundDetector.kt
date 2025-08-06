package com.gromozeka.bot.utils

import com.gromozeka.shared.domain.message.ChatMessage

object ChatMessageSoundDetector {
    
    fun shouldPlayErrorSound(message: ChatMessage): Boolean {
        // Skip historical messages
        if (message.isHistorical) return false
        
        return message.content.any { contentItem ->
            when (contentItem) {
                // System errors
                is ChatMessage.ContentItem.System -> {
                    contentItem.level == ChatMessage.ContentItem.System.SystemLevel.ERROR
                }
                
                // Tool results with errors
                is ChatMessage.ContentItem.ToolResult -> {
                    contentItem.isError
                }
                
                else -> false
            }
        }
    }
    
    fun shouldPlayMessageSound(message: ChatMessage): Boolean {
        // Skip historical messages
        if (message.isHistorical) return false
        
        // Skip if this is an error (error sound takes precedence)
        if (shouldPlayErrorSound(message)) return false
        
        return when (message.messageType) {
            // Assistant messages
            ChatMessage.MessageType.ASSISTANT -> true
            
            // System messages (non-error)
            ChatMessage.MessageType.SYSTEM -> true
            
            // Tool results (non-error) - these are typically user type but not actual user input
            ChatMessage.MessageType.USER -> {
                // Only if it contains tool results (not actual user input)
                message.content.any { it is ChatMessage.ContentItem.ToolResult }
            }
            
            // Tool calls themselves (from assistant)
            ChatMessage.MessageType.TOOL -> true
        }
    }
}