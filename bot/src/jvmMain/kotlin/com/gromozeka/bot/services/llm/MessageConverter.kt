package com.gromozeka.bot.services.llm

import com.gromozeka.shared.domain.message.ChatMessage

/**
 * Generic interface for bidirectional message conversion between LLM-specific formats and ChatMessage
 * 
 * This interface abstracts message conversion for different LLM engines:
 * - Claude Code CLI: ClaudeCodeStreamJsonLine ↔ ChatMessage
 * - OpenAI API: OpenAIMessage ↔ ChatMessage  
 * - Anthropic API: AnthropicMessage ↔ ChatMessage
 * - DeepSeek, etc.
 * 
 * Type parameters:
 * @param IncomingFormat LLM-specific incoming format (e.g. ClaudeCodeStreamJsonLine)
 * @param OutgoingFormat LLM-specific outgoing format (e.g. ClaudeCodeStreamJsonLine.User)
 * @param SessionId LLM-specific session identifier type (e.g. ClaudeSessionUuid, String)
 */
interface MessageConverter<IncomingFormat, OutgoingFormat, SessionId> {

    /**
     * Convert from LLM-specific format to internal ChatMessage format
     * 
     * @param streamData LLM engine output (stream line, API response, etc.)
     * @return ChatMessage for UI consumption
     */
    fun toMessage(streamData: IncomingFormat): ChatMessage

    /**
     * Convert from internal ChatMessage format to LLM-specific format
     * 
     * @param chatMessage Internal message representation
     * @param sessionId Current session identifier for the LLM
     * @return LLM-specific format for sending to the engine
     */
    fun fromMessage(chatMessage: ChatMessage, sessionId: SessionId): OutgoingFormat

}