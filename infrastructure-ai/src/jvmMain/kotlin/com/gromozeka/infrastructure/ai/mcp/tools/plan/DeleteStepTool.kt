package com.gromozeka.infrastructure.ai.mcp.tools.plan

import com.gromozeka.domain.model.plan.PlanStep
import com.gromozeka.domain.service.plan.DeleteStepTool as DeleteStepToolInterface
import com.gromozeka.domain.service.plan.PlanManagementService
import com.gromozeka.infrastructure.ai.mcp.tools.GromozekaMcpTool
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
class DeleteStepTool(
    private val planManagementService: PlanManagementService
) : GromozekaMcpTool, DeleteStepToolInterface {

    @Serializable
    data class Input(
        val stepId: String
    )

    override val definition = Tool(
        name = "delete_step",
        description = "Delete step and all its child steps (cascades to children)",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("stepId", buildJsonObject {
                    put("type", "string")
                    put("description", "Step ID to delete")
                })
            },
            required = listOf("stepId")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = Json.decodeFromJsonElement<Input>(request.arguments)
        val result = execute(input.stepId)
        
        return CallToolResult(
            content = listOf(TextContent(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), 
                kotlinx.serialization.json.JsonObject(result.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value.toString()) })))),
            isError = false
        )
    }

    override suspend fun execute(stepId: String): Map<String, Any> {
        planManagementService.deleteStep(PlanStep.Id(stepId))
        
        return mapOf(
            "success" to true,
            "message" to "Step $stepId deleted successfully"
        )
    }
}
