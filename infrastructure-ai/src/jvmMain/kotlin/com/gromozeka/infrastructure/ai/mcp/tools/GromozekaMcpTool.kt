package com.gromozeka.infrastructure.ai.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonObject

/**
 * Base interface for Gromozeka MCP tools.
 *
 * Note: MCP framework automatically catches exceptions from execute() method and converts them
 * to proper error responses. No need to wrap execute() implementation in try-catch blocks.
 */
interface GromozekaMcpTool {
    val definition: Tool
    suspend fun execute(request: CallToolRequest): CallToolResult
    fun toRegisteredTool(): RegisteredTool = RegisteredTool(definition) { request ->
        execute(request)
    }
}

internal fun CallToolRequest.argumentsOrEmpty(): JsonObject =
    arguments ?: JsonObject(emptyMap())
