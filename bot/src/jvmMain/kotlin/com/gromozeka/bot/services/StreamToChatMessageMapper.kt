package com.gromozeka.bot.services

import com.gromozeka.bot.model.*
import com.gromozeka.shared.domain.message.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.util.*

/**
 * Maps StreamMessage to ChatMessage for UI consumption
 */
object StreamToChatMessageMapper {
    
    fun mapToChatMessage(streamMessage: StreamMessage): ChatMessage {
        return when (streamMessage) {
            is StreamMessage.SystemStreamMessage -> mapSystemMessage(streamMessage)
            is StreamMessage.UserStreamMessage -> mapUserMessage(streamMessage)
            is StreamMessage.AssistantStreamMessage -> mapAssistantMessage(streamMessage)
            is StreamMessage.ResultStreamMessage -> mapResultMessage(streamMessage)
        }
    }
    
    private fun mapSystemMessage(message: StreamMessage.SystemStreamMessage): ChatMessage {
        val level = when (message.subtype) {
            "error" -> ChatMessage.ContentItem.System.SystemLevel.ERROR
            "warning" -> ChatMessage.ContentItem.System.SystemLevel.WARNING
            else -> ChatMessage.ContentItem.System.SystemLevel.INFO
        }
        
        val content = listOf(
            ChatMessage.ContentItem.System(
                level = level,
                content = "${message.subtype}: ${message.data}",
                toolUseId = null
            )
        )
        
        return ChatMessage(
            uuid = generateUuid(),
            parentUuid = null,
            messageType = ChatMessage.MessageType.SYSTEM,
            content = content,
            timestamp = Clock.System.now(),
            llmSpecificMetadata = createStreamMetadata(
                sessionId = message.sessionId ?: "unknown"
            )
        )
    }
    
    private fun mapUserMessage(message: StreamMessage.UserStreamMessage): ChatMessage {
        val content = mapStreamMessageContent(message.message)
        
        return ChatMessage(
            uuid = generateUuid(),
            parentUuid = null,
            messageType = ChatMessage.MessageType.USER,
            content = content,
            timestamp = Clock.System.now(),
            llmSpecificMetadata = createStreamMetadata(
                sessionId = message.sessionId
            )
        )
    }
    
    private fun mapAssistantMessage(message: StreamMessage.AssistantStreamMessage): ChatMessage {
        val assistantContent = message.message as StreamMessageContent.AssistantContent
        val contentItems = assistantContent.content.flatMap { item ->
            mapStreamContentItem(item)
        }
        
        return ChatMessage(
            uuid = generateUuid(),
            parentUuid = null,
            messageType = ChatMessage.MessageType.ASSISTANT,
            content = contentItems,
            timestamp = Clock.System.now(),
            llmSpecificMetadata = createStreamMetadata(
                sessionId = message.sessionId,
                model = assistantContent.model,
                usage = assistantContent.usage,
                stopReason = assistantContent.stopReason
            )
        )
    }
    
    private fun mapResultMessage(message: StreamMessage.ResultStreamMessage): ChatMessage {
        val level = if (message.isError) {
            ChatMessage.ContentItem.System.SystemLevel.ERROR
        } else {
            ChatMessage.ContentItem.System.SystemLevel.INFO
        }
        
        val contentText = buildString {
            append("Result (${message.subtype}): ")
            if (message.result != null) {
                append(message.result)
            } else {
                append("${message.numTurns} turns, ")
                append("${message.durationMs}ms total, ")
                message.totalCostUsd?.let { append("$%.6f, ".format(it)) }
                message.usage?.let { 
                    append("${it.inputTokens} in / ${it.outputTokens} out tokens")
                }
            }
        }
        
        val content = listOf(
            ChatMessage.ContentItem.System(
                level = level,
                content = contentText,
                toolUseId = null
            )
        )
        
        return ChatMessage(
            uuid = generateUuid(),
            parentUuid = null,
            messageType = ChatMessage.MessageType.SYSTEM,
            content = content,
            timestamp = Clock.System.now(),
            llmSpecificMetadata = createStreamMetadata(
                sessionId = message.sessionId,
                usage = message.usage
            )
        )
    }
    
    private fun mapStreamMessageContent(content: StreamMessageContent): List<ChatMessage.ContentItem> {
        return when (content) {
            is StreamMessageContent.UserContent -> {
                mapContentItemsUnion(content.content)
            }
            is StreamMessageContent.AssistantContent -> {
                content.content.flatMap { mapStreamContentItem(it) }
            }
        }
    }
    
    private fun mapContentItemsUnion(union: ContentItemsUnion): List<ChatMessage.ContentItem> {
        return when (union) {
            is ContentItemsUnion.StringContent -> {
                listOf(parseTextForGromozeka(union.content))
            }
            is ContentItemsUnion.ArrayContent -> {
                union.items.flatMap { mapStreamContentItem(it) }
            }
        }
    }
    
