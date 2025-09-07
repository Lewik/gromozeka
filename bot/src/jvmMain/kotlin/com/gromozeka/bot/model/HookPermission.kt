package com.gromozeka.bot.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Official Claude Code CLI hook payload format
 * Sent via stdin as JSON according to Anthropic documentation
 */
@Serializable
data class ClaudeHookPayload(
    val session_id: String,
    val hook_event_name: String,
    val tool_name: String,
    val tool_input: JsonObject,
    val transcript_path: String? = null,
    val cwd: String? = null,
    val permission_mode: String? = null  // e.g., "acceptEdits"
) {
//    fun isPreToolUse(): Boolean = hook_event_name == "PreToolUse"
//    fun isPostToolUse(): Boolean = hook_event_name == "PostToolUse"
}

/**
 * Claude Code CLI hook response format
 * According to Anthropic documentation: {"decision": "approve|block", "reason": "..."}
 */
@Serializable
data class ClaudeHookResponse(
    val decision: String,
    val reason: String? = null
)

/**
 * Internal decision model for service logic
 * Converts to ClaudeHookResponse for external API
 */
data class HookDecision(
    val allow: Boolean,
    val reason: String = ""
) {
    fun toClaudeResponse(): ClaudeHookResponse {
        return if (allow) {
            ClaudeHookResponse(decision = "approve")
        } else {
            ClaudeHookResponse(decision = "block", reason = reason)
        }
    }
}