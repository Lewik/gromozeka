package com.gromozeka.infrastructure.ai.tool.web

import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.web.ClaudeCodeWebSearchRequest
import com.gromozeka.infrastructure.ai.claude.ClaudeCodeNativeTool
import com.gromozeka.infrastructure.ai.claude.ClaudeCodeNativeWebToolClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.springframework.stereotype.Service

@Service
internal class ClaudeCodeWebSearchTool(
    private val client: ClaudeCodeNativeWebToolClient,
) : com.gromozeka.domain.tool.web.ClaudeCodeWebSearchTool {
    override val available: Boolean
        get() = client.isAvailable(ClaudeCodeNativeTool.WEB_SEARCH)

    override fun execute(
        request: ClaudeCodeWebSearchRequest,
        context: ToolExecutionContext?,
    ): String =
        runBlocking {
            client.execute(
                tool = ClaudeCodeNativeTool.WEB_SEARCH,
                input = request.toNativeWebSearchInput(),
            ).toString()
        }
}

/** Always send the mode explicitly: an omitted mode is not the CLI's standard default. */
internal fun ClaudeCodeWebSearchRequest.toNativeWebSearchInput(): JsonObject = JsonObject(
    buildMap {
        put("query", JsonPrimitive(query))
        put("mode", JsonPrimitive(mode.name))
        if (allowed_domains.isNotEmpty()) put("allowed_domains", JsonArray(allowed_domains.map(::JsonPrimitive)))
        if (blocked_domains.isNotEmpty()) put("blocked_domains", JsonArray(blocked_domains.map(::JsonPrimitive)))
    }
)
