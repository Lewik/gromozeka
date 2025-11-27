package com.gromozeka.infrastructure.ai.config

import com.gromozeka.domain.tool.memory.*
import com.gromozeka.infrastructure.ai.tool.memory.*
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ParameterizedTypeReference
import java.util.function.BiFunction

/**
 * Configuration for Memory/Knowledge Graph tools.
 * 
 * All tools delegate to @Service Tool classes following the unified architecture.
 */
@Configuration
@ConditionalOnProperty(name = ["knowledge-graph.enabled"], havingValue = "true", matchIfMissing = false)
class MemoryToolsConfig {

    @Bean
    fun buildMemoryFromTextToolCallback(tool: com.gromozeka.infrastructure.ai.tool.memory.BuildMemoryFromTextTool): ToolCallback {
        val function = object : BiFunction<BuildMemoryFromTextRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: BuildMemoryFromTextRequest, context: ToolContext?) = 
                tool.execute(request, context)
        }
        return FunctionToolCallback.builder(tool.name, function)
            .description(tool.description)
            .inputType(object : ParameterizedTypeReference<BuildMemoryFromTextRequest>() {})
            .build()
    }

    @Bean
    fun addMemoryLinkToolCallback(tool: com.gromozeka.infrastructure.ai.tool.memory.AddMemoryLinkTool): ToolCallback {
        val function = object : BiFunction<AddMemoryLinkRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: AddMemoryLinkRequest, context: ToolContext?) = 
                tool.execute(request, context)
        }
        return FunctionToolCallback.builder(tool.name, function)
            .description(tool.description)
            .inputType(object : ParameterizedTypeReference<AddMemoryLinkRequest>() {})
            .build()
    }

    @Bean
    fun getMemoryObjectToolCallback(tool: com.gromozeka.infrastructure.ai.tool.memory.GetMemoryObjectTool): ToolCallback {
        val function = object : BiFunction<GetMemoryObjectRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: GetMemoryObjectRequest, context: ToolContext?) = 
                tool.execute(request, context)
        }
        return FunctionToolCallback.builder(tool.name, function)
            .description(tool.description)
            .inputType(object : ParameterizedTypeReference<GetMemoryObjectRequest>() {})
            .build()
    }

    @Bean
    fun invalidateMemoryLinkToolCallback(tool: com.gromozeka.infrastructure.ai.tool.memory.InvalidateMemoryLinkTool): ToolCallback {
        val function = object : BiFunction<InvalidateMemoryLinkRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: InvalidateMemoryLinkRequest, context: ToolContext?) = 
                tool.execute(request, context)
        }
        return FunctionToolCallback.builder(tool.name, function)
            .description(tool.description)
            .inputType(object : ParameterizedTypeReference<InvalidateMemoryLinkRequest>() {})
            .build()
    }

    @Bean
    fun updateMemoryObjectToolCallback(tool: com.gromozeka.infrastructure.ai.tool.memory.UpdateMemoryObjectTool): ToolCallback {
        val function = object : BiFunction<UpdateMemoryObjectRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: UpdateMemoryObjectRequest, context: ToolContext?) = 
                tool.execute(request, context)
        }
        return FunctionToolCallback.builder(tool.name, function)
            .description(tool.description)
            .inputType(object : ParameterizedTypeReference<UpdateMemoryObjectRequest>() {})
            .build()
    }

    @Bean
    fun deleteMemoryObjectToolCallback(tool: com.gromozeka.infrastructure.ai.tool.memory.DeleteMemoryObjectTool): ToolCallback {
        val function = object : BiFunction<DeleteMemoryObjectRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: DeleteMemoryObjectRequest, context: ToolContext?) = 
                tool.execute(request, context)
        }
        return FunctionToolCallback.builder(tool.name, function)
            .description(tool.description)
            .inputType(object : ParameterizedTypeReference<DeleteMemoryObjectRequest>() {})
            .build()
    }
}
