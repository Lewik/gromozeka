package com.gromozeka.shared.domain.message

import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import kotlin.time.Instant
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlin.time.ExperimentalTime

/**
 * Internal message representation for gromozeka.
 * Maps from various LLM-specific formats (ClaudeLogEntry, etc) to this unified structure.
 */
@OptIn(ExperimentalTime::class)
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
    
    // Message tags that were active when this message was sent
    val activeTags: List<MessageTagDefinition.Data> = emptyList(),
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
            val sessionId: ClaudeSessionUuid? = null,
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
            val result: List<Data>,
            val isError: Boolean = false
        ) : ContentItem() {

            @Serializable
            sealed class Data {
                
                @Serializable
                data class Text(val content: String) : Data()
                
                @Serializable
                data class Base64Data(
                    val data: String,
                    val mediaType: MediaType
                ) : Data()
                
                @Serializable
                data class UrlData(
                    val url: String,
                    val mediaType: MediaType? = null
                ) : Data()
                
                @Serializable
                data class FileData(
                    val fileId: String,
                    val mediaType: MediaType? = null
                ) : Data()
            }
        }
        
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
        data class ImageItem(
            val source: ImageSource
        ) : ContentItem()
        
        @Serializable
        data class UnknownJson(
            val json: JsonElement
        ) : ContentItem()
    }
    
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonClassDiscriminator("type")
    sealed class ImageSource {
        abstract val type: String

        @Serializable
        @SerialName("base64")
        data class Base64ImageSource(
            val data: String,
            @SerialName("media_type")
            val mediaType: String,
            override val type: String = "base64",
        ) : ImageSource()

        @Serializable
        @SerialName("url")
        data class UrlImageSource(
            val url: String,
            override val type: String = "url",
        ) : ImageSource()

        @Serializable
        @SerialName("file")
        data class FileImageSource(
            @SerialName("file_id")
            val fileId: String,
            override val type: String = "file",
        ) : ImageSource()
    }
    
    @Serializable
    data class MediaType(
        val type: String,      // "image", "text", "application", "audio", "video"
        val subtype: String    // "png", "plain", "json", "pdf", "mp4"
    ) {
        val value: String get() = "$type/$subtype"
        
        companion object {
            // Image types
            val IMAGE_PNG = MediaType("image", "png")
            val IMAGE_JPEG = MediaType("image", "jpeg")
            val IMAGE_GIF = MediaType("image", "gif")
            val IMAGE_WEBP = MediaType("image", "webp")
            val IMAGE_SVG = MediaType("image", "svg+xml")
            
            // Text types
            val TEXT_PLAIN = MediaType("text", "plain")
            val TEXT_HTML = MediaType("text", "html")
            val TEXT_CSS = MediaType("text", "css")
            val TEXT_JAVASCRIPT = MediaType("text", "javascript")
            
            // Application types
            val APPLICATION_JSON = MediaType("application", "json")
            val APPLICATION_PDF = MediaType("application", "pdf")
            val APPLICATION_ZIP = MediaType("application", "zip")
            val APPLICATION_OCTET_STREAM = MediaType("application", "octet-stream")
            
            // Audio types
            val AUDIO_MP3 = MediaType("audio", "mpeg")
            val AUDIO_WAV = MediaType("audio", "wav")
            val AUDIO_OGG = MediaType("audio", "ogg")
            
            // Video types
            val VIDEO_MP4 = MediaType("video", "mp4")
            val VIDEO_WEBM = MediaType("video", "webm")
            val VIDEO_AVI = MediaType("video", "x-msvideo")
            
            /**
             * Parse MediaType from string like "image/png"
             */
            fun parse(value: String): MediaType {
                val parts = value.split("/", limit = 2)
                return if (parts.size == 2) {
                    MediaType(parts[0], parts[1])
                } else {
                    MediaType("application", "octet-stream") // fallback
                }
            }
        }
    }
}

