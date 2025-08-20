package com.gromozeka.shared.domain.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Claude Code specific tool calls
 */
@Serializable
sealed class ClaudeCodeToolCallData : ToolCallData() {

    /**
     * File system read operation
     */
    @Serializable
    data class Read(
        @SerialName("file_path") val filePath: String,
        val offset: Int? = null,
        val limit: Int? = null,
    ) : ClaudeCodeToolCallData()

    /**
     * File system edit operation
     */
    @Serializable
    data class Edit(
        @SerialName("file_path") val filePath: String,
        @SerialName("old_string") val oldString: String,
        @SerialName("new_string") val newString: String,
        @SerialName("replace_all") val replaceAll: Boolean = false,
    ) : ClaudeCodeToolCallData()

    /**
     * Bash command execution
     */
    @Serializable
    data class Bash(
        val command: String,
        val description: String? = null,
        val timeout: Int? = null,
    ) : ClaudeCodeToolCallData()

    /**
     * Grep search operation
     */
    @Serializable
    data class Grep(
        val pattern: String,
        val path: String? = null,
        val glob: String? = null,
        @SerialName("output_mode") val outputMode: String? = null,
        @SerialName("-i") val caseInsensitive: Boolean? = null,
        val multiline: Boolean? = null,
    ) : ClaudeCodeToolCallData()

    /**
     * TodoWrite operation
     */
    @Serializable
    data class TodoWrite(
        val todos: JsonElement, // Complex structure, keep as JSON
    ) : ClaudeCodeToolCallData()

    /**
     * WebSearch operation
     */
    @Serializable
    data class WebSearch(
        val query: String,
        @SerialName("allowed_domains") val allowedDomains: List<String>? = null,
        @SerialName("blocked_domains") val blockedDomains: List<String>? = null,
    ) : ClaudeCodeToolCallData()

    /**
     * WebFetch operation
     */
    @Serializable
    data class WebFetch(
        val url: String,
        val prompt: String,
    ) : ClaudeCodeToolCallData()

    /**
     * Task operation (subagent)
     */
    @Serializable
    data class Task(
        val description: String,
        val prompt: String,
        @SerialName("subagent_type") val subagentType: String,
    ) : ClaudeCodeToolCallData()
}