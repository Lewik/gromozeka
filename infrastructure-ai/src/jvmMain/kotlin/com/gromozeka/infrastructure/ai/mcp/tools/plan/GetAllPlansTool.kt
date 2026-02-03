package com.gromozeka.infrastructure.ai.mcp.tools.plan

import com.gromozeka.domain.service.plan.GetAllPlansTool as GetAllPlansToolInterface
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
class GetAllPlansTool(
    private val planManagementService: PlanManagementService
) : GromozekaMcpTool, GetAllPlansToolInterface {

    @Serializable
    data class Input(
        val includeTemplates: Boolean = true
    )

    override val definition = Tool(
        name = "get_all_plans",
        description = "Get all plans without filtering. Returns complete list of plans sorted by creation date (newest first).",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("includeTemplates", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to include template plans")
                    put("default", true)
                })
            },
            required = emptyList()
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = if (request.arguments.toString() == "{}") {
            Input()
        } else {
            Json.decodeFromJsonElement<Input>(request.arguments)
        }
        val result = execute(input.includeTemplates)
        
        return CallToolResult(
            content = listOf(TextContent(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), 
                kotlinx.serialization.json.JsonObject(result.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value.toString()) })))),
            isError = false
        )
    }

    override suspend fun execute(includeTemplates: Boolean): Map<String, Any> {
        val plans = planManagementService.getAllPlans(includeTemplates)
        
        return mapOf(
            "plans" to plans.map { plan ->
                mapOf(
                    "planId" to plan.id.toString(),
                    "name" to plan.name,
                    "description" to plan.description,
                    "isTemplate" to plan.isTemplate,
                    "createdAt" to plan.createdAt.toString()
                )
            },
            "count" to plans.size
        )
    }
}
