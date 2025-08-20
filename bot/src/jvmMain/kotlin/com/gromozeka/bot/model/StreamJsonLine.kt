package com.gromozeka.bot.model

import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable
@JsonClassDiscriminator("type")
sealed class StreamJsonLine {
    abstract val type: String

    @Serializable
    @SerialName("system")
    data class System(
        val subtype: String,
        @SerialName("session_id")
        val sessionId: ClaudeSessionUuid? = null,
        val cwd: String? = null,
        val tools: List<String>? = null,
        @SerialName("mcp_servers")
        val mcpServers: List<McpServerInfo>? = null,
        val model: String? = null,
        val permissionMode: String? = null,
        @SerialName("permission_denials")
        val permissionDenials: JsonElement? = null,
        @SerialName("slash_commands")
        val slashCommands: List<String>? = null,
        val apiKeySource: String? = null,
        @SerialName("output_style")
        val outputStyle: String? = null,
        val data: JsonObject? = null,  // Fallback for other fields
        override val type: String = "system",
    ) : StreamJsonLine()

    @Serializable
    @SerialName("user")
    data class User(
        val message: StreamMessageContent.User,
        @SerialName("session_id")
        val sessionId: ClaudeSessionUuid,
        @SerialName("parent_tool_use_id")
        val parentToolUseId: String? = null,
        override val type: String = "user",
    ) : StreamJsonLine()

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        val message: StreamMessageContent.Assistant,
        @SerialName("session_id")
        val sessionId: ClaudeSessionUuid,
        @SerialName("parent_tool_use_id")
        val parentToolUseId: String? = null,
        override val type: String = "assistant",
    ) : StreamJsonLine()

    @Serializable
    @SerialName("result")
    data class Result(
        val subtype: String,
        @SerialName("duration_ms")
        val durationMs: Int,
        @SerialName("duration_api_ms")
        val durationApiMs: Int,
        @SerialName("is_error")
        val isError: Boolean,
        @SerialName("num_turns")
        val numTurns: Int,
        @SerialName("session_id")
        val sessionId: ClaudeSessionUuid,
        @SerialName("total_cost_usd")
        val totalCostUsd: Double? = null,
        val usage: UsageInfo? = null,
        val result: String? = null,
        @SerialName("permission_denials")
        val permissionDenials: JsonElement? = null,
        override val type: String = "result",
    ) : StreamJsonLine()

    @Serializable
    @SerialName("control_request")
    data class ControlRequest(
        @SerialName("request_id")
        val requestId: String,
        val request: com.gromozeka.bot.model.ControlRequest,
        override val type: String = "control_request",
    ) : StreamJsonLine()

    @Serializable
    @SerialName("control_response")
    data class ControlResponse(
        val response: com.gromozeka.bot.model.ControlResponse,
        override val type: String = "control_response",
    ) : StreamJsonLine()
}

@Serializable
@JsonClassDiscriminator("role")
sealed class StreamMessageContent {
    abstract val role: String

    @Serializable
    @SerialName("user")
    data class User(
        val content: ContentItemsUnion,
        override val role: String = "user",
    ) : StreamMessageContent()

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        val id: String? = null,
        val type: String? = null, // Claude adds "type": "message" field
        val model: String? = null,
        val content: List<ContentBlock>,
        @SerialName("stop_reason")
        val stopReason: String? = null,
        @SerialName("stop_sequence")
        val stopSequence: String? = null,
        val usage: UsageInfo? = null,
        override val role: String = "assistant",
    ) : StreamMessageContent()
}

@Serializable
@JsonClassDiscriminator("type")
sealed class ContentBlock {
    abstract val type: String

    @Serializable
    @SerialName("text")
    data class TextBlock(
        val text: String,
        override val type: String = "text",
    ) : ContentBlock()

    @Serializable
    @SerialName("tool_use")
    data class ToolUseBlock(
        val id: String,
        val name: String,
        val input: JsonElement,
        override val type: String = "tool_use",
    ) : ContentBlock()

    @Serializable
    @SerialName("tool_result")
    data class ToolResultBlock(
        @SerialName("tool_use_id")
        val toolUseId: String,
        val content: ContentResultUnion? = null,
        @SerialName("is_error")
        val isError: Boolean? = null,
        override val type: String = "tool_result",
    ) : ContentBlock()

    @Serializable
    @SerialName("thinking")
    data class ThinkingItem(
        val thinking: String,
        val signature: String? = null,
        override val type: String = "thinking",
    ) : ContentBlock()

