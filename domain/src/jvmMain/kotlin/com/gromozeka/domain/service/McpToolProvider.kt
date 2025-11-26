package com.gromozeka.domain.service

import org.springframework.ai.tool.ToolCallback

/**
 * Provider for Model Context Protocol (MCP) tools.
 *
 * Abstraction for accessing MCP-registered tools as Spring AI ToolCallbacks.
 * Infrastructure layer manages MCP server connections and tool registration.
 *
 * This is JVM-specific domain interface because it uses Spring AI types.
 * Spring AI is treated as framework dependency (similar to Spring Framework itself).
 */
interface McpToolProvider {
    /**
     * Gets all registered MCP tools as ToolCallbacks.
     *
     * Returns tools from all connected MCP servers.
     * Tools are dynamically loaded from MCP server configurations.
     *
     * @return list of MCP tool callbacks ready for ChatModel usage
     */
    fun getToolCallbacks(): List<ToolCallback>
}
