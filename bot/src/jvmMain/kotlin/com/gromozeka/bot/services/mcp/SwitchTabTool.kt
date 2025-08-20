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
class SwitchTabTool(
    private val appViewModel: AppViewModel
) : GromozekaMcpTool {
    
    @Serializable
    data class Input(
        val tab_index: Int
    )
    
    override val definition = Tool(
        name = "switch_tab",
        description = "Switch to specified tab by index",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("tab_index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Index of the tab to switch to (0-based)")
                    put("minimum", 0)
                })
            },
            required = listOf("tab_index")
        ),
        outputSchema = null,
        annotations = null
    )
    
    override suspend fun execute(request: CallToolRequest): CallToolResult {
        return try {
            val input = Json.decodeFromJsonElement<Input>(request.arguments ?: JsonObject(emptyMap()))
            
            val currentTabs = appViewModel.tabs.first()
            
            if (input.tab_index < 0 || input.tab_index >= currentTabs.size) {
                return CallToolResult(
                    content = listOf(TextContent("Error: tab_index ${input.tab_index} out of bounds (0..${currentTabs.size - 1})")),
                    isError = true
                )
            }
            
            appViewModel.selectTab(input.tab_index)
            
            val selectedTab = currentTabs[input.tab_index]
            val sessionId = selectedTab.sessionId
            val projectPath = selectedTab.projectPath
            
            CallToolResult(
                content = listOf(TextContent("Successfully switched to tab ${input.tab_index}: $projectPath (Session ID: ${sessionId.value})")),
                isError = false
            )
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent("Error switching tab: ${e.message}")),
                isError = true
            )
        }
    }
}