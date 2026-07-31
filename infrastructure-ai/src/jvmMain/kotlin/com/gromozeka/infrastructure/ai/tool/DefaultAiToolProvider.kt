package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
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
    private val callbackContributors: ObjectProvider<AiToolCallbackContributor>,
    private val mcpConfigurationService: ObjectProvider<McpConfigurationService>,
) : AiToolProvider {

    override fun getTools(): List<AiToolCallback> {
        val declaredCallbacks = applicationContext.getBeansOfType(AiToolCallback::class.java).values
        val contributedCallbacks = callbackContributors.orderedStream()
            .toList()
            .flatMap(AiToolCallbackContributor::callbacks)
        val externalTools = mcpConfigurationService.getIfAvailable()?.getTools().orEmpty()
        val tools = (declaredCallbacks + contributedCallbacks + externalTools).filter(AiToolCallback::available)
        val duplicateNames = tools
            .groupingBy { it.definition.name }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
        check(duplicateNames.isEmpty()) {
            "AI tool names must be unique: ${duplicateNames.joinToString()}"
        }
        return tools
    }
}
