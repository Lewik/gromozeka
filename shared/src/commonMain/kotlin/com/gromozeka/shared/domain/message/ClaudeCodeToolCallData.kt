package com.gromozeka.shared.domain.message

import kotlinx.serialization.*
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
        val filePath: String,
        val offset: Int? = null,
        val limit: Int? = null
    ) : ClaudeCodeToolCallData()
    
    /**
     * File system edit operation
     */
    @Serializable
    data class Edit(
        val filePath: String,
        val oldString: String,
        val newString: String,
        val replaceAll: Boolean = false
    ) : ClaudeCodeToolCallData()
    
    /**
     * Bash command execution
     */
    @Serializable
    data class Bash(
        val command: String,
        val description: String? = null,
        val timeout: Int? = null
    ) : ClaudeCodeToolCallData()
    
    /**
     * Grep search operation
     */
    @Serializable
    data class Grep(
        val pattern: String,
        val path: String? = null,
        val glob: String? = null,
        val outputMode: String? = null,
        val caseInsensitive: Boolean? = null,
        val multiline: Boolean? = null
    ) : ClaudeCodeToolCallData()
    
    /**
     * TodoWrite operation
     */
    @Serializable
    data class TodoWrite(
        val todos: JsonElement // Complex structure, keep as JSON
    ) : ClaudeCodeToolCallData()
    
    /**
     * WebSearch operation
     */
    @Serializable
    data class WebSearch(
        val query: String,
        val allowedDomains: List<String>? = null,
        val blockedDomains: List<String>? = null
    ) : ClaudeCodeToolCallData()
    
    /**
     * WebFetch operation
     */
    @Serializable
    data class WebFetch(
        val url: String,
        val prompt: String
    ) : ClaudeCodeToolCallData()
    
    /**
     * Subagent operation
     */
    @Serializable
    data class Subagent(
        val description: String,
        val prompt: String,
        val subagentType: String
    ) : ClaudeCodeToolCallData()
}