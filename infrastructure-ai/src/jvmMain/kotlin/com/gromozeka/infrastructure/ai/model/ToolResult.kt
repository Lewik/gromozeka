package com.gromozeka.infrastructure.ai.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.JsonNode

/**
 * Represents tool execution results in Claude Code session files.
 *
 * Based on analysis of real session files, supporting different result formats
 * for different tool types with proper error handling.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "toolType",
    visible = true,
    defaultImpl = ToolResult.GenericResult::class
)
@JsonSubTypes(
    JsonSubTypes.Type(value = ToolResult.BashResult::class, name = "Bash"),
    JsonSubTypes.Type(value = ToolResult.FileResult::class, name = "File"),
    JsonSubTypes.Type(value = ToolResult.SearchResult::class, name = "Search"),
    JsonSubTypes.Type(value = ToolResult.WebResult::class, name = "Web"),
    JsonSubTypes.Type(value = ToolResult.McpResult::class, name = "MCP")
)
sealed class ToolResult {
    abstract val tool_use_id: String
    abstract val is_error: Boolean
    abstract val content: String?

    /**
     * Bash command execution result
     * Contains stdout, stderr, exit info
     */
    data class BashResult(
        override val tool_use_id: String,
        override val is_error: Boolean,
        override val content: String?,
        val stdout: String,
        val stderr: String,
        val interrupted: Boolean = false,
        val isImage: Boolean = false,
    ) : ToolResult() {
        val toolType: String = "Bash"
    }

    /**
     * File operation results (Read, Edit, Write, MultiEdit)
     * Contains file content or operation status
     */
    data class FileResult(
        override val tool_use_id: String,
        override val is_error: Boolean,
        override val content: String?,
        val file_path: String? = null,
        val operation: String? = null, // "read" | "edit" | "write" | "multiedit"
    ) : ToolResult() {
        val toolType: String = "File"
    }

    /**
     * Search operation results (Grep, Glob, LS)
     * Contains search results in various formats
     */
    data class SearchResult(
        override val tool_use_id: String,
        override val is_error: Boolean,
        override val content: String?,
        val matches_count: Int? = null,
        val files_found: List<String>? = null,
        val search_type: String? = null, // "grep" | "glob" | "ls"
    ) : ToolResult() {
        val toolType: String = "Search"
    }

    /**
     * Web-related tool results (WebFetch, WebSearch)
     * Contains web content or search results
     */
    data class WebResult(
        override val tool_use_id: String,
        override val is_error: Boolean,
        override val content: String?,
        val url: String? = null,
        val results_count: Int? = null,
        val operation: String? = null, // "fetch" | "search"
    ) : ToolResult() {
        val toolType: String = "Web"
    }

    /**
     * MCP (Model Context Protocol) tool results
     * For tools like mcp__ide__getDiagnostics, mcp__voice-mode__converse
     */
    data class McpResult(
        override val tool_use_id: String,
        override val is_error: Boolean,
        override val content: String?,
        val mcp_server: String? = null,
        val mcp_method: String? = null,
        val data: JsonNode? = null,
    ) : ToolResult() {
        val toolType: String = "MCP"
    }

    /**
     * Generic fallback for unknown or complex tool results
     * Stores raw result data as JsonNode for flexibility
     */
    data class GenericResult(
        override val tool_use_id: String,
        override val is_error: Boolean,
        override val content: String?,
        val raw_data: JsonNode? = null,
    ) : ToolResult() {
        val toolType: String = "Generic"
    }
}

/**
 * Utility functions for working with tool results
 */
object ToolResultUtils {

    /**
     * Creates appropriate ToolResult based on tool name and result data
     */
    fun createFromToolName(
        toolName: String,
        toolUseId: String,
        isError: Boolean,
        content: String?,
        additionalData: Map<String, Any>? = null,
    ): ToolResult {
        return when (toolName) {
            "Bash" -> {
                val stdout = additionalData?.get("stdout") as? String ?: ""
                val stderr = additionalData?.get("stderr") as? String ?: ""
                val interrupted = additionalData?.get("interrupted") as? Boolean ?: false
                val isImage = additionalData?.get("isImage") as? Boolean ?: false

                ToolResult.BashResult(
                    tool_use_id = toolUseId,
                    is_error = isError,
                    content = content,
                    stdout = stdout,
                    stderr = stderr,
                    interrupted = interrupted,
                    isImage = isImage
                )
            }

            "Edit", "Read", "Write", "MultiEdit" -> {
                ToolResult.FileResult(
                    tool_use_id = toolUseId,
                    is_error = isError,
                    content = content,
                    file_path = additionalData?.get("file_path") as? String,
                    operation = toolName.lowercase()
                )
            }

            "Grep", "Glob", "LS" -> {
                ToolResult.SearchResult(
                    tool_use_id = toolUseId,
                    is_error = isError,
                    content = content,
                    matches_count = additionalData?.get("matches_count") as? Int,
                    files_found = additionalData?.get("files_found") as? List<String>,
                    search_type = toolName.lowercase()
                )
            }

            "WebFetch", "WebSearch" -> {
                ToolResult.WebResult(
                    tool_use_id = toolUseId,
                    is_error = isError,
                    content = content,
                    url = additionalData?.get("url") as? String,
                    results_count = additionalData?.get("results_count") as? Int,
                    operation = if (toolName == "WebFetch") "fetch" else "search"
                )
            }

            else -> {
                if (toolName.startsWith("mcp__")) {
                    val parts = toolName.split("__")
                    ToolResult.McpResult(
                        tool_use_id = toolUseId,
                        is_error = isError,
                        content = content,
                        mcp_server = parts.getOrNull(1),
                        mcp_method = parts.getOrNull(2)
                    )
                } else {
                    ToolResult.GenericResult(
                        tool_use_id = toolUseId,
                        is_error = isError,
                        content = content
                    )
                }
            }
        }
    }
}