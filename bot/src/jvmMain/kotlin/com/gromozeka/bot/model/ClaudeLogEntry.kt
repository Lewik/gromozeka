package com.gromozeka.bot.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@OptIn(ExperimentalSerializationApi::class)
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

    sealed class BaseEntry : ClaudeLogEntry() {
        abstract val cwd: String?
        abstract val gitBranch: String?
        abstract val sessionId: String?
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
        override val sessionId: String?,
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
        override val sessionId: String?,
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
        override val sessionId: String?,
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

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonClassDiscriminator("role")
    sealed class Message {
        abstract val role: String

        @Serializable
        @SerialName("user")
        data class UserMessage(
            val content: ClaudeMessageContent,
            override val role: String = "user",
        ) : Message()

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

    @Serializable(with = ClaudeMessageContentSerializer::class)
    sealed class ClaudeMessageContent {

        @Serializable
        data class StringContent(
            val content: String,
        ) : ClaudeMessageContent()

        @Serializable
        data class ArrayContent(
            val content: List<UserContentItem>,
        ) : ClaudeMessageContent()

        @Serializable
        data class GromozekaJsonContent(
            val data: JsonObject,
        ) : ClaudeMessageContent()

        @Serializable
        data class UnknownJsonContent(
            val json: JsonElement,
        ) : ClaudeMessageContent()
    }

    @OptIn(ExperimentalSerializationApi::class)
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
            val content: ToolResultContent,
            @SerialName("tool_use_id")
            val toolUseId: String,
            override val type: String = "tool_result",
        ) : UserContentItem()
    }

    @OptIn(ExperimentalSerializationApi::class)
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
    }

    @OptIn(ExperimentalSerializationApi::class)
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

@OptIn(ExperimentalSerializationApi::class)
object ClaudeMessageContentSerializer : KSerializer<ClaudeLogEntry.ClaudeMessageContent> {
    override val descriptor = buildClassSerialDescriptor("ClaudeMessageContent")

    override fun deserialize(decoder: Decoder): ClaudeLogEntry.ClaudeMessageContent {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())

        return when {
            // 1. Quick check - string?
            element is JsonPrimitive && element.isString -> {
                ClaudeLogEntry.ClaudeMessageContent.StringContent(element.content)
            }

            // 2. Object with type discriminator (Claude format)
            element is JsonObject && element.containsKey("type") -> {
                when (element["type"]?.jsonPrimitive?.content) {
                    "string" -> {
                        val content = element["content"]?.jsonPrimitive?.content ?: ""
                        ClaudeLogEntry.ClaudeMessageContent.StringContent(content)
                    }

                    "array" -> {
                        val content = element["content"]?.jsonArray?.map {
                            Json.decodeFromJsonElement<ClaudeLogEntry.UserContentItem>(it)
                        } ?: emptyList()
                        ClaudeLogEntry.ClaudeMessageContent.ArrayContent(content)
                    }

                    else -> ClaudeLogEntry.ClaudeMessageContent.UnknownJsonContent(element)
                }
            }

            // 3. Object without type - try to deserialize into Gromozeka format
            element is JsonObject -> {
                try {
                    // Try to parse as valid Gromozeka JSON
                    parseAsGromozekaJson(element)
                    ClaudeLogEntry.ClaudeMessageContent.GromozekaJsonContent(element)
                } catch (e: Exception) {
                    // If failed - fallback
                    ClaudeLogEntry.ClaudeMessageContent.UnknownJsonContent(element)
                }
            }

            // 4. Array - try as Claude array
            element is JsonArray -> {
                try {
                    val content = element.map {
                        Json.decodeFromJsonElement<ClaudeLogEntry.UserContentItem>(it)
                    }
                    ClaudeLogEntry.ClaudeMessageContent.ArrayContent(content)
                } catch (e: Exception) {
                    ClaudeLogEntry.ClaudeMessageContent.UnknownJsonContent(element)
                }
            }

            // 5. Fallback
            else -> ClaudeLogEntry.ClaudeMessageContent.UnknownJsonContent(element)
        }
    }

    override fun serialize(encoder: Encoder, value: ClaudeLogEntry.ClaudeMessageContent) {
        when (value) {
            is ClaudeLogEntry.ClaudeMessageContent.StringContent -> {
                encoder.encodeString(value.content)
            }

            is ClaudeLogEntry.ClaudeMessageContent.ArrayContent -> {
                encoder.encodeSerializableValue(
                    ListSerializer(ClaudeLogEntry.UserContentItem.serializer()),
                    value.content
                )
            }

            is ClaudeLogEntry.ClaudeMessageContent.GromozekaJsonContent -> {
                encoder.encodeSerializableValue(JsonObject.serializer(), value.data)
            }

            is ClaudeLogEntry.ClaudeMessageContent.UnknownJsonContent -> {
                encoder.encodeSerializableValue(JsonElement.serializer(), value.json)
            }
        }
    }

    private fun parseAsGromozekaJson(obj: JsonObject) {
        // Check that required fullText field exists
        if (!obj.containsKey("fullText")) {
            throw IllegalArgumentException("Missing required fullText field")
        }
    }
}

