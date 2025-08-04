package com.gromozeka.shared.domain.message

import kotlinx.datetime.Instant
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Internal message representation for gromozeka.
 * Maps from various LLM-specific formats (ClaudeLogEntry, etc) to this unified structure.
 */
@Serializable
data class ChatMessage(
    val uuid: String, //todo real uuid type
    val parentUuid: String? = null, //todo real uuid type
    val messageType: MessageType,
    val content: List<ContentItem>,
    val timestamp: Instant,
    val llmSpecificMetadata: LlmSpecificMetadata?,
    
    // Development context at root level
    val gitBranch: String? = null,
    val cwd: String? = null,
) {

    @Serializable
    enum class MessageType {
        USER, ASSISTANT, SYSTEM, TOOL
    }
    
    /**
     * LLM-specific metadata, extensible for different engines
     */
    @Serializable
    @JsonClassDiscriminator("llm")
    sealed class LlmSpecificMetadata {
        
        @Serializable
        data class ClaudeCodeSessionFileEntry(
            val sessionId: String? = null,
            val requestId: String? = null,
            val model: String? = null,
            val usage: UsageInfo? = null,
            val stopReason: String? = null,
            val version: String? = null,
            val userType: String? = null,
            val isSidechain: Boolean = false,
            val isCompactSummary: Boolean = false,
            val isMeta: Boolean = false
        ) : LlmSpecificMetadata() {
            
            @Serializable
            data class UsageInfo(
                val inputTokens: Int,
                val outputTokens: Int,
                val cacheCreationTokens: Int? = null,
                val cacheReadTokens: Int? = null
            )
        }
        
        // Future: OpenAI, Anthropic API, DeepSeek, etc.
    }
    
    /**
     * Content item hierarchy with support for nested content
     */
    @Serializable
    @JsonClassDiscriminator("type")
    sealed class ContentItem {
        
        @Serializable
        data class Message(
            val text: String,
            val structured: StructuredText? = null
        ) : ContentItem() {
            
            @Serializable
            data class StructuredText(
                val fullText: String,
                val ttsText: String? = null,
                val voiceTone: String? = null
            )
        }
        
        @Serializable
        data class ToolCall(
            val id: String,
            val call: ToolCallData
        ) : ContentItem()
        
        @Serializable
        data class ToolResult(
            val toolUseId: String,
            val result: ToolResultData,
            val isError: Boolean = false
        ) : ContentItem()
        
        @Serializable
        data class Thinking(
            val signature: String,
            val thinking: String
        ) : ContentItem()
        
        @Serializable
        data class Media(
            val mimeType: String,
            val data: String, // base64 or URL
            val caption: String? = null
        ) : ContentItem()
        
        @Serializable
        data class System(
            val level: SystemLevel,
            val content: String,
            val toolUseId: String? = null
        ) : ContentItem() {
            
            @Serializable
            enum class SystemLevel {
                INFO,
                WARNING,
                ERROR
            }
        }
        
        @Serializable
        data class GromozekaMessage(
            val fullText: String,
            val ttsText: String? = null,
            val voiceTone: String? = null
        ) : ContentItem()
        
        @Serializable
        data class UnknownJson(
            val json: JsonElement
        ) : ContentItem()
    }
}

