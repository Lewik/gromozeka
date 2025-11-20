package com.gromozeka.infrastructure.ai.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.buildJsonObject
import org.springframework.stereotype.Service

@Service
class HelloWorldTool : GromozekaMcpTool {
    
    override val definition = Tool(
        name = "hello_world",
        description = "Test tool that returns a greeting from Gromozeka",
        inputSchema = Tool.Input(
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