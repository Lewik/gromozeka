package com.gromozeka.infrastructure.ai.mcp.tools.treesitter

import com.gromozeka.domain.service.treesitter.TreeSitterService
import com.gromozeka.infrastructure.ai.mcp.tools.GromozekaMcpTool
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
class GetAstTool(
    private val treeSitterService: TreeSitterService
) : GromozekaMcpTool {

    @Serializable
    data class Input(
        val project: String,
        val path: String,
        val max_depth: Int? = null,
        val include_text: Boolean = true
    )

    override val definition = Tool(
        name = "ts_get_ast",
        description = """Get the Abstract Syntax Tree (AST) for a file using Tree-sitter.
        
        Parses the specified file and returns its AST representation as a nested structure.
        The AST shows the syntactic structure of the code including all nodes, their types,
        positions, and optionally the source text.
        
        Currently supports: Kotlin only (more languages coming soon)""",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("project", buildJsonObject {
                    put("type", "string")
                    put("description", "Name of the registered project")
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "File path relative to project root")
                })
                put("max_depth", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum depth to traverse the AST (default: 5)")
                })
                put("include_text", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to include source text for each node (default: true)")
                })
            },
            required = listOf("project", "path")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = Json.decodeFromJsonElement<Input>(request.arguments)
        
        return try {
            val maxDepth = input.max_depth ?: 5
            
            val fileAst = treeSitterService.getAst(
                projectName = input.project,
                filePath = input.path,
                maxDepth = maxDepth,
                includeText = input.include_text
            )
            
            // Serialize to JSON for output
            val jsonOutput = Json { prettyPrint = true }.encodeToString(fileAst)
            
            CallToolResult(
                content = listOf(
                    TextContent("AST for ${input.path} (${fileAst.language}):\n\n$jsonOutput")
                ),
                isError = false
            )
            
        } catch (e: IllegalArgumentException) {
            CallToolResult(
                content = listOf(TextContent("Error: ${e.message}")),
                isError = true
            )
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent("Error parsing file: ${e.message}")),
                isError = true
            )
        }
    }
}
