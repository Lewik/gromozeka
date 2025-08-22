package com.gromozeka.bot.services.mcp

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool

/**
 * Base interface for Gromozeka MCP tools.
 * 
 * Note: MCP framework automatically catches exceptions from execute() method and converts them 
 * to proper error responses. No need to wrap execute() implementation in try-catch blocks.
 */
interface GromozekaMcpTool {
    val definition: Tool
    suspend fun execute(request: CallToolRequest): CallToolResult
    fun toRegisteredTool(): RegisteredTool = RegisteredTool(definition, ::execute)
}