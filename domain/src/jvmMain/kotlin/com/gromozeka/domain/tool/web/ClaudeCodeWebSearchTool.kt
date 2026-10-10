package com.gromozeka.domain.tool.web

import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolLoadingPolicy
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter

/** Wire values match the native Claude Code WebSearch schema. */
enum class ClaudeCodeWebSearchMode { standard, extended }

data class ClaudeCodeWebSearchRequest(
    @ToolParameter(description = "Web search query.")
    val query: String,
    @ToolParameter(description = "Optional domain allowlist. Cannot be combined with blocked_domains.")
    val allowed_domains: List<String> = emptyList(),
    @ToolParameter(description = "Optional domain blocklist. Cannot be combined with allowed_domains.")
    val blocked_domains: List<String> = emptyList(),
    @property:ToolParameter(description = "Search mode: standard (default) for quick, economical lookups; extended for thorough, fresh or difficult research at higher cost and latency.")
    val mode: ClaudeCodeWebSearchMode = ClaudeCodeWebSearchMode.standard,
) {
    init {
        require(query.isNotBlank()) { "Claude Code web search query must not be blank" }
        require(allowed_domains.isEmpty() || blocked_domains.isEmpty()) {
            "allowed_domains and blocked_domains cannot be combined"
        }
        require((allowed_domains + blocked_domains).all { it.isNotBlank() }) {
            "Claude Code web search domains must not be blank"
        }
    }
}

interface ClaudeCodeWebSearchTool : Tool<ClaudeCodeWebSearchRequest, String> {
    override val name: String
        get() = "claude_code_web_search"

    override val description: String
        get() = "Search the web through Claude Code's native WebSearch capability. Returns the native structured search result and exact source URLs. Use this provider-specific tool when Claude Code web search is explicitly available. mode defaults to standard for quick lookups; choose extended for thorough, fresh or difficult research at higher cost and latency. The CLI must preserve the requested mode and domain filters."

    override val metadata: AiToolMetadata
        get() = AiToolMetadata(
            requiredRuntimeCapabilities = setOf(ConversationRuntimeCapability.AI_REQUEST_RESPONSE),
            executionScope = AiToolExecutionScope.WORKER,
            loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE,
        )

    override val requestType: Class<ClaudeCodeWebSearchRequest>
        get() = ClaudeCodeWebSearchRequest::class.java

    override fun execute(request: ClaudeCodeWebSearchRequest, context: ToolExecutionContext?): String
}
