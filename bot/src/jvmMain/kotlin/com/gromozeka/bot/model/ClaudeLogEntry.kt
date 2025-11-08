package com.gromozeka.bot.model

import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable
@JsonClassDiscriminator("type")
sealed class ClaudeLogEntry {
    abstract val type: String

    @Serializable
    @SerialName("summary")
    data class SummaryEntry(
        val leafUuid: String,
        val summary: String,
        override val type: String = "summary",
    ) : ClaudeLogEntry()

    @Serializable
    @SerialName("file-history-snapshot")
    data class FileHistorySnapshotEntry(
        val messageId: String,
        val snapshot: Snapshot,
        val isSnapshotUpdate: Boolean,
        override val type: String = "file-history-snapshot",
    ) : ClaudeLogEntry() {

        @Serializable
        data class Snapshot(
            val messageId: String,
            val trackedFileBackups: Map<String, FileBackup>,
            val timestamp: String,
        )

        @Serializable
        data class FileBackup(
            val backupFileName: String?,
            val version: Int,
            val backupTime: String,
        )
    }

    @Serializable
    @SerialName("queue-operation")
    data class QueueOperationEntry(
        val operation: String,
        val timestamp: String,
        val sessionId: String,
        override val type: String = "queue-operation",
    ) : ClaudeLogEntry()

    sealed class BaseEntry : ClaudeLogEntry() {
        abstract val cwd: String?
        abstract val gitBranch: String?
        abstract val sessionId: ClaudeSessionUuid?
        abstract val timestamp: String
        abstract val userType: String?
        abstract val uuid: String
        abstract val version: String?
        abstract val isSidechain: Boolean?
        abstract val parentUuid: String?
    }

    @Serializable
    @SerialName("user")
    data class UserEntry(
        override val cwd: String?,
        override val gitBranch: String?,
        override val sessionId: ClaudeSessionUuid?,
        override val timestamp: String,
        override val userType: String?,
        override val uuid: String,
        override val version: String?,
        override val isSidechain: Boolean?,
        override val parentUuid: String?,
        val isCompactSummary: Boolean? = null,
        val isMeta: Boolean? = null,
        val message: Message? = null,
        val toolUseResult: JsonElement? = null,
        override val type: String = "user",
    ) : BaseEntry()

    @Serializable
    @SerialName("assistant")
    data class AssistantEntry(
        override val cwd: String?,
        override val gitBranch: String?,
        override val sessionId: ClaudeSessionUuid?,
        override val timestamp: String,
        override val userType: String?,
        override val uuid: String,
        override val version: String?,
        override val isSidechain: Boolean?,
        override val parentUuid: String?,
        val requestId: String? = null,
        val message: Message? = null,
        val toolUseResult: JsonElement? = null,
        override val type: String = "assistant",
    ) : BaseEntry()

    @Serializable
    @SerialName("system")
    data class SystemEntry(
        override val cwd: String?,
        override val gitBranch: String?,
        override val sessionId: ClaudeSessionUuid?,
        override val timestamp: String,
        override val userType: String?,
        override val uuid: String,
        override val version: String?,
        override val isSidechain: Boolean?,
        override val parentUuid: String?,
        val content: String,
        val isMeta: Boolean,
        val level: String,
        @SerialName("toolUseID")
        val toolUseId: String? = null,
        override val type: String = "system",
    ) : BaseEntry()

    @Serializable
    @JsonClassDiscriminator("role")
    sealed class Message {
        abstract val role: String

        @Serializable
        @SerialName("user")
        data class UserMessage(
            val content: Content,
            override val role: String = "user",
        ) : Message() {

            @Serializable(with = UserMessageContentSerializer::class)
            sealed class Content {

                @Serializable
                data class StringContent(
                    val content: String,
                ) : Content()

                @Serializable
                data class ArrayContent(
                    val content: List<UserContentItem>,
                ) : Content()

                @Serializable
                data class GromozekaJsonContent(
                    val data: JsonObject,
                ) : Content()

                @Serializable
                data class UnknownJsonContent(
                    val json: JsonElement,
                ) : Content()
            }
        }