    private fun mapStreamContentItem(item: StreamContentItem): List<ChatMessage.ContentItem> {
        return when (item) {
            is StreamContentItem.TextItem -> {
                listOf(parseTextForGromozeka(item.text))
            }
            
            is StreamContentItem.ToolUseItem -> {
                val toolCall = mapToolCall(item.name, item.input)
                listOf(ChatMessage.ContentItem.ToolCall(item.id, toolCall))
            }
            
            is StreamContentItem.ToolResultItem -> {
                val result = mapToolResult(item.content)
                listOf(
                    ChatMessage.ContentItem.ToolResult(
                        toolUseId = item.toolUseId,
                        result = result,
                        isError = item.isError ?: false
                    )
                )
            }
            
            is StreamContentItem.ThinkingItem -> {
                listOf(
                    ChatMessage.ContentItem.Thinking(
                        signature = item.signature ?: "",
                        thinking = item.thinking
                    )
                )
            }
        }
    }
    
    private fun mapToolResult(content: ContentResultUnion?): ToolResultData {
        return when (content) {
            is ContentResultUnion.StringResult -> {
                ClaudeCodeToolResultData.Read(content.content)
            }
            is ContentResultUnion.ArrayResult -> {
                val textContent = content.items.joinToString("\n") { item ->
                    when (item) {
                        is StreamContentItem.TextItem -> item.text
                        else -> item.toString()
                    }
                }
                ClaudeCodeToolResultData.Read(textContent)
            }
            null -> {
                ClaudeCodeToolResultData.Read("")
            }
        }
    }
    
    private fun mapToolCall(name: String, input: JsonElement): ToolCallData {
        return try {
            // For polymorphic deserialization, we could add a type field to the input
            // But since ClaudeCodeToolCallData uses different field names per type,
            // and the discriminator comes from outside (name parameter),
            // it's cleaner to use explicit mapping
            when (name) {
                "Read" -> Json.decodeFromJsonElement<ClaudeCodeToolCallData.Read>(input)
                "Edit" -> Json.decodeFromJsonElement<ClaudeCodeToolCallData.Edit>(input)
                "Bash" -> Json.decodeFromJsonElement<ClaudeCodeToolCallData.Bash>(input)
                "Grep" -> Json.decodeFromJsonElement<ClaudeCodeToolCallData.Grep>(input)
                "TodoWrite" -> ClaudeCodeToolCallData.TodoWrite(input) // Keep as-is since it stores raw JSON
                "WebSearch" -> Json.decodeFromJsonElement<ClaudeCodeToolCallData.WebSearch>(input)
                "WebFetch" -> Json.decodeFromJsonElement<ClaudeCodeToolCallData.WebFetch>(input)
                "Task" -> Json.decodeFromJsonElement<ClaudeCodeToolCallData.Task>(input)
                else -> ToolCallData.Generic(name, input)
            }
        } catch (e: Exception) {
            // Fallback to generic if deserialization fails
            println("[StreamToChatMessageMapper] Failed to deserialize tool call '$name': ${e.message}")
            ToolCallData.Generic(name, input)
        }
    }
    
    private fun parseTextForGromozeka(text: String): ChatMessage.ContentItem {
        if (!text.trim().startsWith("{") || !text.trim().endsWith("}")) {
            return ChatMessage.ContentItem.Message(text)
        }
        
        return try {
            // Try to deserialize as GromozekaJson first
            val gromozekaData = Json.decodeFromString<GromozekaJson>(text)
            ChatMessage.ContentItem.GromozekaMessage(
                fullText = gromozekaData.fullText,
                ttsText = gromozekaData.ttsText,
                voiceTone = gromozekaData.voiceTone
            )
        } catch (e: SerializationException) {
            // Not a Gromozeka format, try as generic JSON
            try {
                val jsonElement = Json.parseToJsonElement(text)
                ChatMessage.ContentItem.UnknownJson(jsonElement)
            } catch (e: Exception) {
                // Not valid JSON at all
                ChatMessage.ContentItem.Message(text)
            }
        } catch (e: Exception) {
            // Any other error - treat as plain text
            ChatMessage.ContentItem.Message(text)
        }
    }
    
    private fun createStreamMetadata(
        sessionId: String,
        model: String? = null,
        usage: UsageInfo? = null,
        stopReason: String? = null
    ): ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry {
        return ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry(
            sessionId = sessionId,
            model = model,
            usage = usage?.let { mapUsageInfo(it) },
            stopReason = stopReason,
            version = null,
            userType = null,
            isSidechain = false,
            isCompactSummary = false,
            isMeta = false
        )
    }
    
    private fun mapUsageInfo(usage: UsageInfo): ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry.UsageInfo {
        return ChatMessage.LlmSpecificMetadata.ClaudeCodeSessionFileEntry.UsageInfo(
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cacheCreationTokens = usage.cacheCreationInputTokens,
            cacheReadTokens = usage.cacheReadInputTokens
        )
    }
    
    private fun generateUuid(): String {
        return UUID.randomUUID().toString()
    }
}