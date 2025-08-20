package com.gromozeka.bot.services.mcp

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool

interface GromozekaMcpTool {
    val definition: Tool
    suspend fun execute(request: CallToolRequest): CallToolResult
    fun toRegisteredTool(): RegisteredTool = RegisteredTool(definition, ::execute)
}