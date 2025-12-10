package com.gromozeka.infrastructure.ai.mcp.tools.treesitter

import com.gromozeka.infrastructure.ai.mcp.tools.GromozekaMcpTool
import com.gromozeka.infrastructure.ai.treesitter.LanguageRegistryImpl
import com.gromozeka.infrastructure.ai.treesitter.ProjectRegistryImpl
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
import java.nio.file.Paths

@Service
class RegisterProjectTool(
    private val projectRegistry: ProjectRegistryImpl,
    private val languageRegistry: LanguageRegistryImpl
) : GromozekaMcpTool {

    @Serializable
    data class Input(
        val path: String,
        val name: String? = null,
        val description: String? = null
    )

    override val definition = Tool(
        name = "ts_register_project",
        description = """Register a project directory for Tree-sitter code analysis.
        
        This tool registers a project directory, scans it for supported programming languages,
        and makes it available for code analysis operations like AST parsing.
        
        Currently supported languages: Kotlin only (more languages coming soon)""",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Path to the project directory to register")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional name for the project (defaults to directory name)")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional description of the project")
                })
            },
            required = listOf("path")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = Json.decodeFromJsonElement<Input>(request.arguments)
        
        return try {
            // Register the project
            val projectName = input.name ?: Paths.get(input.path).fileName.toString()
            val projectInfo = projectRegistry.registerProject(
                name = projectName,
                path = input.path,
                description = input.description
            )
            
            // Scan for languages
            val projectPath = Paths.get(input.path).toAbsolutePath().normalize()
            val languages = languageRegistry.scanProjectLanguages(projectPath)
            
            // Update project with discovered languages
            projectRegistry.updateProjectLanguages(projectName, languages)
            
            // Return success with project information
            CallToolResult(
                content = listOf(
                    TextContent("""Project registered successfully:
                    
Name: ${projectInfo.name}
Path: ${projectInfo.rootPath}
Description: ${projectInfo.description ?: "None"}
Languages detected: ${languages.joinToString(", ").ifEmpty { "None" }}
Scan time: ${projectInfo.lastScanTime}

The project is now ready for Tree-sitter analysis operations.""")
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
                content = listOf(TextContent("Unexpected error: ${e.message}")),
                isError = true
            )
        }
    }
}
