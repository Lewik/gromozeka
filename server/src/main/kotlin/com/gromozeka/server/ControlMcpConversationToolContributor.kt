package com.gromozeka.server

import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredUserId
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.springframework.stereotype.Service

@Service
internal class ControlMcpConversationToolContributor(
    catalog: ControlMcpToolCatalog,
    private val userDirectoryService: UserDirectoryService,
) : AiToolCallbackContributor {
    override val callbacks: List<AiToolCallback> = catalog.tools.map(::adapt)

    private fun adapt(tool: ControlMcpTool): AiToolCallback =
        object : AiToolCallback {
            override val definition = AiToolDefinition(
                name = tool.definition.name,
                description = tool.definition.description.orEmpty(),
                inputSchema = controlMcpJson.encodeToString(
                    ToolSchema.serializer(),
                    tool.definition.inputSchema,
                ),
                source = CONTROL_TOOL_SOURCE,
            )

            override val metadata = AiToolMetadata(
                executionScope = AiToolExecutionScope.SERVER,
                visibleToMemoryPipeline = false,
            )

            override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
                val user = userDirectoryService.findActiveById(context.requiredUserId())
                    ?: error("Conversation control tools require an active authenticated user")
                tool.invokeStructured(
                    context = ControlMcpCallContext(user),
                    arguments = toolInput.toArguments(),
                ).toString()
            }
        }

    private fun String.toArguments(): JsonObject {
        if (isBlank()) return buildJsonObject {}
        return controlMcpJson.parseToJsonElement(this) as? JsonObject
            ?: throw IllegalArgumentException("Control tool input must be a JSON object")
    }

    private companion object {
        const val CONTROL_TOOL_SOURCE = "gromozeka:control"
    }
}
