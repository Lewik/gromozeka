package com.gromozeka.infrastructure.ai.config

import com.gromozeka.infrastructure.ai.tool.*
import com.gromozeka.domain.tool.filesystem.*
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ParameterizedTypeReference
import java.util.function.BiFunction

/**
 * Configuration for built-in file system tools.
 * 
 * All tools delegate to @Service Tool classes following the unified architecture.
 */
@Configuration
class BuiltInTools {

    private val logger = LoggerFactory.getLogger(BuiltInTools::class.java)

    @Bean
    fun readFileToolCallback(grzReadFileTool: GrzReadFileToolImpl): ToolCallback {
        val function = object : BiFunction<ReadFileRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: ReadFileRequest, context: ToolContext?): Map<String, Any> {
                return grzReadFileTool.execute(request, context)
            }
        }
        
        return FunctionToolCallback.builder(grzReadFileTool.name, function)
            .description(grzReadFileTool.description)
            .inputType(object : ParameterizedTypeReference<ReadFileRequest>() {})
            .build()
    }

    @Bean
    fun writeFileToolCallback(grzWriteFileTool: GrzWriteFileToolImpl): ToolCallback {
        val function = object : BiFunction<WriteFileRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: WriteFileRequest, context: ToolContext?): Map<String, Any> {
                return grzWriteFileTool.execute(request, context)
            }
        }
        
        return FunctionToolCallback.builder(grzWriteFileTool.name, function)
            .description(grzWriteFileTool.description)
            .inputType(object : ParameterizedTypeReference<WriteFileRequest>() {})
            .build()
    }

    @Bean
    fun editFileToolCallback(grzEditFileTool: GrzEditFileToolImpl): ToolCallback {
        val function = object : BiFunction<EditFileRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: EditFileRequest, context: ToolContext?): Map<String, Any> {
                return grzEditFileTool.execute(request, context)
            }
        }
        
        return FunctionToolCallback.builder(grzEditFileTool.name, function)
            .description(grzEditFileTool.description)
            .inputType(object : ParameterizedTypeReference<EditFileRequest>() {})
            .build()
    }

    @Bean
    fun executeCommandToolCallback(grzExecuteCommandTool: GrzExecuteCommandToolImpl): ToolCallback {
        val function = object : BiFunction<ExecuteCommandRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: ExecuteCommandRequest, context: ToolContext?): Map<String, Any> {
                return grzExecuteCommandTool.execute(request, context)
            }
        }
        
        return FunctionToolCallback.builder(grzExecuteCommandTool.name, function)
            .description(grzExecuteCommandTool.description)
            .inputType(object : ParameterizedTypeReference<ExecuteCommandRequest>() {})
            .build()
    }
}

// All tool request/response classes migrated to Tool<*, *> implementations:
// - ReadFileRequest in GrzReadFileTool
// - WriteFileRequest in GrzWriteFileTool
// - EditFileRequest in GrzEditFileTool
// - ExecuteCommandRequest in GrzExecuteCommandTool
