package com.gromozeka.bot.model

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class StreamMessage {
    abstract val type: String

    @Serializable
    @SerialName("system")
    data class SystemStreamMessage(
        val subtype: String,
        @SerialName("session_id")
        val sessionId: String? = null,
        val cwd: String? = null,
        val tools: List<String>? = null,
        @SerialName("mcp_servers")
        val mcpServers: List<String>? = null,
        val model: String? = null,
        val permissionMode: String? = null,
        @SerialName("slash_commands")
        val slashCommands: List<String>? = null,
        val apiKeySource: String? = null,
        val data: JsonObject? = null,  // Fallback for other fields
        override val type: String = "system"
    ) : StreamMessage()

    @Serializable
    @SerialName("user")
    data class UserStreamMessage(
        val message: StreamMessageContent,
        @SerialName("session_id")
        val sessionId: String,
        @SerialName("parent_tool_use_id")
        val parentToolUseId: String? = null,
        override val type: String = "user"
    ) : StreamMessage()

    @Serializable
    @SerialName("assistant")
    data class AssistantStreamMessage(
        val message: StreamMessageContent,
        @SerialName("session_id")
        val sessionId: String,
        @SerialName("parent_tool_use_id")
        val parentToolUseId: String? = null,
        override val type: String = "assistant"
    ) : StreamMessage()

    @Serializable
    @SerialName("result")
    data class ResultStreamMessage(
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
        val sessionId: String,
        @SerialName("total_cost_usd")
        val totalCostUsd: Double? = null,
        val usage: UsageInfo? = null,
        val result: String? = null,
        override val type: String = "result"
    ) : StreamMessage()

    @Serializable
    @SerialName("control_request")
    data class ControlRequestMessage(
        @SerialName("request_id")
        val requestId: String,
        val request: ControlRequest,
        override val type: String = "control_request"
    ) : StreamMessage()

    @Serializable
    @SerialName("control_response")
    data class ControlResponseMessage(
        val response: ControlResponse,
        override val type: String = "control_response"
    ) : StreamMessage()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("role")
sealed class StreamMessageContent {
    abstract val role: String

    @Serializable
    @SerialName("user")
    data class UserContent(
        val content: ContentItemsUnion,
        override val role: String = "user"
    ) : StreamMessageContent()

    @Serializable
    @SerialName("assistant")
    data class AssistantContent(
        val id: String? = null,
        val type: String? = null, // Claude adds "type": "message" field
        val model: String? = null,
        val content: List<StreamContentItem>,
        @SerialName("stop_reason")
        val stopReason: String? = null,
        @SerialName("stop_sequence")
        val stopSequence: String? = null,
        val usage: UsageInfo? = null,
        override val role: String = "assistant"
    ) : StreamMessageContent()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class StreamContentItem {
    abstract val type: String

    @Serializable
    @SerialName("text")
    data class TextItem(
        val text: String,
        override val type: String = "text"
    ) : StreamContentItem()

    @Serializable
    @SerialName("tool_use")
    data class ToolUseItem(
        val id: String,
        val name: String,
        val input: JsonElement,
        override val type: String = "tool_use"
    ) : StreamContentItem()

    @Serializable
    @SerialName("tool_result")
    data class ToolResultItem(
        @SerialName("tool_use_id")
        val toolUseId: String,
        val content: ContentResultUnion? = null,
        @SerialName("is_error")
        val isError: Boolean? = null,
        override val type: String = "tool_result"
    ) : StreamContentItem()

    @Serializable
    @SerialName("thinking")
    data class ThinkingItem(
        val thinking: String,
        val signature: String? = null,
        override val type: String = "thinking"
    ) : StreamContentItem()
}

@Serializable(with = ContentItemsUnionSerializer::class)
sealed class ContentItemsUnion {
    @Serializable
    data class StringContent(val content: String) : ContentItemsUnion()

    @Serializable
    data class ArrayContent(val items: List<StreamContentItem>) : ContentItemsUnion()
}

@Serializable(with = ContentResultUnionSerializer::class)
sealed class ContentResultUnion {
    @Serializable
    data class StringResult(val content: String) : ContentResultUnion()

    @Serializable
    data class ArrayResult(val items: List<StreamContentItem>) : ContentResultUnion()
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
    @SerialName("server_tool_use")
    val serverToolUse: JsonObject? = null, // Contains web_search_requests, etc.
    @SerialName("service_tier")
    val serviceTier: String? = null
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
                    Json.decodeFromJsonElement<StreamContentItem>(it)
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
                    ListSerializer(StreamContentItem.serializer()),
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
                    Json.decodeFromJsonElement<StreamContentItem>(it)
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
                    ListSerializer(StreamContentItem.serializer()),
                    value.items
                )
            }
        }
    }
}

@Serializable
data class ControlRequest(
    val subtype: String // "interrupt", "cancel", etc.
)

@Serializable
data class ControlResponse(
    @SerialName("request_id")
    val requestId: String,
    val subtype: String, // "success", "error", etc.
    val error: String? = null
)