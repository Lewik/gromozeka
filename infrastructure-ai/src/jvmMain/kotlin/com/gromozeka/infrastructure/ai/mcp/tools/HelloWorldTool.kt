package com.gromozeka.infrastructure.ai.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import org.springframework.stereotype.Service

@Service
class HelloWorldTool : GromozekaMcpTool {
    
    override val definition = Tool(
        name = "hello_world",
        description = "Test tool that returns a greeting from Gromozeka",
        inputSchema = ToolSchema(
            properties = buildJsonObject {},
            required = emptyList()
        ),
        outputSchema = null,
        annotations = null
    )
    
    override suspend fun execute(request: CallToolRequest): CallToolResult {
        return CallToolResult(
            content = listOf(
                TextContent("Hello from Gromozeka! 🤖 MCP integration is working correctly via official SDK. Tool: ${request.name}")
            ),
            isError = false
        )
    }
}
