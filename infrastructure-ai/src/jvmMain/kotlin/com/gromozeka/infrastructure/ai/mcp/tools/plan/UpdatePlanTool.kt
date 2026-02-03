package com.gromozeka.infrastructure.ai.mcp.tools.plan

import com.gromozeka.domain.model.plan.Plan
import com.gromozeka.domain.service.plan.UpdatePlanTool as UpdatePlanToolInterface
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
class UpdatePlanTool(
    private val planManagementService: PlanManagementService
) : GromozekaMcpTool, UpdatePlanToolInterface {

    @Serializable
    data class Input(
        val planId: String,
        val name: String? = null,
        val description: String? = null,
        val isTemplate: Boolean? = null
    )

    override val definition = Tool(
        name = "update_plan",
        description = "Update plan properties. Only passed parameters are updated.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("planId", buildJsonObject {
                    put("type", "string")
                    put("description", "Plan ID")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "New plan name (optional)")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "New plan description (optional)")
                })
                put("isTemplate", buildJsonObject {
                    put("type", "boolean")
                    put("description", "New template flag (optional)")
                })
            },
            required = listOf("planId")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = Json.decodeFromJsonElement<Input>(request.arguments)
        val result = execute(input.planId, input.name, input.description, input.isTemplate)
        
        return CallToolResult(
            content = listOf(TextContent(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), 
                kotlinx.serialization.json.JsonObject(result.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value.toString()) })))),
            isError = false
        )
    }

    override suspend fun execute(
        planId: String,
        name: String?,
        description: String?,
        isTemplate: Boolean?
    ): Map<String, Any> {
        val plan = planManagementService.updatePlan(
            planId = Plan.Id(planId),
            name = name,
            description = description,
            isTemplate = isTemplate
        )
        
        
        return mapOf(
            "planId" to plan.id.toString(),
            "name" to plan.name,
            "description" to plan.description,
            "isTemplate" to plan.isTemplate,
            "createdAt" to plan.createdAt.toString()
        )
    }
}
