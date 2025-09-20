package com.gromozeka.bot.services

import com.gromozeka.bot.model.ClaudeCodeStreamJsonLine
import com.gromozeka.bot.model.ContentBlock
import com.gromozeka.bot.model.ContentItemsUnion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

/**
 * Sanitizer for Claude Code stream messages to prevent logging of sensitive content while preserving
 * diagnostic information useful for debugging and monitoring.
 * 
 * This class handles sanitization of different message types:
 * - System messages: Anonymizes paths and prevents data logging
 * - User messages: Replaces user content with placeholders
 * - Assistant messages: Preserves structure but replaces content and tool arguments with placeholders
 * - Result messages: Replaces result content with placeholders but keeps metadata
 * - Control messages: Left as-is (typically safe)
 */
@Service
class StreamMessageSanitizer {
    
    /**
     * Sanitize a parsed stream message by removing sensitive content while preserving
     * message structure and diagnostic information.
     * 
     * @param streamMessage The parsed Claude Code stream message to sanitize
     * @return JSON string of the sanitized message
     */
    fun sanitize(streamMessage: ClaudeCodeStreamJsonLine): String = when (streamMessage) {
        is ClaudeCodeStreamJsonLine.System -> sanitizeSystemMessage(streamMessage)
        is ClaudeCodeStreamJsonLine.User -> sanitizeUserMessage(streamMessage)
        is ClaudeCodeStreamJsonLine.Assistant -> sanitizeAssistantMessage(streamMessage)
        is ClaudeCodeStreamJsonLine.Result -> sanitizeResultMessage(streamMessage)
        is ClaudeCodeStreamJsonLine.ControlRequest,
        is ClaudeCodeStreamJsonLine.ControlResponse -> sanitizeControlMessage(streamMessage)
    }.let { Json.encodeToString(it) }
    
    /**
     * Create a placeholder JSON for unparseable lines to maintain log structure.
     * 
     * @param originalLineLength Length of the original line that failed to parse
     * @return JSON string representing a parse error placeholder
     */
    fun createParseErrorPlaceholder(originalLineLength: Int): String {
        return """{"type":"parse_error","error":"Failed to parse stream message","timestamp":"${System.currentTimeMillis()}","line_length":$originalLineLength}"""
    }
    
    private fun sanitizeSystemMessage(message: ClaudeCodeStreamJsonLine.System): ClaudeCodeStreamJsonLine.System {
        return message.copy(
            cwd = message.cwd?.let { anonymizePath(it) },
            data = if (message.data != null) buildJsonObject {
                put("sanitized", "[PLACEHOLDER - System data not logged]")
            } else null
        )
    }
    
    private fun sanitizeUserMessage(message: ClaudeCodeStreamJsonLine.User): ClaudeCodeStreamJsonLine.User {
        return message.copy(
            message = message.message.copy(
                content = ContentItemsUnion.StringContent("[PLACEHOLDER - User message content not logged]")
            )
        )
    }
    
    private fun sanitizeAssistantMessage(message: ClaudeCodeStreamJsonLine.Assistant): ClaudeCodeStreamJsonLine.Assistant {
        val sanitizedContent = message.message.content.map { contentBlock ->
            when (contentBlock) {
                is ContentBlock.TextBlock -> contentBlock.copy(
                    text = "[PLACEHOLDER - Assistant text not logged]"
                )
                is ContentBlock.ToolUseBlock -> contentBlock.copy(
                    input = JsonPrimitive("[PLACEHOLDER - Tool arguments not logged]")
                )
                is ContentBlock.ThinkingItem -> contentBlock.copy(
                    thinking = "[PLACEHOLDER - Thinking content not logged]"
                )
                else -> contentBlock // Keep other blocks as-is
            }
        }
        
        return message.copy(
            message = message.message.copy(content = sanitizedContent)
        )
    }
    
    private fun sanitizeResultMessage(message: ClaudeCodeStreamJsonLine.Result): ClaudeCodeStreamJsonLine.Result {
        return message.copy(
            result = message.result?.let { "[PLACEHOLDER - Result content not logged]" }
        )
    }
    
    private fun sanitizeControlMessage(message: ClaudeCodeStreamJsonLine): ClaudeCodeStreamJsonLine {
        // Control messages are typically safe (no user content), return as-is
        return message
    }
    
    /**
     * Anonymize file system paths by replacing user-specific information with generic placeholders.
     * 
     * Replaces:
     * - User home directory with $HOME
     * - /Users/username with /Users/USER
     * - /home/username with /home/USER  
     * - Windows \Users\username with \Users\USER
     */
    private fun anonymizePath(path: String): String = path
        .replace(System.getProperty("user.home"), "\$HOME")
        .replace(Regex("/Users/[^/]+"), "/Users/USER")
        .replace(Regex("\\\\Users\\\\[^\\\\]+"), "\\Users\\USER")
        .replace(Regex("/home/[^/]+"), "/home/USER")
}