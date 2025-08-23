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
import java.io.File

@Service
class DeleteContextTool(
    private val contextFileService: ContextFileService,
) : GromozekaMcpTool {

    @Serializable
    data class Input(
        val context_name: String,
    )

    override val definition = Tool(
        name = "delete_context",
        description = """Delete a context by name. WARNING: This action cannot be undone.
        
Use 'list_contexts' first to see available contexts and their exact names.""",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("context_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact name of the context to delete (case-sensitive)")
                })
            },
            required = listOf("context_name")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {

        val input = Json.decodeFromJsonElement<Input>(request.arguments)

        // Find context by name
        val allContexts = contextFileService.listAllContexts()
        val contextToDelete = allContexts.find { it.name == input.context_name }

        if (contextToDelete == null) {
            return CallToolResult(
                content = listOf(
                    TextContent(
                        "Context '${input.context_name}' not found. " +
                                "Use 'list_contexts' to see available contexts."
                    )
                ),
                isError = true
            )
        }

        // Verify file exists
        if (contextToDelete.filePath.isEmpty()) {
            return CallToolResult(
                content = listOf(
                    TextContent(
                        "Context '${input.context_name}' has no file path. Cannot delete."
                    )
                ),
                isError = true
            )
        }

        val file = File(contextToDelete.filePath)
        if (!file.exists()) {
            return CallToolResult(
                content = listOf(
                    TextContent(
                        "Context file not found at: ${contextToDelete.filePath}"
                    )
                ),
                isError = true
            )
        }

        // Delete the context
        val deleteResult = contextFileService.deleteContext(contextToDelete.filePath)

        return if (deleteResult.isSuccess) {
            CallToolResult(
                content = listOf(
                    TextContent(
                        "Successfully deleted context '${input.context_name}' from project ${contextToDelete.projectPath}"
                    )
                ),
                isError = false
            )
        } else {
            CallToolResult(
                content = listOf(
                    TextContent(
                        "Failed to delete context '${input.context_name}': ${deleteResult.exceptionOrNull()?.message}"
                    )
                ),
                isError = true
            )
        }

    }
}