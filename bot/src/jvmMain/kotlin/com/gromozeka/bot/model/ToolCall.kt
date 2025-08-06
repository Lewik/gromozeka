package com.gromozeka.bot.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.JsonNode

/**
 * Represents a tool call in Claude Code session files.
 *
 * Based on analysis of real session files, supporting all observed tool types
 * with type-safe parameters for common tools and generic fallback for others.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "name",
    visible = true
)
@JsonSubTypes(
    JsonSubTypes.Type(value = ToolCall.EditTool::class, name = "Edit"),
    JsonSubTypes.Type(value = ToolCall.ReadTool::class, name = "Read"),
    JsonSubTypes.Type(value = ToolCall.BashTool::class, name = "Bash"),
    JsonSubTypes.Type(value = ToolCall.TodoWriteTool::class, name = "TodoWrite"),
    JsonSubTypes.Type(value = ToolCall.GrepTool::class, name = "Grep"),
    JsonSubTypes.Type(value = ToolCall.MultiEditTool::class, name = "MultiEdit"),
    JsonSubTypes.Type(value = ToolCall.WebFetchTool::class, name = "WebFetch"),
    JsonSubTypes.Type(value = ToolCall.WebSearchTool::class, name = "WebSearch"),
    JsonSubTypes.Type(value = ToolCall.TaskTool::class, name = "Task"),
    JsonSubTypes.Type(value = ToolCall.LSTool::class, name = "LS"),
    JsonSubTypes.Type(value = ToolCall.GlobTool::class, name = "Glob"),
    JsonSubTypes.Type(value = ToolCall.WriteTool::class, name = "Write"),
    JsonSubTypes.Type(
        value = ToolCall.GenericTool::class, names = [
            "mcp__ide__getDiagnostics",
            "mcp__voice-mode__converse"
        ]
    )
)
sealed class ToolCall {
    abstract val id: String
    abstract val name: String

    /**
     * File editing tool - most frequently used
     * Params: file_path, old_string, new_string, replace_all?
     */
    data class EditTool(
        override val id: String,
        override val name: String = "Edit",
        val file_path: String,
        val old_string: String,
        val new_string: String,
        val replace_all: Boolean = false,
    ) : ToolCall()

    /**
     * File reading tool
     * Params: file_path, offset?, limit?
     */
    data class ReadTool(
        override val id: String,
        override val name: String = "Read",
        val file_path: String,
        val offset: Int? = null,
        val limit: Int? = null,
    ) : ToolCall()

    /**
     * Bash command execution tool
     * Params: command, description?, timeout?
     */
    data class BashTool(
        override val id: String,
        override val name: String = "Bash",
        val command: String,
        val description: String? = null,
        val timeout: Long? = null,
    ) : ToolCall()

    /**
     * Todo list management tool
     * Params: todos[] (array of todo items)
     */
    data class TodoWriteTool(
        override val id: String,
        override val name: String = "TodoWrite",
        val todos: List<TodoItem>,
    ) : ToolCall() {
        data class TodoItem(
            val id: String,
            val content: String,
            val status: String, // "pending" | "in_progress" | "completed"
            val priority: String, // "high" | "medium" | "low"
        )
    }

    /**
     * Text search tool with many options
     * Params: pattern, path?, output_mode?, context options, filters
     */
    data class GrepTool(
        override val id: String,
        override val name: String = "Grep",
        val pattern: String,
        val path: String? = null,
        val output_mode: String? = null, // "content" | "files_with_matches" | "count"
        val contextBefore: Int? = null, // -B
        val contextAfter: Int? = null,  // -A
        val contextAround: Int? = null, // -C
        val caseInsensitive: Boolean? = null, // -i
        val lineNumbers: Boolean? = null, // -n
        val glob: String? = null,
        val type: String? = null,
        val head_limit: Int? = null,
        val multiline: Boolean? = null,
    ) : ToolCall()

    /**
     * Multiple file edits in one operation
     * Params: file_path, edits[]
     */
    data class MultiEditTool(
        override val id: String,
        override val name: String = "MultiEdit",
        val file_path: String,
        val edits: List<EditOperation>,
    ) : ToolCall() {
        data class EditOperation(
            val old_string: String,
            val new_string: String,
            val replace_all: Boolean = false,
        )
    }

    /**
     * Web content fetching with AI processing
     * Params: url, prompt
     */
    data class WebFetchTool(
        override val id: String,
        override val name: String = "WebFetch",
        val url: String,
        val prompt: String,
    ) : ToolCall()

    /**
     * Web search tool
     * Params: query, domain filters
     */
    data class WebSearchTool(
        override val id: String,
        override val name: String = "WebSearch",
        val query: String,
        val allowed_domains: List<String>? = null,
        val blocked_domains: List<String>? = null,
    ) : ToolCall()

    /**
     * Task execution tool
     * Params: description, prompt
     */
    data class TaskTool(
        override val id: String,
        override val name: String = "Task",
        val description: String,
        val prompt: String,
    ) : ToolCall()

    /**
     * Directory listing tool
     * Params: path, ignore?
     */
    data class LSTool(
        override val id: String,
        override val name: String = "LS",
        val path: String,
        val ignore: List<String>? = null,
    ) : ToolCall()

    /**
     * File pattern matching tool
     * Params: pattern, path?
     */
    data class GlobTool(
        override val id: String,
        override val name: String = "Glob",
        val pattern: String,
        val path: String? = null,
    ) : ToolCall()

    /**
     * File writing tool
     * Params: file_path, content
     */
    data class WriteTool(
        override val id: String,
        override val name: String = "Write",
        val file_path: String,
        val content: String,
    ) : ToolCall()

    /**
     * Generic fallback for MCP tools and unknown tools
     * Stores raw input parameters as JsonNode for flexibility
     */
    data class GenericTool(
        override val id: String,
        override val name: String,
        val input: JsonNode,
    ) : ToolCall()
}