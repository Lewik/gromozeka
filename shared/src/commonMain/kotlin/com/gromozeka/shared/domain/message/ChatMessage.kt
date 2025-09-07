package com.gromozeka.shared.domain.message

import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant

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

    // Message metadata
    val instructions: List<Instruction> = emptyList(),

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
            val sessionId: ClaudeSessionUuid? = null,
            val requestId: String? = null,
            val model: String? = null,
            val usage: UsageInfo? = null,
            val stopReason: String? = null,
            val version: String? = null,
            val userType: String? = null,
            val isSidechain: Boolean = false,
            val isCompactSummary: Boolean = false,
            val isMeta: Boolean = false,
        ) : LlmSpecificMetadata() {

            @Serializable
            data class UsageInfo(
                val inputTokens: Int,
                val outputTokens: Int,
                val cacheCreationTokens: Int? = null,
                val cacheReadTokens: Int? = null,
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
        val failedToParse: Boolean = false,  // true if response parser failed and fell back to raw text
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
            val call: ToolCallData,
        ) : ContentItem()

        @Serializable
        data class ToolResult(
            val toolUseId: String,
            val result: List<Data>,
            val isError: Boolean = false,
        ) : ContentItem() {

            @Serializable
            sealed class Data {

                @Serializable
                data class Text(val content: String) : Data()

                @Serializable
                data class Base64Data(
                    val data: String,
                    val mediaType: MediaType,
                ) : Data()

                @Serializable
                data class UrlData(
                    val url: String,
                    val mediaType: MediaType? = null,
                ) : Data()

                @Serializable
                data class FileData(
                    val fileId: String,
                    val mediaType: MediaType? = null,
                ) : Data()
            }
        }

        @Serializable
        data class Thinking(
            val signature: String,
            val thinking: String,
        ) : ContentItem()

        @Serializable
        data class System(
            val level: SystemLevel,
            val content: String,
            val toolUseId: String? = null,
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
            val structured: StructuredText,
        ) : ContentItem()


        @Serializable
        data class ImageItem(
            val source: ImageSource,
        ) : ContentItem()

        @Serializable
        data class UnknownJson(
            val json: JsonElement,
        ) : ContentItem()
    }

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
        val subtype: String,    // "png", "plain", "json", "pdf", "mp4"
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

    /**
     * Instructions for message processing with XMLL serialization
     */
    @Serializable
    sealed class Instruction {
        abstract val title: String
        abstract val description: String
        abstract fun serializeContent(): String
        abstract fun toXmlLine(): String
        
        companion object {
            private data class InstructionParser(
                val tagName: String,
                val fromContent: (String) -> Instruction?
            )
            
            private val instructionParsers = listOf(
                InstructionParser(UserInstruction.TAG_NAME, UserInstruction::fromContent),
                InstructionParser(Source.TAG_NAME, Source::fromContent),
                InstructionParser(ResponseExpected.TAG_NAME, ResponseExpected::fromContent)
            )
            
            fun parseFromXmlLine(line: String): Instruction? {
                val trimmed = line.trim()
                
                for (parser in instructionParsers) {
                    val startTag = "<${parser.tagName}>"
                    val endTag = "</${parser.tagName}>"
                    
                    if (trimmed.startsWith(startTag) && trimmed.endsWith(endTag)) {
                        val content = trimmed.removePrefix(startTag).removeSuffix(endTag)
                        return parser.fromContent(content)
                    }
                }
                
                return null
            }
        }
        
        @Serializable
        data class UserInstruction(
            val id: String,        // "thinking_ultrathink", "mode_readonly"
            override val title: String,     // "Ultrathink", "Readonly" 
            override val description: String // "Режим глубокого анализа..."
        ) : Instruction() {
            override fun serializeContent() = "$id:$title:$description"
            override fun toXmlLine() = "<$TAG_NAME>${serializeContent()}</$TAG_NAME>"
            
            companion object {
                const val TAG_NAME = "user-instruction"
                
                fun fromContent(content: String): UserInstruction? {
                    val parts = content.split(":", limit = 3)
                    return if (parts.size == 3) {
                        UserInstruction(parts[0].trim(), parts[1].trim(), parts[2].trim())
                    } else null
                }
            }
        }
        
        @Serializable
        sealed class Source : Instruction() {
            override fun toXmlLine() = "<$TAG_NAME>${serializeContent()}</$TAG_NAME>"
            
            @Serializable
            @SerialName("user")
            object User : Source() {
                override val title = "User"
                override val description = "Message from user"
                override fun serializeContent() = "user"
            }
            
            @Serializable
            @SerialName("agent")
            data class Agent(val tabId: String) : Source() {
                override val title = "Agent"
                override val description = "Message from agent (Tab ID: $tabId)"
                override fun serializeContent() = "agent:$tabId"
            }
            
            companion object {
                const val TAG_NAME = "source"
                
                fun fromContent(content: String): Source? {
                    return when {
                        content == "user" -> User
                        content.startsWith("agent:") -> Agent(content.removePrefix("agent:"))
                        else -> null
                    }
                }
            }
        }
        
        @Serializable
        data class ResponseExpected(
            val targetTabId: String
        ) : Instruction() {
            override val title = "Response Expected"
            override val description = "This agent expects a response back to Tab ID: $targetTabId"
            override fun serializeContent() = "Use mcp__gromozeka__tell_agent with target_tab_id: $targetTabId"
            override fun toXmlLine() = "<$TAG_NAME>${serializeContent()}</$TAG_NAME>"
            
            companion object {
                const val TAG_NAME = "response-expected"
                
                fun fromContent(content: String): ResponseExpected? {
                    val match = Regex("target_tab_id: (.+)").find(content)
                    return if (match != null) {
                        ResponseExpected(match.groupValues[1])
                    } else null
                }
            }
        }
    }

}

