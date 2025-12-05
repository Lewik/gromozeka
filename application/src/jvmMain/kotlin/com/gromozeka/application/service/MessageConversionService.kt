package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import klog.KLoggers
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.stereotype.Service

/**
 * Service for converting messages between Conversation domain model
 * and Spring AI message format.
 *
 * Supports:
 * - User messages
 * - Assistant messages (with text and tool calls)
 * - Tool response messages
 */
@Service
class MessageConversionService {
    private val log = KLoggers.logger(this)

    /**
     * Convert Conversation.Message to Spring AI Message format.
     *
     * Handles:
     * - USER role → UserMessage
     * - ASSISTANT role with thinking blocks → AssistantMessage(thinking, metadata)
     * - ASSISTANT role with tool calls → AssistantMessage(text, toolCalls)
     * - ASSISTANT role with tool results → ToolResponseMessage
     * - ASSISTANT role with text only → AssistantMessage(text)
     */
    fun toSpringAI(message: Conversation.Message): Message? {
        return when (message.role) {
            Conversation.Message.Role.USER -> {
                val toolResults = message.content.filterIsInstance<Conversation.Message.ContentItem.ToolResult>()

                if (toolResults.isNotEmpty()) {
                    log.debug { "Converting ${toolResults.size} tool results from USER message to ToolResponseMessage" }
                    buildToolResponseMessage(toolResults)
                } else {
                    val text = message.content
                        .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
                        .joinToString(" ") { it.text }

                    if (text.isBlank()) {
                        log.debug { "Skipping empty USER message" }
                        null
                    } else {
                        val instructionsPrefix = message.instructions
                            .joinToString("\n") { it.toXmlLine() }

                        val fullText = if (instructionsPrefix.isNotEmpty()) {
                            "$instructionsPrefix\n$text"
                        } else {
                            text
                        }

                        log.debug { "User message with ${message.instructions.size} instructions, full text length: ${fullText.length}" }

                        UserMessage(fullText)
                    }
                }
            }

            Conversation.Message.Role.ASSISTANT -> {
                val thinkingBlocks = message.content.filterIsInstance<Conversation.Message.ContentItem.Thinking>()
                val toolCalls = message.content.filterIsInstance<Conversation.Message.ContentItem.ToolCall>()
                val toolResults = message.content.filterIsInstance<Conversation.Message.ContentItem.ToolResult>()
                val text = message.content
                    .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
                    .joinToString(" ") { it.structured.fullText }

                when {
                    // Thinking blocks (must be separate messages per Anthropic requirements)
                    thinkingBlocks.isNotEmpty() -> {
                        val thinking = thinkingBlocks.first()
                        log.debug { "Converting thinking block: signature=${thinking.signature?.take(20)}..., text length=${thinking.thinking.length}" }
                        val props = mutableMapOf<String, Any>("thinking" to true)
                        thinking.signature?.let { props["signature"] = it }
                        AssistantMessage.builder()
                            .content(thinking.thinking)
                            .properties(props)
                            .build()
                    }

                    // Tool results message
                    toolResults.isNotEmpty() -> {
                        log.debug { "Converting ${toolResults.size} tool results to ToolResponseMessage" }
                        buildToolResponseMessage(toolResults)
                    }

                    // Assistant message with tool calls
                    toolCalls.isNotEmpty() -> {
                        log.debug { "Converting ${toolCalls.size} tool calls to AssistantMessage" }
                        AssistantMessage.builder()
                            .content(text.ifBlank { "" })
                            .properties(emptyMap())
                            .toolCalls(toolCalls.map { tc ->
                                AssistantMessage.ToolCall(
                                    tc.id.value,
                                    "function",
                                    tc.call.name,
                                    tc.call.input.toString()
                                )
                            })
                            .build()
                    }

                    // Regular assistant message
                    text.isNotBlank() -> {
                        AssistantMessage(text)
                    }

                    else -> {
                        log.debug { "Skipping empty ASSISTANT message" }
                        null
                    }
                }
            }

            Conversation.Message.Role.SYSTEM -> {
                log.debug { "Skipping SYSTEM message (UI/logging only)" }
                null
            }
        }
    }

    private fun buildToolResponseMessage(
        toolResults: List<Conversation.Message.ContentItem.ToolResult>,
    ): ToolResponseMessage {
        return ToolResponseMessage.builder()
            .responses(toolResults.map { tr ->
                val resultText = tr.result
                    .filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.Text>()
                    .joinToString(" ") { it.content }

                ToolResponseMessage.ToolResponse(
                    tr.toolUseId.value,
                    tr.toolName,
                    resultText
                )
            })
            .metadata(emptyMap())
            .build()
    }

    /**
     * Convert a list of Conversation messages to Spring AI format.
     *
     * Filters out null results (e.g., empty messages, SYSTEM messages).
     */
    fun convertHistoryToSpringAI(messages: List<Conversation.Message>): List<Message> {
        return messages.mapNotNull { toSpringAI(it) }
    }
}
