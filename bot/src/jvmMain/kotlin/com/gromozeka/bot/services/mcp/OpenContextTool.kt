package com.gromozeka.bot.services.mcp

import com.gromozeka.bot.services.ContextFileService
import com.gromozeka.bot.ui.viewmodel.AppViewModel
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service
import java.io.File

@Service
class OpenContextTool(
    private val applicationContext: ApplicationContext,
) : GromozekaMcpTool {

    @Serializable
    data class Input(
        val context_name: String,
        val action: String = "start_tab", // "start_tab", "view_file", "load_content"
    )

    override val definition = Tool(
        name = "open_context",
        description = """Open a context in different ways:
        
- start_tab: Create new tab with context loaded (default)
- view_file: Open context file in IDE  
- load_content: Return context content as text

Use 'list_contexts' first to see available contexts.""",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("context_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact name of the context to open (case-sensitive)")
                })
                put("action", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum", kotlinx.serialization.json.JsonArray(
                            listOf(
                                kotlinx.serialization.json.JsonPrimitive("start_tab"),
                                kotlinx.serialization.json.JsonPrimitive("view_file"),
                                kotlinx.serialization.json.JsonPrimitive("load_content")
                            )
                        )
                    )
                    put(
                        "description",
                        "Action to perform: start_tab (new tab), view_file (IDE), load_content (return text)"
                    )
                    put("default", "start_tab")
                })
            },
            required = listOf("context_name")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val contextFileService = applicationContext.getBean(ContextFileService::class.java)
        val appViewModel = applicationContext.getBean(AppViewModel::class.java)
        val input = Json.decodeFromJsonElement<Input>(request.arguments)

        // Find context by name
        val allContexts = contextFileService.listAllContexts()
        val context = allContexts.find { it.name == input.context_name }

        if (context == null) {
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

        return when (input.action) {
            "start_tab" -> {
                if (context.filePath.isEmpty()) {
                    return CallToolResult(
                        content = listOf(
                            TextContent(
                                "Context '${input.context_name}' has no file path. Cannot start tab."
                            )
                        ),
                        isError = true
                    )
                }

                val contextContent = contextFileService.loadContextContent(context.filePath)
                val currentTab = appViewModel.currentTab.value
                val parentTabId = currentTab?.uiState?.value?.tabId

                val tabIndex = appViewModel.createTab(
                    projectPath = context.projectPath,
                    initialMessage = contextContent,
                    parentTabId = parentTabId
                )

                CallToolResult(
                    content = listOf(
                        TextContent(
                            "Started new tab (${tabIndex}) with context '${input.context_name}' " +
                                    "for project ${context.projectPath}"
                        )
                    ),
                    isError = false
                )
            }

            "view_file" -> {
                if (context.filePath.isEmpty()) {
                    return CallToolResult(
                        content = listOf(
                            TextContent(
                                "Context '${input.context_name}' has no file path. Cannot view in IDE."
                            )
                        ),
                        isError = true
                    )
                }

                val file = File(context.filePath)
                if (!file.exists()) {
                    return CallToolResult(
                        content = listOf(
                            TextContent(
                                "Context file not found at: ${context.filePath}"
                            )
                        ),
                        isError = true
                    )
                }


                val command = listOf("idea", context.filePath)
                ProcessBuilder(command).start()

                CallToolResult(
                    content = listOf(
                        TextContent(
                            "Opened context '${input.context_name}' in IDE: ${context.filePath}"
                        )
                    ),
                    isError = false
                )

            }

            "load_content" -> {
                if (context.filePath.isEmpty()) {
                    // Return basic context info if no file
                    val basicContent = buildString {
                        appendLine("# ${context.name}")
                        appendLine("Project: ${context.projectPath}")
                        if (context.files.isNotEmpty()) {
                            appendLine("Files: ${context.files.keys.joinToString(", ")}")
                        }
                        if (context.links.isNotEmpty()) {
                            appendLine("Links: ${context.links.joinToString(", ")}")
                        }
                        appendLine()
                        appendLine(context.content)
                    }

                    CallToolResult(
                        content = listOf(TextContent(basicContent)),
                        isError = false
                    )
                } else {
                    val file = File(context.filePath)
                    if (!file.exists()) {
                        return CallToolResult(
                            content = listOf(
                                TextContent(
                                    "Context file not found at: ${context.filePath}"
                                )
                            ),
                            isError = true
                        )
                    }

                    val fileContent = contextFileService.loadContextContent(context.filePath)
                    CallToolResult(
                        content = listOf(TextContent(fileContent)),
                        isError = false
                    )
                }
            }

            else -> {
                CallToolResult(
                    content = listOf(
                        TextContent(
                            "Unknown action '${input.action}'. " +
                                    "Use: start_tab, view_file, or load_content"
                        )
                    ),
                    isError = true
                )
            }
        }

    }
}