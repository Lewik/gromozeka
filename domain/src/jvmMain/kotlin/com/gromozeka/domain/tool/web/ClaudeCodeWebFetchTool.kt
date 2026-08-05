package com.gromozeka.domain.tool.web

import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolLoadingPolicy
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.Tool
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.ToolParameter
import java.net.URI

data class ClaudeCodeWebFetchRequest(
    @ToolParameter(description = "Public HTTP or HTTPS URL to fetch.")
    val url: String,
    @ToolParameter(description = "Exact extraction or analysis request for the fetched page.")
    val prompt: String,
) {
    init {
        val parsed = runCatching { URI(url) }.getOrNull()
        require(parsed?.scheme in setOf("http", "https") && !parsed?.host.isNullOrBlank()) {
            "Claude Code web fetch URL must be an absolute HTTP or HTTPS URL"
        }
        require(prompt.isNotBlank()) { "Claude Code web fetch prompt must not be blank" }
    }
}

interface ClaudeCodeWebFetchTool : Tool<ClaudeCodeWebFetchRequest, String> {
    override val name: String
        get() = "claude_code_web_fetch"

    override val description: String
        get() = "Fetch and extract a public web page through Claude Code's native WebFetch capability. Provide a focused prompt describing what to extract. Redirects to another host are returned explicitly and require a separate call."

    override val metadata: AiToolMetadata
        get() = AiToolMetadata(
            requiredRuntimeCapabilities = setOf(ConversationRuntimeCapability.AI_REQUEST_RESPONSE),
            executionScope = AiToolExecutionScope.WORKER,
            loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE,
        )

    override val requestType: Class<ClaudeCodeWebFetchRequest>
        get() = ClaudeCodeWebFetchRequest::class.java

    override fun execute(request: ClaudeCodeWebFetchRequest, context: ToolExecutionContext?): String
}
