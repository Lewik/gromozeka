package com.gromozeka.infrastructure.ai.config

import com.gromozeka.infrastructure.ai.tool.*
import com.gromozeka.infrastructure.ai.tool.codebase.IndexDomainToGraphToolImpl
import com.gromozeka.infrastructure.ai.tool.lsp.*
import com.gromozeka.infrastructure.ai.mcp.tools.plan.*
import com.gromozeka.domain.tool.filesystem.*
import com.gromozeka.domain.tool.codebase.IndexDomainToGraphRequest
import com.gromozeka.domain.tool.lsp.*
import kotlinx.coroutines.runBlocking
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

    // ============ Plan Management Tools ============

    @Bean
    fun createPlanToolCallback(createPlanTool: CreatePlanTool): ToolCallback {
        val function = object : BiFunction<CreatePlanTool.Input, ToolContext?, Map<String, Any>> {
            override fun apply(request: CreatePlanTool.Input, context: ToolContext?): Map<String, Any> {
                return runBlocking {
                    createPlanTool.execute(request.name, request.description, request.isTemplate)
                }
            }
        }

        return FunctionToolCallback.builder("create_plan", function)
            .description("Create new plan with name and description")
            .inputType(object : ParameterizedTypeReference<CreatePlanTool.Input>() {})
            .build()
    }

    @Bean
    fun updatePlanToolCallback(updatePlanTool: UpdatePlanTool): ToolCallback {
        val function = object : BiFunction<UpdatePlanTool.Input, ToolContext?, Map<String, Any>> {
            override fun apply(request: UpdatePlanTool.Input, context: ToolContext?): Map<String, Any> {
                return runBlocking {
                    updatePlanTool.execute(request.planId, request.name, request.description, request.isTemplate)
                }
            }
        }

        return FunctionToolCallback.builder("update_plan", function)
            .description("Update existing plan")
            .inputType(object : ParameterizedTypeReference<UpdatePlanTool.Input>() {})
            .build()
    }

    @Bean
    fun deletePlanToolCallback(deletePlanTool: DeletePlanTool): ToolCallback {
        val function = object : BiFunction<DeletePlanTool.Input, ToolContext?, Map<String, Any>> {
            override fun apply(request: DeletePlanTool.Input, context: ToolContext?): Map<String, Any> {
                return runBlocking {
                    deletePlanTool.execute(request.planId)
                }
            }
        }

        return FunctionToolCallback.builder("delete_plan", function)
            .description("Delete plan with all its steps")
            .inputType(object : ParameterizedTypeReference<DeletePlanTool.Input>() {})
            .build()
    }

    @Bean
    fun getPlanToolCallback(getPlanTool: GetPlanTool): ToolCallback {
        val function = object : BiFunction<GetPlanTool.Input, ToolContext?, Map<String, Any>> {
            override fun apply(request: GetPlanTool.Input, context: ToolContext?): Map<String, Any> {
                return runBlocking {
                    getPlanTool.execute(request.planId)
                }
            }
        }

        return FunctionToolCallback.builder("get_plan", function)
            .description("Get plan with all its steps")
            .inputType(object : ParameterizedTypeReference<GetPlanTool.Input>() {})
            .build()
    }

    @Bean
    fun getAllPlansToolCallback(getAllPlansTool: com.gromozeka.infrastructure.ai.mcp.tools.plan.GetAllPlansTool): ToolCallback {
        val function = object : BiFunction<com.gromozeka.infrastructure.ai.mcp.tools.plan.GetAllPlansTool.Input, ToolContext?, Map<String, Any>> {
            override fun apply(request: com.gromozeka.infrastructure.ai.mcp.tools.plan.GetAllPlansTool.Input, context: ToolContext?): Map<String, Any> {
                return runBlocking {
                    getAllPlansTool.execute(request.includeTemplates)
                }
            }
        }

        return FunctionToolCallback.builder("get_all_plans", function)
            .description("Get all plans without filtering")
            .inputType(object : ParameterizedTypeReference<com.gromozeka.infrastructure.ai.mcp.tools.plan.GetAllPlansTool.Input>() {})
            .build()
    }

    @Bean
    fun searchPlansToolCallback(searchPlansTool: SearchPlansTool): ToolCallback {
        val function = object : BiFunction<SearchPlansTool.Input, ToolContext?, Map<String, Any>> {
            override fun apply(request: SearchPlansTool.Input, context: ToolContext?): Map<String, Any> {
                return runBlocking {
                    searchPlansTool.execute(request.query, request.limit)
                }
            }
        }

        return FunctionToolCallback.builder("search_plans", function)
            .description("Search plans by query using semantic and keyword search")
            .inputType(object : ParameterizedTypeReference<SearchPlansTool.Input>() {})
            .build()
    }

    @Bean
    fun clonePlanToolCallback(clonePlanTool: ClonePlanTool): ToolCallback {
        val function = object : BiFunction<ClonePlanTool.Input, ToolContext?, Map<String, Any>> {
            override fun apply(request: ClonePlanTool.Input, context: ToolContext?): Map<String, Any> {
                return runBlocking {
                    clonePlanTool.execute(request.planId, request.newName)
                }
            }
        }

        return FunctionToolCallback.builder("clone_plan", function)
            .description("Clone existing plan with all steps")
            .inputType(object : ParameterizedTypeReference<ClonePlanTool.Input>() {})
            .build()
    }

    @Bean
    fun addTextStepToolCallback(addTextStepTool: AddTextStepTool): ToolCallback {
        val function = object : BiFunction<AddTextStepTool.Input, ToolContext?, Map<String, Any>> {
            override fun apply(request: AddTextStepTool.Input, context: ToolContext?): Map<String, Any> {
                return runBlocking {
                    addTextStepTool.execute(request.planId, request.instruction, request.parentId)
                }
            }
        }

        return FunctionToolCallback.builder("add_text_step", function)
            .description("Add text step to plan")
            .inputType(object : ParameterizedTypeReference<AddTextStepTool.Input>() {})
            .build()
    }

    @Bean
    fun updateStepToolCallback(updateStepTool: UpdateStepTool): ToolCallback {
        val function = object : BiFunction<UpdateStepTool.Input, ToolContext?, Map<String, Any>> {
            override fun apply(request: UpdateStepTool.Input, context: ToolContext?): Map<String, Any> {
                return runBlocking {
                    updateStepTool.execute(request.stepId, request.instruction, request.result, request.status, request.parentId)
                }
            }
        }

        return FunctionToolCallback.builder("update_step", function)
            .description("Update existing step")
            .inputType(object : ParameterizedTypeReference<UpdateStepTool.Input>() {})
            .build()
    }

    @Bean
    fun deleteStepToolCallback(deleteStepTool: DeleteStepTool): ToolCallback {
        val function = object : BiFunction<DeleteStepTool.Input, ToolContext?, Map<String, Any>> {
            override fun apply(request: DeleteStepTool.Input, context: ToolContext?): Map<String, Any> {
                return runBlocking {
                    deleteStepTool.execute(request.stepId)
                }
            }
        }

        return FunctionToolCallback.builder("delete_step", function)
            .description("Delete step from plan")
            .inputType(object : ParameterizedTypeReference<DeleteStepTool.Input>() {})
            .build()
    }

    @Bean
    fun updateStepStatusToolCallback(updateStepStatusTool: UpdateStepStatusTool): ToolCallback {
        val function = object : BiFunction<UpdateStepStatusTool.Input, ToolContext?, Map<String, Any>> {
            override fun apply(request: UpdateStepStatusTool.Input, context: ToolContext?): Map<String, Any> {
                return runBlocking {
                    updateStepStatusTool.execute(request.stepId, request.status)
                }
            }
        }

        return FunctionToolCallback.builder("update_step_status", function)
            .description("Update step status (PENDING, DONE, SKIPPED)")
            .inputType(object : ParameterizedTypeReference<UpdateStepStatusTool.Input>() {})
            .build()
    }
}

// All tool request/response classes migrated to Tool<*, *> implementations:
// - ReadFileRequest in GrzReadFileTool
// - WriteFileRequest in GrzWriteFileTool
// - EditFileRequest in GrzEditFileTool
// - ExecuteCommandRequest in GrzExecuteCommandTool
