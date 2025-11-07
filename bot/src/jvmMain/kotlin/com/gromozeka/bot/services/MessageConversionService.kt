package com.gromozeka.bot.services

import com.gromozeka.shared.domain.Conversation
import klog.KLoggers
import kotlinx.serialization.json.Json
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.time.Clock

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
    private val json = Json { ignoreUnknownKeys = true }

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
                val text = message.content
                    .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
                    .joinToString(" ") { it.text }

                if (text.isBlank()) {
                    log.debug { "Skipping empty USER message" }
                    null
                } else {
                    // Restore instruction serialization logic (was in ClaudeChatToStreamConverter before Spring AI migration)
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
                        log.debug { "Converting thinking block: signature=${thinking.signature.take(20)}..., text length=${thinking.thinking.length}" }
                        AssistantMessage.builder()
                            .content(thinking.thinking)
                            .properties(mapOf(
                                "thinking" to true,
                                "signature" to thinking.signature
                            ))
                            .build()
                    }

                    // Tool results message
                    toolResults.isNotEmpty() -> {
                        log.debug { "Converting ${toolResults.size} tool results to ToolResponseMessage" }
                        ToolResponseMessage.builder()
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

    /**
     * Convert Spring AI AssistantMessage to Conversation.Message.
     *
     * Handles both text content, tool calls, and thinking blocks.
     */
    fun fromSpringAI(message: AssistantMessage): Conversation.Message {
        val content = mutableListOf<Conversation.Message.ContentItem>()

        // Check if this is a thinking block (thinking blocks come as separate AssistantMessage with metadata)
        val isThinking = message.metadata["thinking"] as? Boolean ?: false

        if (isThinking) {
            // This is a thinking block, not regular text
            val signature = message.metadata["signature"] as? String ?: ""
            val thinkingText = message.text ?: ""

            log.debug { "Converting thinking block: signature='$signature', text length=${thinkingText.length}" }

            content.add(
                Conversation.Message.ContentItem.Thinking(
                    signature = signature,
                    thinking = thinkingText
                )
            )
        } else {
            // Regular assistant message processing

            // Add text content if present
            val text = message.text
            val hasToolCalls = !message.toolCalls.isNullOrEmpty()

            if (!text.isNullOrBlank()) {
                log.debug { "Assistant message text: '${text.take(100)}' (${text.length} chars, hasToolCalls=$hasToolCalls)" }
                content.add(
                    Conversation.Message.ContentItem.AssistantMessage(
                        structured = Conversation.Message.StructuredText(
                            fullText = text
                        )
                    )
                )
            }

            // Add tool calls if present
            message.toolCalls?.forEach { toolCall ->
                log.debug { "Converting tool call: ${toolCall.name()} (${toolCall.id()})" }
                content.add(
                    Conversation.Message.ContentItem.ToolCall(
                        id = Conversation.Message.ContentItem.ToolCall.Id(toolCall.id()),
                        call = Conversation.Message.ContentItem.ToolCall.Data(
                            name = toolCall.name(),
                            input = json.parseToJsonElement(toolCall.arguments())
                        )
                    )
                )
            }
        }

        return Conversation.Message(
            id = Conversation.Message.Id(UUID.randomUUID().toString()),
            conversationId = Conversation.Id(""), // Will be set by caller
            createdAt = Clock.System.now(),
            role = Conversation.Message.Role.ASSISTANT,
            content = content
        )
    }

    /**
     * Convert Spring AI ToolResponseMessage to Conversation.Message.
     *
     * Tool results are stored as ASSISTANT role messages with ToolResult content items.
     */
    fun fromSpringAI(message: ToolResponseMessage): Conversation.Message {
        log.debug { "Converting ${message.responses.size} tool responses" }

        val content = message.responses.map { response ->
            Conversation.Message.ContentItem.ToolResult(
                toolUseId = Conversation.Message.ContentItem.ToolCall.Id(response.id()),
                toolName = response.name(),
                result = listOf(
                    Conversation.Message.ContentItem.ToolResult.Data.Text(
                        content = response.responseData()
                    )
                ),
                isError = false
            )
        }

        return Conversation.Message(
            id = Conversation.Message.Id(UUID.randomUUID().toString()),
            conversationId = Conversation.Id(""), // Will be set by caller
            createdAt = Clock.System.now(),
            role = Conversation.Message.Role.ASSISTANT,
            content = content
        )
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
