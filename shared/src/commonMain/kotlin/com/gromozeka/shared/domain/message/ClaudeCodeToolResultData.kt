package com.gromozeka.shared.domain.message

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Claude Code specific tool results
 */
@Serializable
sealed class ClaudeCodeToolResultData : ToolResultData() {

    /**
     * File system read result
     */
    @Serializable
    data class Read(
        val content: String, // File content with line numbers
        val isEmpty: Boolean = false,
    ) : ClaudeCodeToolResultData()

    /**
     * File system edit result
     */
    @Serializable
    data class Edit(
        val success: Boolean,
        val message: String? = null,
    ) : ClaudeCodeToolResultData()

    /**
     * Bash execution result
     */
    @Serializable
    data class Bash(
        val stdout: String? = null,
        val stderr: String? = null,
        val interrupted: Boolean = false,
        val isImage: Boolean = false,
    ) : ClaudeCodeToolResultData()

    /**
     * Grep search result
     */
    @Serializable
    data class Grep(
        val matches: List<String>? = null,
        val content: String? = null,
        val count: Int? = null,
    ) : ClaudeCodeToolResultData()

    /**
     * TodoWrite result
     */
    @Serializable
    data class TodoWrite(
        val success: Boolean,
        val message: String? = null,
    ) : ClaudeCodeToolResultData()

    /**
     * WebSearch result
     */
    @Serializable
    data class WebSearch(
        val results: JsonElement, // Complex search results structure
    ) : ClaudeCodeToolResultData()

    /**
     * WebFetch result
     */
    @Serializable
    data class WebFetch(
        val content: String,
        val redirectUrl: String? = null,
    ) : ClaudeCodeToolResultData()

    /**
     * Subagent result
     */
    @Serializable
    data class Subagent(
        val response: String,
        val success: Boolean = true,
    ) : ClaudeCodeToolResultData()

    /**
     * Null result - when tool returns no content
     */
    @Serializable
    data object NullResult : ClaudeCodeToolResultData()
}