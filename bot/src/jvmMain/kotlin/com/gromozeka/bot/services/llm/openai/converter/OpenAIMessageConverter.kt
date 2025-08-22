package com.gromozeka.bot.services.llm.openai.converter

import com.gromozeka.bot.services.llm.MessageConverter
import com.gromozeka.shared.domain.message.ChatMessage
// import org.springframework.stereotype.Service

/**
 * FUTURE IMPLEMENTATION EXAMPLE
 * 
 * OpenAI API message converter demonstrating how the generic MessageConverter interface
 * can be implemented for different LLM engines.
 * 
 * This class shows the architectural pattern for adding new LLM support:
 * 1. Define engine-specific message types (OpenAIMessage, OpenAIRequest)
 * 2. Implement MessageConverter interface with appropriate type parameters
 * 3. Handle engine-specific serialization/deserialization logic
 */
// @Service  // Commented out since this is just an example
class OpenAIMessageConverter(
    // private val openAIToChatConverter: OpenAIToChatConverter,
    // private val chatToOpenAIConverter: ChatToOpenAIConverter
) : MessageConverter<OpenAIMessage, OpenAIRequest, String> {

    /**
     * Convert OpenAI API response to ChatMessage
     */
    override fun toMessage(streamData: OpenAIMessage): ChatMessage {
        TODO("Implementation when OpenAI support is added")
        // return openAIToChatConverter.convert(streamData)
    }

    /**
     * Convert ChatMessage to OpenAI API request
     */
    override fun fromMessage(chatMessage: ChatMessage, sessionId: String): OpenAIRequest {
        TODO("Implementation when OpenAI support is added")
        // return chatToOpenAIConverter.convert(chatMessage, sessionId)
    }

    /**
     * Serialize message with OpenAI-specific formatting
     * OpenAI might use JSON metadata instead of XML tags
     */
    override fun serializeMessageWithTags(
        content: String,
        instructions: List<ChatMessage.Instruction>,
        sender: ChatMessage.Sender?
    ): String {
        TODO("OpenAI-specific serialization (probably JSON metadata)")
        // Example: JSON metadata approach
        // return jsonObject {
        //     "content" to content
        //     "instructions" to instructions.map { ... }
        //     "sender" to sender?.let { ... }
        // }.toString()
    }
}

/**
 * Placeholder types for OpenAI API integration
 * These would be defined based on actual OpenAI API structure
 */
sealed class OpenAIMessage {
    data class ChatCompletion(val choices: List<Choice>) : OpenAIMessage()
    data class StreamChunk(val delta: Delta) : OpenAIMessage()
    
    data class Choice(val message: Message)
    data class Delta(val content: String?)
    data class Message(val role: String, val content: String)
}

data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage.Message>,
    val stream: Boolean = false
)

/**
 * Architecture comparison:
 * 
 * Claude Code:
 * MessageConverter<ClaudeCodeStreamJsonLine, ClaudeCodeStreamJsonLine.User, ClaudeSessionUuid>
 * 
 * OpenAI:
 * MessageConverter<OpenAIMessage, OpenAIRequest, String>
 * 
 * Anthropic API:
 * MessageConverter<AnthropicResponse, AnthropicRequest, AnthropicSessionId>
 * 
 * Each engine can have completely different:
 * - Message structures
 * - Session management
 * - Serialization formats
 * 
 * But all use the same unified ChatMessage for UI consistency.
 */