package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.infrastructure.ai.config.mcp.McpConfigurationService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service

/**
 * Aggregates all AI-callable tools exposed inside the application:
 * - built-in domain tools adapted as AiToolCallback beans
 * - internal MCP tools registered as AiToolCallback beans
 * - external MCP tools exposed by MCP configuration service
 */
@Service
class DefaultAiToolProvider(
    private val applicationContext: ApplicationContext,
    private val mcpConfigurationService: ObjectProvider<McpConfigurationService>,
) : AiToolProvider {

    override fun getTools(): List<AiToolCallback> {
        val localTools = applicationContext.getBeansOfType(AiToolCallback::class.java).values.toList()
        val externalTools = mcpConfigurationService.getIfAvailable()?.getTools().orEmpty()
        return localTools + externalTools
    }
}
