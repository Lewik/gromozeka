package com.gromozeka.bot

import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

/**
 * Test structures for message content deserialization
 */

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class TestMessageContent {
    abstract val type: String

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        override val type: String = "text",
    ) : TestMessageContent()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonElement,
        override val type: String = "tool_use",
    ) : TestMessageContent()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val tool_use_id: String,
        val content: JsonElement? = null,
        val is_error: Boolean? = null,
        override val type: String = "tool_result",
    ) : TestMessageContent()

    @Serializable
    @SerialName("thinking")
    data class Thinking(
        val thinking: String,
        val signature: String,
        override val type: String = "thinking",
    ) : TestMessageContent()
}

@Serializable
data class TestMessage(
    val id: String? = null,
    val role: String,
    val content: JsonElement, // Keep as JsonElement for now
    val model: String? = null,
    val stop_reason: String? = null,
    val stop_sequence: String? = null,
    val usage: JsonElement? = null,
)

@Serializable
data class TestLogEntry(
    val type: String,
    val timestamp: String,
    val uuid: String,
    val sessionId: ClaudeSessionUuid? = null,
    val message: TestMessage? = null,
    val toolUseResult: JsonElement? = null,
)