        @Serializable
        @SerialName("assistant")
        data class AssistantMessage(
            val id: String,
            val model: String,
            val content: List<AssistantContent>,
            @SerialName("stop_reason")
            val stopReason: String?,
            @SerialName("stop_sequence")
            val stopSequence: String?,
            val type: String,
            val usage: UsageInfo,
            override val role: String = "assistant",
        ) : Message()
    }


    @Serializable
    @JsonClassDiscriminator("type")
    sealed class UserContentItem {
        abstract val type: String

        @Serializable
        @SerialName("text")
        data class TextItem(
            val text: String,
            override val type: String = "text",
        ) : UserContentItem()

        @Serializable
        @SerialName("tool_result")
        data class ToolResultItem(
            val content: JsonElement, // Union: String | Array<ContentItem>
            @SerialName("tool_use_id")
            val toolUseId: String,
            override val type: String = "tool_result",
        ) : UserContentItem() {

            /**
             * Parse content as typed ToolResultContent based on JSON structure
             */
            fun getTypedContent(): ToolResultContent {
                return when {
                    // String content
                    content is JsonPrimitive && content.isString -> {
                        ToolResultContent.StringToolResult(content.content)
                    }

                    // Object with string content field
                    content is JsonObject && content["content"]?.jsonPrimitive?.isString == true -> {
                        ToolResultContent.StringToolResult(
                            content = content["content"]!!.jsonPrimitive.content,
                            isError = content["is_error"]?.jsonPrimitive?.booleanOrNull
                        )
                    }

                    // Array content
                    content is JsonArray -> {
                        val items = content.map {
                            Json.decodeFromJsonElement<UserContentItem>(it)
                        }
                        ToolResultContent.MixedContentToolResult(items)
                    }

                    // Object with array content field
                    content is JsonObject && content["content"] is JsonArray -> {
                        val contentArray = content["content"]!!.jsonArray
                        val items = contentArray.map {
                            Json.decodeFromJsonElement<UserContentItem>(it)
                        }
                        ToolResultContent.MixedContentToolResult(items)
                    }

                    else -> {
                        throw SerializationException("Unknown ToolResultContent format: $content")
                    }
                }
            }
        }

        @Serializable
        @SerialName("image")
        data class ImageItem(
            val source: ImageSource,
            override val type: String = "image",
        ) : UserContentItem()
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
            val mediaType: String, // "image/png", "image/jpeg", "image/gif", "image/webp"
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
    @JsonClassDiscriminator("type")
    sealed class ToolResultContent {
        abstract val type: String

        @Serializable
        @SerialName("string")
        data class StringToolResult(
            val content: String,
            @SerialName("is_error")
            val isError: Boolean? = null,
            override val type: String = "string",
        ) : ToolResultContent()

        @Serializable
        @SerialName("array")
        data class ArrayToolResult(
            val content: List<UserContentItem.TextItem>,
            override val type: String = "array",
        ) : ToolResultContent()

        @Serializable
        @SerialName("mixed_content")
        data class MixedContentToolResult(
            val content: List<UserContentItem>, // Can contain text, images, etc.
            override val type: String = "mixed_content",
        ) : ToolResultContent()
    }

    @Serializable
    @JsonClassDiscriminator("type")
    sealed class AssistantContent {
        abstract val type: String

        @Serializable
        @SerialName("text")
        data class TextContent(
            val text: String,
            override val type: String = "text",
        ) : AssistantContent()

        @Serializable
        @SerialName("tool_use")
        data class ToolUseContent(
            val id: String,
            val name: String,
            val input: JsonElement,
            override val type: String = "tool_use",
        ) : AssistantContent()

        @Serializable
        @SerialName("thinking")
        data class ThinkingContent(
            val signature: String,
            val thinking: String,
            override val type: String = "thinking",
        ) : AssistantContent()
    }

