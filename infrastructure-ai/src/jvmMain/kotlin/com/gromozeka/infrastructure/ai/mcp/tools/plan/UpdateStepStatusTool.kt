package com.gromozeka.infrastructure.ai.mcp.tools.plan

import com.gromozeka.domain.model.plan.PlanStep
import com.gromozeka.domain.model.plan.StepStatus
import com.gromozeka.domain.service.plan.UpdateStepStatusTool as UpdateStepStatusToolInterface
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
class UpdateStepStatusTool(
    private val planManagementService: PlanManagementService
) : GromozekaMcpTool, UpdateStepStatusToolInterface {

    @Serializable
    data class Input(
        val stepId: String,
        val status: String
    )

    override val definition = Tool(
        name = "update_step_status",
        description = "Quick update of step status (for UI checkbox). Status: PENDING, DONE, SKIPPED",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("stepId", buildJsonObject {
                    put("type", "string")
                    put("description", "Step ID")
                })
                put("status", buildJsonObject {
                    put("type", "string")
                    put("description", "New status: PENDING, DONE, SKIPPED")
                    put("enum", kotlinx.serialization.json.JsonArray(listOf("PENDING", "DONE", "SKIPPED").map { 
                        kotlinx.serialization.json.JsonPrimitive(it) 
                    }))
                })
            },
            required = listOf("stepId", "status")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = Json.decodeFromJsonElement<Input>(request.arguments)
        val result = execute(input.stepId, input.status)
        
        return CallToolResult(
            content = listOf(TextContent(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), 
                kotlinx.serialization.json.JsonObject(result.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value.toString()) })))),
            isError = false
        )
    }

    override suspend fun execute(stepId: String, status: String): Map<String, Any> {
        val step = planManagementService.updateStepStatus(
            stepId = PlanStep.Id(stepId),
            status = StepStatus.valueOf(status)
        )
        
        
        return when (step) {
            is PlanStep.Text -> mapOf(
                "stepId" to step.id.toString(),
                "planId" to step.planId.toString(),
                "parentId" to (step.parentId?.toString() ?: "null"),
                "status" to step.status.name,
                "type" to "text",
                "instruction" to step.instruction,
                "result" to (step.result ?: "null")
            )
        }
    }
}
