package com.gromozeka.bot.model

import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Data classes for parsing Claude Code session files (version 1.0.61)
 * Uses strict parsing - any extra or missing fields will cause serialization errors
 */
sealed class ClaudeCodeSessionEntryV1_0 {

    @Serializable
    @SerialName("summary")
    data class Summary(
        val type: String,
        val summary: String,
        val leafUuid: String,
    ) : ClaudeCodeSessionEntryV1_0()

    @Serializable
    data class Message(
        val type: String,
        val uuid: String,
        val parentUuid: String?,
        val isSidechain: Boolean,
        val userType: String,
        val cwd: String,
        val sessionId: ClaudeSessionUuid,
        val version: String,
        val gitBranch: String,
        val message: MessageContent,
        val timestamp: String,
        val requestId: String? = null,
        val toolUseResult: JsonElement? = null,
        val isCompactSummary: Boolean? = null,
        val isMeta: Boolean? = null,
    ) : ClaudeCodeSessionEntryV1_0()
}

@Serializable
data class MessageContent(
    val role: String,
    val content: JsonElement? = null,
    val id: String? = null,
    val type: String? = null,
    val model: String? = null,
    @SerialName("stop_reason")
    val stopReason: String? = null,
    @SerialName("stop_sequence")
    val stopSequence: String? = null,
    val usage: Usage? = null,
)

@Serializable
data class ContentItem(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null,
    @SerialName("tool_use_id")
    val toolUseId: String? = null,
    @SerialName("is_error")
    val isError: Boolean? = null,
)

@Serializable
data class Usage(
    @SerialName("input_tokens")
    val inputTokens: Int,
    @SerialName("cache_creation_input_tokens")
    val cacheCreationInputTokens: Int,
    @SerialName("cache_read_input_tokens")
    val cacheReadInputTokens: Int,
    @SerialName("output_tokens")
    val outputTokens: Int,
    @SerialName("service_tier")
    val serviceTier: String,
)

@Serializable
data class ToolUseResult(
    val type: String? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val interrupted: Boolean? = null,
    val isImage: Boolean? = null,
    val file: FileResult? = null,
    val mode: String? = null,
    val filenames: List<String>? = null,
    val numFiles: Int? = null,
)

@Serializable
data class FileResult(
    val filePath: String,
    val content: String,
    val numLines: Int,
    val startLine: Int,
    val totalLines: Int,
)