    @Serializable
    data class UsageInfo(
        @SerialName("cache_creation_input_tokens")
        val cacheCreationInputTokens: Int? = null,
        @SerialName("cache_read_input_tokens")
        val cacheReadInputTokens: Int? = null,
        @SerialName("input_tokens")
        val inputTokens: Int,
        @SerialName("output_tokens")
        val outputTokens: Int,
        @SerialName("service_tier")
        val serviceTier: String? = null,
    )
}

object UserMessageContentSerializer : KSerializer<ClaudeLogEntry.Message.UserMessage.Content> {
    override val descriptor = buildClassSerialDescriptor("UserMessage.Content")

    override fun deserialize(decoder: Decoder): ClaudeLogEntry.Message.UserMessage.Content {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())

        return when {
            // 1. Quick check - string?
            element is JsonPrimitive && element.isString -> {
                ClaudeLogEntry.Message.UserMessage.Content.StringContent(element.content)
            }

            // 2. Object with type discriminator (Claude format)
            element is JsonObject && element.containsKey("type") -> {
                when (element["type"]?.jsonPrimitive?.content) {
                    "string" -> {
                        val content = element["content"]?.jsonPrimitive?.content ?: ""
                        ClaudeLogEntry.Message.UserMessage.Content.StringContent(content)
                    }

                    "array" -> {
                        val content = element["content"]?.jsonArray?.map {
                            Json.decodeFromJsonElement<ClaudeLogEntry.UserContentItem>(it)
                        } ?: emptyList()
                        ClaudeLogEntry.Message.UserMessage.Content.ArrayContent(content)
                    }

                    else -> ClaudeLogEntry.Message.UserMessage.Content.UnknownJsonContent(element)
                }
            }

            // 3. Object without type - try to deserialize into Gromozeka format
            element is JsonObject -> {
                try {
                    // Try to parse as valid StructuredText JSON
                    parseAsStructuredText(element)
                    ClaudeLogEntry.Message.UserMessage.Content.GromozekaJsonContent(element)
                } catch (e: Exception) {
                    // If failed - fallback
                    ClaudeLogEntry.Message.UserMessage.Content.UnknownJsonContent(element)
                }
            }

            // 4. Array - try as Claude array
            element is JsonArray -> {
                try {
                    val content = element.map {
                        Json.decodeFromJsonElement<ClaudeLogEntry.UserContentItem>(it)
                    }
                    ClaudeLogEntry.Message.UserMessage.Content.ArrayContent(content)
                } catch (e: Exception) {
                    ClaudeLogEntry.Message.UserMessage.Content.UnknownJsonContent(element)
                }
            }

            // 5. Fallback
            else -> ClaudeLogEntry.Message.UserMessage.Content.UnknownJsonContent(element)
        }
    }

    override fun serialize(encoder: Encoder, value: ClaudeLogEntry.Message.UserMessage.Content) {
        when (value) {
            is ClaudeLogEntry.Message.UserMessage.Content.StringContent -> {
                encoder.encodeString(value.content)
            }

            is ClaudeLogEntry.Message.UserMessage.Content.ArrayContent -> {
                encoder.encodeSerializableValue(
                    ListSerializer(ClaudeLogEntry.UserContentItem.serializer()),
                    value.content
                )
            }

            is ClaudeLogEntry.Message.UserMessage.Content.GromozekaJsonContent -> {
                encoder.encodeSerializableValue(JsonObject.serializer(), value.data)
            }

            is ClaudeLogEntry.Message.UserMessage.Content.UnknownJsonContent -> {
                encoder.encodeSerializableValue(JsonElement.serializer(), value.json)
            }
        }
    }

    private fun parseAsStructuredText(obj: JsonObject) {
        // Check that required fullText field exists
        if (!obj.containsKey("fullText")) {
            throw IllegalArgumentException("Missing required fullText field")
        }
    }
}


