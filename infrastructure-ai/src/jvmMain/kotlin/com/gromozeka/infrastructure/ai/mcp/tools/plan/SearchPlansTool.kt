package com.gromozeka.infrastructure.ai.mcp.tools.plan

import com.gromozeka.domain.service.plan.SearchPlansTool as SearchPlansToolInterface
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
class SearchPlansTool(
    private val planManagementService: PlanManagementService
) : GromozekaMcpTool, SearchPlansToolInterface {

    @Serializable
    data class Input(
        val query: String,
        val limit: Int = 10
    )

    override val definition = Tool(
        name = "search_plans",
        description = "Search plans using semantic search over description and name",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search query")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of results")
                    put("default", 10)
                })
            },
            required = listOf("query")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = Json.decodeFromJsonElement<Input>(request.arguments)
        val result = execute(input.query, input.limit)
        
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

    override suspend fun execute(query: String, limit: Int): Map<String, Any> {
        val plans = planManagementService.searchPlans(query, limit)
        
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
