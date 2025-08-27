package com.gromozeka.bot.services.llm.claudecode.converter

import com.gromozeka.bot.model.ClaudeCodeStreamJsonLine
import com.gromozeka.bot.model.StreamMessageContent
import com.gromozeka.bot.model.ContentItemsUnion
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import org.springframework.stereotype.Service

/**
 * Converts ChatMessage to ClaudeCodeStreamJsonLine for sending to Claude Code CLI
 * 
 * This class handles the reverse direction of ClaudeStreamToChatConverter:
 * ChatMessage → ClaudeCodeStreamJsonLine.User
 * 
 * Key responsibilities:
 * - Convert ChatMessage structure to StreamJsonLine format
 * - Serialize instructions and sender as XML tags 
 * - Create proper ContentItemsUnion structure for Claude CLI
 */
@Service
class ClaudeChatToStreamConverter {

    /**
     * Convert ChatMessage to ClaudeCodeStreamJsonLine.User for sending to Claude CLI
     */
    fun convert(
        chatMessage: ChatMessage,
        sessionId: ClaudeSessionUuid
    ): ClaudeCodeStreamJsonLine.User {
        // Extract text content from ChatMessage
        val messageText = extractMessageText(chatMessage)
        
        // Serialize tags and combine with message text  
        val messageWithTags = serializeMessageWithTags(
            content = messageText,
            instructions = chatMessage.instructions
        )

        // Create ContentItemsUnion - for simple text we use StringContent
        val content = ContentItemsUnion.StringContent(messageWithTags)

        // Create StreamMessageContent.User
        val streamMessage = StreamMessageContent.User(content = content)

        // Create final ClaudeCodeStreamJsonLine.User
        return ClaudeCodeStreamJsonLine.User(
            message = streamMessage,
            sessionId = sessionId,
            parentToolUseId = null,
            uuid = chatMessage.uuid
        )
    }

    /**
     * Serialize instructions and content into message with XMLL tags
     */
    fun serializeMessageWithTags(
        content: String,
        instructions: List<ChatMessage.Instruction> = emptyList()
    ): String = buildString {
        // Add instruction tags (XMLL format - one per line)
        instructions.forEach { instruction ->
            append("<${instruction.tagName}>${instruction.serializeContent()}</${instruction.tagName}>")
            append("\n")
        }
        
        // Add content
        append(content)
    }


    /**
     * Extract text content from ChatMessage ContentItems
     * 
     * For now we handle simple UserMessage content.
     * Future: could handle more complex content types (images, etc.)
     */
    private fun extractMessageText(chatMessage: ChatMessage): String {
        return chatMessage.content
            .filterIsInstance<ChatMessage.ContentItem.UserMessage>()
            .joinToString("\n") { it.text }
    }
}