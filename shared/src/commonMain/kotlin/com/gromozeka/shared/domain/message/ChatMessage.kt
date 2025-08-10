package com.gromozeka.shared.domain.message

import kotlinx.datetime.Instant
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

/**
 * Internal message representation for gromozeka.
 * Maps from various LLM-specific formats (ClaudeLogEntry, etc) to this unified structure.
 */
@Serializable
data class ChatMessage(
    val uuid: String, //todo real uuid type
    val parentUuid: String? = null, //todo real uuid type
    val role: Role,
    val content: List<ContentItem>,
    val timestamp: Instant,
    val llmSpecificMetadata: LlmSpecificMetadata?,
    
    // Development context at root level
    val gitBranch: String? = null,
    val cwd: String? = null,
    
    // Indicates if this message is loaded from historical data (prevents TTS replay)
    val isHistorical: Boolean = false,
    
    // Original JSON from LLM stream for debugging (when showOriginalJson setting enabled)
    val originalJson: String? = null,
) {

    @Serializable
    enum class Role {
        USER, ASSISTANT, SYSTEM
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
     * Structured text data for TTS and voice synthesis
     */
    @Serializable
    data class StructuredText(
        val fullText: String,
        val ttsText: String? = null,
        val voiceTone: String? = null,
        val failedToParse: Boolean = false  // true if response parser failed and fell back to raw text
    )
    
    /**
     * Content item hierarchy with support for nested content
     */
    @Serializable
    @JsonClassDiscriminator("type")
    sealed class ContentItem {
        
        @Serializable
        @SerialName("Message")
        data class UserMessage(
            val text: String,
        ) : ContentItem()

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
        @SerialName("IntermediateMessage")
        data class AssistantMessage(
            val structured: StructuredText
        ) : ContentItem()
        
        
        
        @Serializable
        data class UnknownJson(
            val json: JsonElement
        ) : ContentItem()
    }
}

