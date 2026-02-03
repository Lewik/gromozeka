package com.gromozeka.infrastructure.ai.mcp.tools.plan

import com.gromozeka.domain.service.plan.CreatePlanTool as CreatePlanToolInterface
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
class CreatePlanTool(
    private val planManagementService: PlanManagementService
) : GromozekaMcpTool, CreatePlanToolInterface {

    @Serializable
    data class Input(
        val name: String,
        val description: String,
        val isTemplate: Boolean = false
    )

    override val definition = Tool(
        name = "create_plan",
        description = "Create new plan with name and description",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Plan name")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "Detailed description of the task")
                })
                put("isTemplate", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether this plan is a template for reuse")
                    put("default", false)
                })
            },
            required = listOf("name", "description")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = Json.decodeFromJsonElement<Input>(request.arguments)
        val result = execute(input.name, input.description, input.isTemplate)
        
        return CallToolResult(
            content = listOf(TextContent(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), 
                kotlinx.serialization.json.JsonObject(result.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value.toString()) })))),
            isError = false
        )
    }

    override suspend fun execute(name: String, description: String, isTemplate: Boolean): Map<String, Any> {
        val plan = planManagementService.createPlan(name, description, isTemplate)
        
        return mapOf(
            "planId" to plan.id.toString(),
            "name" to plan.name,
            "description" to plan.description,
            "isTemplate" to plan.isTemplate,
            "createdAt" to plan.createdAt.toString()
        )
    }
}
