package com.gromozeka.domain.service

import com.gromozeka.domain.tool.AiToolCallback

/**
 * Provider for all AI-callable tools available to the runtime.
 *
 * Implementations may merge built-in tools, internal MCP tools, and external
 * MCP tools behind one framework-agnostic contract.
 */
interface AiToolProvider {
    fun getTools(): List<AiToolCallback>
}
