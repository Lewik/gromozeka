package com.gromozeka.bot.config.mcp

import io.modelcontextprotocol.kotlin.sdk.Tool

/**
 * Common interface for MCP client wrappers supporting different transports (stdio, SSE, etc).
 */
sealed interface McpWrapperInterface {
    val name: String

    suspend fun initialize()

    suspend fun listTools(): List<Tool>

    suspend fun callTool(toolName: String, arguments: Map<String, Any>): String

    fun close()
}
