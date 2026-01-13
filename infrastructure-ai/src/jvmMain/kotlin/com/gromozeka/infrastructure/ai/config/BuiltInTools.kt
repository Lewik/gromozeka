package com.gromozeka.infrastructure.ai.config

import com.gromozeka.infrastructure.ai.tool.*
import com.gromozeka.infrastructure.ai.tool.codebase.IndexDomainToGraphToolImpl
import com.gromozeka.infrastructure.ai.tool.lsp.*
import com.gromozeka.domain.tool.filesystem.*
import com.gromozeka.domain.tool.codebase.IndexDomainToGraphRequest
import com.gromozeka.domain.tool.lsp.*
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

    @Bean
    fun indexDomainToGraphToolCallback(indexDomainToGraphTool: IndexDomainToGraphToolImpl): ToolCallback {
        val function = object : BiFunction<IndexDomainToGraphRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: IndexDomainToGraphRequest, context: ToolContext?): Map<String, Any> {
                return indexDomainToGraphTool.execute(request, context)
            }
        }

        return FunctionToolCallback.builder(indexDomainToGraphTool.name, function)
            .description(indexDomainToGraphTool.description)
            .inputType(object : ParameterizedTypeReference<IndexDomainToGraphRequest>() {})
            .build()
    }

    @Bean
    fun lspFindDefinitionToolCallback(lspFindDefinitionTool: LspFindDefinitionToolImpl): ToolCallback {
        val function = object : BiFunction<LspFindDefinitionRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: LspFindDefinitionRequest, context: ToolContext?): Map<String, Any> {
                return lspFindDefinitionTool.execute(request, context)
            }
        }

        return FunctionToolCallback.builder(lspFindDefinitionTool.name, function)
            .description(lspFindDefinitionTool.description)
            .inputType(object : ParameterizedTypeReference<LspFindDefinitionRequest>() {})
            .build()
    }

    @Bean
    fun lspFindReferencesToolCallback(lspFindReferencesTool: LspFindReferencesToolImpl): ToolCallback {
        val function = object : BiFunction<LspFindReferencesRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: LspFindReferencesRequest, context: ToolContext?): Map<String, Any> {
                return lspFindReferencesTool.execute(request, context)
            }
        }

        return FunctionToolCallback.builder(lspFindReferencesTool.name, function)
            .description(lspFindReferencesTool.description)
            .inputType(object : ParameterizedTypeReference<LspFindReferencesRequest>() {})
            .build()
    }

    @Bean
    fun lspGetHoverToolCallback(lspGetHoverTool: LspGetHoverToolImpl): ToolCallback {
        val function = object : BiFunction<LspGetHoverRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: LspGetHoverRequest, context: ToolContext?): Map<String, Any> {
                return lspGetHoverTool.execute(request, context)
            }
        }

        return FunctionToolCallback.builder(lspGetHoverTool.name, function)
            .description(lspGetHoverTool.description)
            .inputType(object : ParameterizedTypeReference<LspGetHoverRequest>() {})
            .build()
    }

    @Bean
    fun lspGetDiagnosticsToolCallback(lspGetDiagnosticsTool: LspGetDiagnosticsToolImpl): ToolCallback {
        val function = object : BiFunction<LspGetDiagnosticsRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: LspGetDiagnosticsRequest, context: ToolContext?): Map<String, Any> {
                return lspGetDiagnosticsTool.execute(request, context)
            }
        }

        return FunctionToolCallback.builder(lspGetDiagnosticsTool.name, function)
            .description(lspGetDiagnosticsTool.description)
            .inputType(object : ParameterizedTypeReference<LspGetDiagnosticsRequest>() {})
            .build()
    }

    @Bean
    fun lspGetDocumentSymbolsToolCallback(lspGetDocumentSymbolsTool: LspGetDocumentSymbolsToolImpl): ToolCallback {
        val function = object : BiFunction<LspGetDocumentSymbolsRequest, ToolContext?, Map<String, Any>> {
            override fun apply(request: LspGetDocumentSymbolsRequest, context: ToolContext?): Map<String, Any> {
                return lspGetDocumentSymbolsTool.execute(request, context)
            }
        }

        return FunctionToolCallback.builder(lspGetDocumentSymbolsTool.name, function)
            .description(lspGetDocumentSymbolsTool.description)
            .inputType(object : ParameterizedTypeReference<LspGetDocumentSymbolsRequest>() {})
            .build()
    }
}

// All tool request/response classes migrated to Tool<*, *> implementations:
// - ReadFileRequest in GrzReadFileTool
// - WriteFileRequest in GrzWriteFileTool
// - EditFileRequest in GrzEditFileTool
// - ExecuteCommandRequest in GrzExecuteCommandTool
