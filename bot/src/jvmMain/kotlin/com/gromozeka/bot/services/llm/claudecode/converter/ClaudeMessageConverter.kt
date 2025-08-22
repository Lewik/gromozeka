package com.gromozeka.bot.services.llm.claudecode.converter

import com.gromozeka.bot.model.ClaudeCodeStreamJsonLine
import com.gromozeka.bot.services.llm.MessageConverter
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import org.springframework.stereotype.Service

/**
 * Facade for bidirectional Claude Code message conversion
 * 
 * This service coordinates between the two specialized converters:
 * - ClaudeStreamToChatConverter: StreamJsonLine → ChatMessage (decode)
 * - ClaudeChatToStreamConverter: ChatMessage → StreamJsonLine (encode)
 * 
 * Benefits of this facade approach:
 * - Single responsibility principle: each converter handles one direction
 * - Easy testing: can test each direction independently
 * - Clean public API: external code uses one service
 * - Future extensibility: easy to add OpenAI/other engine converters
 */
@Service
class ClaudeMessageConverter(
    private val streamToChatConverter: ClaudeStreamToChatConverter,
    private val chatToStreamConverter: ClaudeChatToStreamConverter
) : MessageConverter<ClaudeCodeStreamJsonLine, ClaudeCodeStreamJsonLine.User, ClaudeSessionUuid> {

    /**
     * Convert from Claude Code stream format to internal ChatMessage format
     * 
     * @param streamLine Claude Code CLI output line
     * @return ChatMessage for UI consumption
     */
    override fun toMessage(streamLine: ClaudeCodeStreamJsonLine): ChatMessage {
        return streamToChatConverter.mapToChatMessage(streamLine)
    }

    /**
     * Convert from internal ChatMessage format to Claude Code stream format
     * 
     * @param chatMessage Internal message representation
     * @param sessionId Current Claude session ID
     * @return ClaudeCodeStreamJsonLine.User for sending to Claude CLI
     */
    override fun fromMessage(chatMessage: ChatMessage, sessionId: ClaudeSessionUuid): ClaudeCodeStreamJsonLine.User {
        return chatToStreamConverter.convert(chatMessage, sessionId)
    }


}