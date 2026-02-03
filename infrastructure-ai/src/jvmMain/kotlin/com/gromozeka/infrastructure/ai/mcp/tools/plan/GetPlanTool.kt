package com.gromozeka.infrastructure.ai.mcp.tools.plan

import com.gromozeka.domain.model.plan.Plan
import com.gromozeka.domain.model.plan.PlanStep
import com.gromozeka.domain.service.plan.GetPlanTool as GetPlanToolInterface
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
class GetPlanTool(
    private val planManagementService: PlanManagementService
) : GromozekaMcpTool, GetPlanToolInterface {

    @Serializable
    data class Input(
        val planId: String
    )

    override val definition = Tool(
        name = "get_plan",
        description = "Get plan with all its steps",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("planId", buildJsonObject {
                    put("type", "string")
                    put("description", "Plan ID")
                })
            },
            required = listOf("planId")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = Json.decodeFromJsonElement<Input>(request.arguments)
        val result = execute(input.planId)
        
        return CallToolResult(
            content = listOf(TextContent(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), 
                kotlinx.serialization.json.JsonObject(result.mapValues { 
                    when (val value = it.value) {
                        is List<*> -> kotlinx.serialization.json.JsonArray(value.map { item ->
                            kotlinx.serialization.json.JsonPrimitive(item.toString())
                        })
                        else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
                    }
                })))),
            isError = false
        )
    }

    override suspend fun execute(planId: String): Map<String, Any> {
        val (plan, steps) = planManagementService.getPlanWithSteps(Plan.Id(planId))
        
        return mapOf(
            "plan" to mapOf(
                "planId" to plan.id.toString(),
                "name" to plan.name,
                "description" to plan.description,
                "isTemplate" to plan.isTemplate,
                "createdAt" to plan.createdAt.toString()
            ),
            "steps" to steps.map { step ->
                when (step) {
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
        )
    }
}
