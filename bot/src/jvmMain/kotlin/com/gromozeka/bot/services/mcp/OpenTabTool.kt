package com.gromozeka.bot.services.mcp

import com.gromozeka.bot.ui.viewmodel.AppViewModel
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service

@Service
class OpenTabTool(
    private val appViewModel: AppViewModel
) : GromozekaMcpTool {
    
    @Serializable
    data class Input(
        val project_path: String,
        val initial_message: String? = null,
        val resume_session_id: String? = null,
        val set_as_current: Boolean = true,
        val parent_tab_id: String? = null
    )
    
    override val definition = Tool(
        name = "open_tab",
        description = "Open a new tab with Claude session for specified project",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("project_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Path to the project directory")
                })
                put("initial_message", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional initial message to send after creating session")
                })
                put("resume_session_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional Claude session ID to resume")
                })
                put("set_as_current", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to set the new tab as current/focused (default: true)")
                    put("default", true)
                })
                put("parent_tab_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional ID of the parent tab for inter-tab communication")
                })
            },
            required = listOf("project_path")
        ),
        outputSchema = null,
        annotations = null
    )
    
    override suspend fun execute(request: CallToolRequest): CallToolResult {
        return try {
            val input = Json.decodeFromJsonElement<Input>(request.arguments ?: JsonObject(emptyMap()))
            
            // Get current tab ID if parent_tab_id is not specified but we need to track the parent
            val effectiveParentTabId = input.parent_tab_id ?: run {
                val currentTab = appViewModel.currentTab.first()
                currentTab?.uiState?.first()?.tabId
            }
            
            // Construct message with parent tab information
            val messageWithParentInfo = if (effectiveParentTabId != null) {
                val parentPrefix = "You were created from tab with ID: $effectiveParentTabId"
                if (input.initial_message != null) {
                    "$parentPrefix\n\n${input.initial_message}"
                } else {
                    parentPrefix
                }
            } else {
                input.initial_message
            }
            
            val newTabIndex = appViewModel.createTab(
                projectPath = input.project_path,
                resumeSessionId = input.resume_session_id,
                initialMessage = messageWithParentInfo,
                parentTabId = effectiveParentTabId,
                setAsCurrent = input.set_as_current
            )
            
            // Get information about the created tab
            val newTab = appViewModel.tabs.first()[newTabIndex]
            val tabId = newTab.uiState.first().tabId
            val sessionId = newTab.sessionId.value
            
            val focusStatus = if (input.set_as_current) "current" else "background"
            val parentInfo = if (effectiveParentTabId != null) " (parent: $effectiveParentTabId)" else ""
            
            CallToolResult(
                content = listOf(TextContent(
                    "Successfully opened new tab at index $newTabIndex ($focusStatus): ${input.project_path}\n" +
                    "Tab ID: $tabId\n" +
                    "Session ID: $sessionId$parentInfo" +
                    if (messageWithParentInfo != null) "\nMessage sent: ${messageWithParentInfo.take(150)}${if (messageWithParentInfo.length > 150) "..." else ""}" else ""
                )),
                isError = false
            )
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent("Error opening tab: ${e.message}")),
                isError = true
            )
        }
    }
}