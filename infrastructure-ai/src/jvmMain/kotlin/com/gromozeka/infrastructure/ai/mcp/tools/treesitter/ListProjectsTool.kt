package com.gromozeka.infrastructure.ai.mcp.tools.treesitter

import com.gromozeka.infrastructure.ai.mcp.tools.GromozekaMcpTool
import com.gromozeka.infrastructure.ai.treesitter.ProjectRegistryImpl
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.buildJsonObject
import org.springframework.stereotype.Service

@Service
class ListProjectsTool(
    private val projectRegistry: ProjectRegistryImpl
) : GromozekaMcpTool {

    override val definition = Tool(
        name = "ts_list_projects",
        description = """List all registered projects available for Tree-sitter analysis.
        
        Returns information about all projects that have been registered for code analysis,
        including their names, paths, descriptions, detected languages, and last scan times.""",
        inputSchema = Tool.Input(
            properties = buildJsonObject {},
            required = emptyList()
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        return try {
            val projects = projectRegistry.listProjects()
            
            if (projects.isEmpty()) {
                CallToolResult(
                    content = listOf(
                        TextContent("No projects registered. Use 'ts_register_project' to register a project for analysis.")
                    ),
                    isError = false
                )
            } else {
                val projectsText = projects.mapIndexed { index, project ->
                    """${index + 1}. ${project.name}
   Path: ${project.rootPath}
   Description: ${project.description ?: "None"}
   Languages: ${project.languages.joinToString(", ").ifEmpty { "None detected" }}
   Last scan: ${project.lastScanTime ?: "Unknown"}"""
                }.joinToString("\n\n")
                
                CallToolResult(
                    content = listOf(
                        TextContent("""Registered projects (${projects.size}):

$projectsText

Use these project names in other Tree-sitter tools for code analysis.""")
                    ),
                    isError = false
                )
            }
            
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent("Error listing projects: ${e.message}")),
                isError = true
            )
        }
    }
}
