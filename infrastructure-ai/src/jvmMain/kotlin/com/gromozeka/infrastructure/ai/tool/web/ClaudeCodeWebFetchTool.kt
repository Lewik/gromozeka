package com.gromozeka.infrastructure.ai.tool.web

import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.web.ClaudeCodeWebFetchRequest
import com.gromozeka.infrastructure.ai.claude.ClaudeCodeNativeTool
import com.gromozeka.infrastructure.ai.claude.ClaudeCodeNativeWebToolClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
internal class ClaudeCodeWebFetchTool(
    private val client: ClaudeCodeNativeWebToolClient,
) : com.gromozeka.domain.tool.web.ClaudeCodeWebFetchTool {
    override val available: Boolean
        get() = client.isAvailable(ClaudeCodeNativeTool.WEB_FETCH)

    override fun execute(
        request: ClaudeCodeWebFetchRequest,
        context: ToolExecutionContext?,
    ): String =
        runBlocking {
            client.execute(
                tool = ClaudeCodeNativeTool.WEB_FETCH,
                input = buildJsonObject {
                    put("url", request.url)
                    put("prompt", request.prompt)
                },
            ).toString()
        }
}
