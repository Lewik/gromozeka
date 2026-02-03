package com.gromozeka.infrastructure.ai.mcp.tools.plan

import com.gromozeka.domain.model.plan.Plan
import com.gromozeka.domain.service.plan.ClonePlanTool as ClonePlanToolInterface
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
class ClonePlanTool(
    private val planManagementService: PlanManagementService
) : GromozekaMcpTool, ClonePlanToolInterface {

    @Serializable
    data class Input(
        val planId: String,
        val newName: String? = null
    )

    override val definition = Tool(
        name = "clone_plan",
        description = "Clone plan with all steps. Creates a copy with new IDs and PENDING status.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("planId", buildJsonObject {
                    put("type", "string")
                    put("description", "Plan ID to clone")
                })
                put("newName", buildJsonObject {
                    put("type", "string")
                    put("description", "Name for cloned plan (optional, defaults to 'Copy of {original}')")
                })
            },
            required = listOf("planId")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = Json.decodeFromJsonElement<Input>(request.arguments)
        val result = execute(input.planId, input.newName)
        
        return CallToolResult(
            content = listOf(TextContent(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), 
                kotlinx.serialization.json.JsonObject(result.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value.toString()) })))),
            isError = false
        )
    }

    override suspend fun execute(planId: String, newName: String?): Map<String, Any> {
        val sourcePlanId = Plan.Id(planId)
        val clonedPlan = planManagementService.clonePlan(sourcePlanId, newName)
        
        
        return mapOf(
            "planId" to clonedPlan.id.toString(),
            "name" to clonedPlan.name,
            "description" to clonedPlan.description,
            "isTemplate" to clonedPlan.isTemplate,
            "createdAt" to clonedPlan.createdAt.toString()
        )
    }
}