    @Serializable
    @SerialName("image")
    data class ImageBlock(
        val source: ImageSource,
        override val type: String = "image",
    ) : ContentBlock()
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

@Serializable(with = ContentItemsUnionSerializer::class)
sealed class ContentItemsUnion {
    @Serializable
    data class StringContent(val content: String) : ContentItemsUnion()

    @Serializable
    data class ArrayContent(val items: List<ContentBlock>) : ContentItemsUnion()
}

@Serializable(with = ContentResultUnionSerializer::class)
sealed class ContentResultUnion {
    @Serializable
    data class StringResult(val content: String) : ContentResultUnion()

    @Serializable
    data class ArrayResult(val items: List<ContentBlock>) : ContentResultUnion()
}

@Serializable
data class UsageInfo(
    @SerialName("input_tokens")
    val inputTokens: Int,
    @SerialName("output_tokens")
    val outputTokens: Int,
    @SerialName("cache_creation_input_tokens")
    val cacheCreationInputTokens: Int? = null,
    @SerialName("cache_read_input_tokens")
    val cacheReadInputTokens: Int? = null,
    @SerialName("cache_creation")
    val cacheCreation: CacheCreationInfo? = null,
    @SerialName("server_tool_use")
    val serverToolUse: JsonObject? = null, // Contains web_search_requests, etc.
    @SerialName("service_tier")
    val serviceTier: String? = null,
)

@Serializable
data class CacheCreationInfo(
    @SerialName("ephemeral_5m_input_tokens")
    val ephemeral5mInputTokens: Int? = null,
    @SerialName("ephemeral_1h_input_tokens")
    val ephemeral1hInputTokens: Int? = null,
)

@Serializable
data class McpServerInfo(
    val name: String,
    val status: String,
)

object ContentItemsUnionSerializer : KSerializer<ContentItemsUnion> {
    override val descriptor = buildClassSerialDescriptor("ContentItemsUnion")

    override fun deserialize(decoder: Decoder): ContentItemsUnion {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())

        return when {
            element is JsonPrimitive && element.isString ->
                ContentItemsUnion.StringContent(element.content)

            element is JsonArray -> {
                val items = element.map {
                    Json.decodeFromJsonElement<ContentBlock>(it)
                }
                ContentItemsUnion.ArrayContent(items)
            }

            else -> throw SerializationException("Invalid ContentItemsUnion format: expected string or array")
        }
    }

    override fun serialize(encoder: Encoder, value: ContentItemsUnion) {
        when (value) {
            is ContentItemsUnion.StringContent -> {
                encoder.encodeString(value.content)
            }

            is ContentItemsUnion.ArrayContent -> {
                encoder.encodeSerializableValue(
                    ListSerializer(ContentBlock.serializer()),
                    value.items
                )
            }
        }
    }
}

object ContentResultUnionSerializer : KSerializer<ContentResultUnion> {
    override val descriptor = buildClassSerialDescriptor("ContentResultUnion")

    override fun deserialize(decoder: Decoder): ContentResultUnion {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())

        return when {
            element is JsonPrimitive && element.isString ->
                ContentResultUnion.StringResult(element.content)

            element is JsonArray -> {
                val items = element.map {
                    Json.decodeFromJsonElement<ContentBlock>(it)
                }
                ContentResultUnion.ArrayResult(items)
            }

            else -> throw SerializationException("Invalid ContentResultUnion format: expected string or array")
        }
    }

    override fun serialize(encoder: Encoder, value: ContentResultUnion) {
        when (value) {
            is ContentResultUnion.StringResult -> {
                encoder.encodeString(value.content)
            }

            is ContentResultUnion.ArrayResult -> {
                encoder.encodeSerializableValue(
                    ListSerializer(ContentBlock.serializer()),
                    value.items
                )
            }
        }
    }
}

@Serializable
data class ControlRequest(
    val subtype: String, // "interrupt", "cancel", etc.
)

@Serializable
data class ControlResponse(
    @SerialName("request_id")
    val requestId: String,
    val subtype: String, // "success", "error", etc.
    val error: String? = null,
)

/**
 * Wrapper for StreamMessage that preserves original JSON for debugging purposes.
 * Allows passing both parsed StreamMessage and raw JSON through the processing pipeline.
 */
data class StreamJsonLinePacket(
    val streamMessage: StreamJsonLine,
    val originalJson: String?,
)