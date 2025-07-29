package com.gromozeka.bot.services

import com.gromozeka.bot.model.*
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*

/**
 * Maps Claude Code session entries to ChatMessage model
 */
object ClaudeCodeSessionMapper {
    
    /**
     * Convert Claude Code session message to ChatMessage
     * @return ChatMessage or null if entry is not a user/assistant message
     */
    fun ClaudeCodeSessionEntryV1_0.Message.toChatMessage(): ChatMessage? {
        // Only process user and assistant messages
        if (type !in listOf("user", "assistant")) return null
        
        val role = when (message.role) {
            "user" -> ChatMessage.Role.USER
            "assistant" -> ChatMessage.Role.ASSISTANT
            else -> return null
        }
        
        val contents = parseMessageContent(message.content)
        
        return ChatMessage(
            id = uuid,
            role = role,
            content = contents,
            timestamp = Instant.parse(timestamp),
            metadataType = ChatMessage.MetadataType.NONE
        )
    }
    
    /**
     * Parse different content formats from Claude Code messages
     */
    private fun parseMessageContent(content: JsonElement?): List<ChatMessageContent> {
        if (content == null) return emptyList()
        
        return when (content) {
            // Simple string content (user messages)
            is JsonPrimitive -> {
                if (content.isString) {
                    listOf(
                        ChatMessageContent(
                            content = content.content,
                            type = ChatMessageContent.Type.TEXT,
                            annotationsJson = ""
                        )
                    )
                } else {
                    emptyList()
                }
            }
            
            // Array content (assistant messages with text/tool_use)
            is JsonArray -> {
                content.mapNotNull { element ->
                    parseContentItem(element)
                }
            }
            
            // Object content (shouldn't happen in normal messages)
            is JsonObject -> {
                // Try to parse as single content item
                listOfNotNull(parseContentItem(content))
            }
            
            else -> emptyList()
        }
    }
    
    /**
     * Parse individual content item (text, tool_use, tool_result)
     */
    private fun parseContentItem(element: JsonElement): ChatMessageContent? {
        if (element !is JsonObject) return null
        
        val type = element["type"]?.jsonPrimitive?.content ?: return null
        
        return when (type) {
            "text" -> {
                val text = element["text"]?.jsonPrimitive?.content ?: return null
                ChatMessageContent(
                    content = text,
                    type = ChatMessageContent.Type.TEXT,
                    annotationsJson = ""
                )
            }
            
            "tool_use" -> {
                val name = element["name"]?.jsonPrimitive?.content ?: "unknown"
                val toolId = element["id"]?.jsonPrimitive?.content ?: ""
                val input = element["input"]?.toString() ?: "{}"
                
                ChatMessageContent(
                    content = "🛠️ Tool: $name\nID: $toolId\nInput: $input",
                    type = ChatMessageContent.Type.TEXT,
                    annotationsJson = ""
                )
            }
            
            "tool_result" -> {
                val toolUseId = element["tool_use_id"]?.jsonPrimitive?.content ?: ""
                val contentElement = element["content"]
                val contentStr = when (contentElement) {
                    is JsonPrimitive -> contentElement.content
                    is JsonArray -> contentElement.toString()
                    is JsonObject -> contentElement.toString()
                    null -> "No content"
                    else -> contentElement.toString()
                }
                
                ChatMessageContent(
                    content = "📊 Tool Result (ID: $toolUseId):\n$contentStr",
                    type = ChatMessageContent.Type.TEXT,
                    annotationsJson = ""
                )
            }
            
            else -> null
        }
    }
    
    /**
     * Convert a list of session entries to ChatMessages
     * Filters out non-message entries (like summaries)
     */
    fun List<ClaudeCodeSessionEntryV1_0>.toChatMessages(): List<ChatMessage> {
        return this.mapNotNull { entry ->
            (entry as? ClaudeCodeSessionEntryV1_0.Message)?.toChatMessage()
        }
    }
    
    /**
     * Load and convert a Claude Code session file to ChatMessages
     */
    suspend fun loadSessionAsChatMessages(
        sessionFile: java.io.File,
        parser: ClaudeCodeSessionParser = ClaudeCodeSessionParser()
    ): List<ChatMessage> {
        val entries = parser.parseSessionFile(sessionFile)
        return entries.toChatMessages()
    }
}