package com.gromozeka.bot.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * MCP (Model Context Protocol) compatible tool result types
 * Based on research from Claude Code SDK and TypeScript MCP SDK
 */

@Serializable
data class McpToolResult(
    val content: List<McpContent>,
    val isError: Boolean? = null,
)

@Serializable
sealed class McpContent {
    abstract val type: String
}

@Serializable
@SerialName("text")
data class TextContent(
    val text: String,
) : McpContent() {
    override val type: String = "text"
}

@Serializable
@SerialName("image")
data class ImageContent(
    val data: String, // base64
    val mimeType: String,
) : McpContent() {
    override val type: String = "image"
}

@Serializable
@SerialName("audio")
data class AudioContent(
    val data: String, // base64
    val mimeType: String,
) : McpContent() {
    override val type: String = "audio"
}

@Serializable
@SerialName("resource")
data class EmbeddedResourceContent(
    val resource: EmbeddedResource,
) : McpContent() {
    override val type: String = "resource"
}

@Serializable
data class EmbeddedResource(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
)

/**
 * Helper to try parsing raw toolUseResult as MCP-compatible structure
 */
object McpToolResultParser {

    /**
     * Try to parse raw toolUseResult JSON as MCP structure
     * Returns null if it doesn't match MCP schema
     */
    fun parseAsMcp(json: JsonElement?): McpToolResult? {
        if (json == null) return null

        return try {
            // TODO: Implement actual JSON parsing logic
            // This is a placeholder for the demo
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Try to convert common toolUseResult patterns to MCP format
     */
    fun convertToMcp(json: JsonElement?): McpToolResult? {
        if (json == null) return null

        return try {
            // Detect common patterns and convert to MCP format
            when {
                // Command execution result with stdout/stderr
                hasFields(json, "stdout", "stderr") -> {
                    val stdout = getStringField(json, "stdout") ?: ""
                    val stderr = getStringField(json, "stderr") ?: ""
                    val isError = stderr.isNotEmpty()

                    val content = buildList {
                        if (stdout.isNotEmpty()) {
                            add(TextContent(text = "Output:\n$stdout"))
                        }
                        if (stderr.isNotEmpty()) {
                            add(TextContent(text = "Error:\n$stderr"))
                        }
                    }

                    McpToolResult(content = content, isError = isError)
                }

                // File content result
                hasFields(json, "content", "filePath") -> {
                    val content = getStringField(json, "content") ?: ""
                    val filePath = getStringField(json, "filePath") ?: ""

                    McpToolResult(
                        content = listOf(
                            TextContent(text = "File: $filePath\n\n$content")
                        )
                    )
                }

                // Simple string result
                json.toString().let { it.startsWith("\"") && it.endsWith("\"") } -> {
                    val text = json.toString().removeSurrounding("\"")
                    McpToolResult(
                        content = listOf(TextContent(text = text))
                    )
                }

                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun hasFields(json: JsonElement, vararg fields: String): Boolean {
        // TODO: Implement JSON field checking
        val jsonStr = json.toString()
        return fields.all { field -> jsonStr.contains("\"$field\"") }
    }

    private fun getStringField(json: JsonElement, field: String): String? {
        // TODO: Implement proper JSON field extraction
        // This is a simple regex-based approach for demo
        val jsonStr = json.toString()
        val regex = "\"$field\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        return regex.find(jsonStr)?.groupValues?.get(1)
    }
}