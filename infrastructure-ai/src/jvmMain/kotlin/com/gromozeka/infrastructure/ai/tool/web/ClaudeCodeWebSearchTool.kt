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
                input = JsonObject(
                    buildMap {
                        put("query", JsonPrimitive(request.query))
                        if (request.allowed_domains.isNotEmpty()) {
                            put(
                                "allowed_domains",
                                JsonArray(request.allowed_domains.map(::JsonPrimitive)),
                            )
                        }
                        if (request.blocked_domains.isNotEmpty()) {
                            put(
                                "blocked_domains",
                                JsonArray(request.blocked_domains.map(::JsonPrimitive)),
                            )
                        }
                    }
                ),
            ).toString()
        }
}
