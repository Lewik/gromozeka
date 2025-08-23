package com.gromozeka.bot.services.mcp

import com.gromozeka.bot.services.ContextFileService
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
class ListContextsTool(
    private val contextFileService: ContextFileService,
) : GromozekaMcpTool {

    @Serializable
    data class Input(
        val project_path: String? = null,
    )

    override val definition = Tool(
        name = "list_contexts",
        description = "List all available contexts or contexts for a specific project",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("project_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional project path to filter contexts. If not provided, lists all contexts.")
                })
            },
            required = emptyList()
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = Json.decodeFromJsonElement<Input>(request.arguments)

        val contexts = if (input.project_path != null) {
            contextFileService.listContextsForProject(input.project_path)
        } else {
            contextFileService.listAllContexts()
        }

        val contextsList = contexts.joinToString("\n") { context ->
            buildString {
                append("• ${context.name}")
                append(" (${context.projectPath})")
                if (context.files.isNotEmpty()) {
                    append(" - ${context.files.size} files")
                }
                if (context.links.isNotEmpty()) {
                    append(", ${context.links.size} links")
                }
                context.extractedAt?.let { date ->
                    append(" [${date.take(10)}]")
                }
            }
        }

        val resultText = if (contexts.isEmpty()) {
            if (input.project_path != null) {
                "No contexts found for project: ${input.project_path}"
            } else {
                "No contexts found. Use 'extract_contexts' to create contexts from conversations."
            }
        } else {
            val header = if (input.project_path != null) {
                "Contexts for project ${input.project_path} (${contexts.size} found):"
            } else {
                "All contexts (${contexts.size} found):"
            }
            "$header\n\n$contextsList"
        }

        return CallToolResult(
            content = listOf(TextContent(resultText)),
            isError = false
        )
    }
}