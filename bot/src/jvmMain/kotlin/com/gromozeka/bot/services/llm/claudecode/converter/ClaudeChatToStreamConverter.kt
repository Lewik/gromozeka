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
            instructions = chatMessage.instructions,
            sender = chatMessage.sender
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
     * Serialize instructions, sender and content into message with XML tags
     * 
     * This centralizes the XML serialization logic that was previously in Session.Command.SendMessage
     */
    fun serializeMessageWithTags(
        content: String,
        instructions: List<ChatMessage.Instruction> = emptyList(),
        sender: ChatMessage.Sender? = null
    ): String = buildString {
        // Add sender tag first
        sender?.let { 
            append(serializeSenderToXml(it))
            append("\n")
        }
        
        // Add instruction tags
        instructions.forEach { instruction ->
            append(serializeInstructionToXml(instruction))
            append("\n")
        }
        
        // Add content
        append(content)
    }

    /**
     * Serialize ChatMessage.Sender to XML tag
     */
    fun serializeSenderToXml(sender: ChatMessage.Sender): String = when (sender) {
        is ChatMessage.Sender.User -> "<message_source>user</message_source>"
        is ChatMessage.Sender.Tab -> "<message_source>tab:${sender.id}</message_source>"
    }

    /**
     * Serialize ChatMessage.Instruction to XML tag
     */
    fun serializeInstructionToXml(instruction: ChatMessage.Instruction): String =
        "<instruction>${instruction.id}:${instruction.title}:${instruction.description}</instruction>"

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