package com.gromozeka.infrastructure.ai.springai

import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.ToolExecutionContext
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * Adapts framework-agnostic AiToolCallback to Spring AI ToolCallback.
 *
 * This adapter is intentionally kept inside infrastructure-ai so Spring AI
 * types never leak into domain/application layers.
 */
class SpringAiToolCallbackAdapter(
    private val delegate: AiToolCallback
) : ToolCallback {

    override fun call(toolInput: String): String = delegate.call(toolInput, null)

    override fun call(toolInput: String, toolContext: ToolContext?): String {
        val domainContext = toolContext?.let { ToolExecutionContext(it.context) }
        return delegate.call(toolInput, domainContext)
    }

    override fun getToolDefinition(): ToolDefinition {
        val definition = delegate.definition
        return object : ToolDefinition {
            override fun name(): String = definition.name
            override fun description(): String = definition.description
            override fun inputSchema(): String = definition.inputSchema
        }
    }
}
