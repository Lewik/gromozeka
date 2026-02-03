package com.gromozeka.infrastructure.ai.mcp.tools.plan

import com.gromozeka.domain.model.plan.PlanStep
import com.gromozeka.domain.model.plan.StepStatus
import com.gromozeka.domain.service.plan.UpdateStepTool as UpdateStepToolInterface
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
class UpdateStepTool(
    private val planManagementService: PlanManagementService
) : GromozekaMcpTool, UpdateStepToolInterface {

    @Serializable
    data class Input(
        val stepId: String,
        val instruction: String? = null,
        val result: String? = null,
        val status: String? = null,
        val parentId: String? = null
    )

    override val definition = Tool(
        name = "update_step",
        description = "Update step properties. Only passed parameters are updated.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("stepId", buildJsonObject {
                    put("type", "string")
                    put("description", "Step ID")
                })
                put("instruction", buildJsonObject {
                    put("type", "string")
                    put("description", "New instruction text (optional, only for Text steps)")
                })
                put("result", buildJsonObject {
                    put("type", "string")
                    put("description", "New result text (optional)")
                })
                put("status", buildJsonObject {
                    put("type", "string")
                    put("description", "New status: PENDING, DONE, SKIPPED (optional)")
                })
                put("parentId", buildJsonObject {
                    put("type", "string")
                    put("description", "New parent step ID (optional)")
                })
            },
            required = listOf("stepId")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = Json.decodeFromJsonElement<Input>(request.arguments)
        val result = execute(
            stepId = input.stepId,
            instruction = input.instruction,
            result = input.result,
            status = input.status,
            parentId = input.parentId
        )
        
        return CallToolResult(
            content = listOf(TextContent(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), 
                kotlinx.serialization.json.JsonObject(result.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value.toString()) })))),
            isError = false
        )
    }

    override suspend fun execute(
        stepId: String,
        instruction: String?,
        result: String?,
        status: String?,
        parentId: String?
    ): Map<String, Any> {
        val step = planManagementService.updateStep(
            stepId = PlanStep.Id(stepId),
            instruction = instruction,
            result = result,
            status = status?.let { StepStatus.valueOf(it) },
            parentId = parentId?.takeIf { it.isNotBlank() }?.let { PlanStep.Id(it) }
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
