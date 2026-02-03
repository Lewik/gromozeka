package com.gromozeka.infrastructure.ai.mcp.tools.plan

import com.gromozeka.domain.model.plan.Plan
import com.gromozeka.domain.model.plan.PlanStep
import com.gromozeka.domain.service.plan.AddTextStepTool as AddTextStepToolInterface
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
class AddTextStepTool(
    private val planManagementService: PlanManagementService
) : GromozekaMcpTool, AddTextStepToolInterface {

    @Serializable
    data class Input(
        val planId: String,
        val instruction: String,
        val parentId: String? = null
    )

    override val definition = Tool(
        name = "add_text_step",
        description = """Add text step to plan with instruction. 
            
            IMPORTANT: Only ONE root step (parentId=null) is allowed per plan. 
            - For the FIRST step: set parentId to null
            - For subsequent steps: set parentId to the previous step's ID to create a chain
            
            Steps form a linear chain via parentId references.""".trimIndent(),
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("planId", buildJsonObject {
                    put("type", "string")
                    put("description", "Plan ID")
                })
                put("instruction", buildJsonObject {
                    put("type", "string")
                    put("description", "Instruction text - what needs to be done")
                })
                put("parentId", buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonArray(listOf(
                        kotlinx.serialization.json.JsonPrimitive("string"),
                        kotlinx.serialization.json.JsonPrimitive("null")
                    )))
                    put("description", "Parent step ID. Use null ONLY for the first step in plan (root step). For all other steps, provide the ID of the previous step to create a chain. Only one root step is allowed per plan.")
                })
            },
            required = listOf("planId", "instruction", "parentId")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = Json.decodeFromJsonElement<Input>(request.arguments)
        val result = execute(input.planId, input.instruction, input.parentId)
        
        return CallToolResult(
            content = listOf(TextContent(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), 
                kotlinx.serialization.json.JsonObject(result.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value.toString()) })))),
            isError = false
        )
    }

    override suspend fun execute(planId: String, instruction: String, parentId: String?): Map<String, Any> {
        val step = planManagementService.addTextStep(
            planId = Plan.Id(planId),
            instruction = instruction,
            parentId = parentId?.takeIf { it.isNotBlank() }?.let { PlanStep.Id(it) }
        )
        
        
        return mapOf(
            "stepId" to step.id.toString(),
            "planId" to step.planId.toString(),
            "parentId" to (step.parentId?.toString() ?: "null"),
            "status" to step.status.name,
            "instruction" to step.instruction,
            "result" to (step.result ?: "null")
        )
    }
}
