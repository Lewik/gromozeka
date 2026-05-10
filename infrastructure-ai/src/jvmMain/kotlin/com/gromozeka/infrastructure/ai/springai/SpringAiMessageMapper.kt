package com.gromozeka.infrastructure.ai.springai

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiUsage
import klog.KLoggers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage
import org.springframework.stereotype.Component

@Component
class SpringAiMessageMapper {
    private val log = KLoggers.logger(this)

    fun toPromptMessages(systemPrompts: List<String>, messages: List<Conversation.Message>): List<Message> {
        return systemPrompts.map(::SystemMessage) + messages.mapNotNull(::toSpringAiMessage)
    }

    fun toRuntimeResponse(chatResponse: ChatResponse): AiRuntimeResponse {
        val messages = chatResponse.results.mapNotNull { generation ->
            val assistantMessage = generation.output
            val content = mutableListOf<Conversation.Message.ContentItem>()

            val signature = assistantMessage.metadata["signature"] as? String
            val isThinking = signature != null || assistantMessage.metadata.containsKey("thinking")

            if (isThinking) {
                content.add(
                    Conversation.Message.ContentItem.Thinking(
                        thinking = assistantMessage.text ?: "",
                        signature = signature,
                        state = Conversation.Message.BlockState.COMPLETE
                    )
                )
            } else if (!assistantMessage.text.isNullOrBlank()) {
                content.add(
                    Conversation.Message.ContentItem.AssistantMessage(
                        structured = Conversation.Message.StructuredText(
                            fullText = assistantMessage.text ?: ""
                        ),
                        state = Conversation.Message.BlockState.COMPLETE
                    )
                )
            }

            assistantMessage.toolCalls.forEach { toolCall ->
                val input = try {
                    Json.parseToJsonElement(toolCall.arguments())
                } catch (e: Exception) {
                    log.error(e) { "Failed to parse tool call arguments for ${toolCall.name()}: ${toolCall.arguments()}" }
                    JsonObject(
                        mapOf(
                            "error" to JsonPrimitive("Parse error: ${e.message}"),
                            "raw" to JsonPrimitive(toolCall.arguments())
                        )
                    )
                }

                content.add(
                    Conversation.Message.ContentItem.ToolCall(
                        id = Conversation.Message.ContentItem.ToolCall.Id(toolCall.id()),
                        call = Conversation.Message.ContentItem.ToolCall.Data(
                            name = toolCall.name(),
                            input = input
                        ),
                        state = Conversation.Message.BlockState.COMPLETE
                    )
                )
            }

            if (content.isEmpty()) {
                null
            } else {
                AiAssistantMessage(
                    content = content,
                    metadata = assistantMessage.metadata
                )
            }
        }

        return AiRuntimeResponse(
            messages = messages,
            usage = chatResponse.metadata.usage?.let(::toAiUsage),
            finishReason = chatResponse.results.firstOrNull()?.metadata?.finishReason,
            providerMetadata = mapOf(
                "generationCount" to chatResponse.results.size,
                "hasToolCalls" to chatResponse.hasToolCalls()
            )
        )
    }

    private fun toSpringAiMessage(message: Conversation.Message): Message? {
        return when (message.role) {
            Conversation.Message.Role.USER -> {
                val toolResults = message.content.filterIsInstance<Conversation.Message.ContentItem.ToolResult>()

                if (toolResults.isNotEmpty()) {
                    buildToolResponseMessage(toolResults)
                } else {
                    val text = message.content
                        .filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
                        .joinToString(" ") { it.text }

                    if (text.isBlank()) {
                        null
                    } else {
                        val instructionsPrefix = message.instructions.joinToString("\n") { it.toXmlLine() }
                        val fullText = if (instructionsPrefix.isNotEmpty()) {
                            "$instructionsPrefix\n$text"
                        } else {
                            text
                        }

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
                    thinkingBlocks.isNotEmpty() -> {
                        val thinking = thinkingBlocks.first()
                        val props = mutableMapOf<String, Any>("thinking" to true)
                        thinking.signature?.let { props["signature"] = it }
                        AssistantMessage.builder()
                            .content(thinking.thinking)
                            .properties(props)
                            .build()
                    }

                    toolResults.isNotEmpty() -> buildToolResponseMessage(toolResults)

                    toolCalls.isNotEmpty() -> {
                        AssistantMessage.builder()
                            .content(text.ifBlank { "" })
                            .properties(emptyMap())
                            .toolCalls(toolCalls.map { toolCall ->
                                AssistantMessage.ToolCall(
                                    toolCall.id.value,
                                    "function",
                                    toolCall.call.name,
                                    toolCall.call.input.toString()
                                )
                            })
                            .build()
                    }

                    text.isNotBlank() -> AssistantMessage(text)

                    else -> null
                }
            }

            Conversation.Message.Role.SYSTEM -> null
        }
    }

    private fun buildToolResponseMessage(
        toolResults: List<Conversation.Message.ContentItem.ToolResult>
    ): ToolResponseMessage {
        return ToolResponseMessage.builder()
            .responses(toolResults.map { toolResult ->
                val resultText = toolResult.result
                    .filterIsInstance<Conversation.Message.ContentItem.ToolResult.Data.Text>()
                    .joinToString(" ") { it.content }

                ToolResponseMessage.ToolResponse(
                    toolResult.toolUseId.value,
                    toolResult.toolName,
                    resultText
                )
            })
            .metadata(emptyMap())
            .build()
    }

    private fun toAiUsage(usage: org.springframework.ai.chat.metadata.Usage): AiUsage {
        val nativeUsage = usage.getNativeUsage()

        val thinkingTokens = when (nativeUsage) {
            is GoogleGenAiUsage -> nativeUsage.thoughtsTokenCount ?: 0
            else -> 0
        }

        return AiUsage(
            promptTokens = usage.promptTokens ?: 0,
            completionTokens = usage.completionTokens ?: 0,
            thinkingTokens = thinkingTokens,
            cacheCreationTokens = 0,
            cacheReadTokens = 0
        )
    }
}
