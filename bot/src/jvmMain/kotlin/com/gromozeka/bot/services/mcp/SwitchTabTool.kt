package com.gromozeka.bot.services.mcp

import com.gromozeka.application.service.TabManager
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
class SwitchTabTool(
    private val tabManager: TabManager,
) : GromozekaMcpTool {

    @Serializable
    data class Input(
        val tab_id: String,
    )

    override val definition = Tool(
        name = "switch_tab",
        description = "Switch to specified tab id",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("tab_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Id of the tab to switch to")
                })
            },
            required = listOf("tab_id")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = Json.decodeFromJsonElement<Input>(request.arguments)

        // Switch to tab and get the tab info
        val selectedTab = tabManager.switchToTab(input.tab_id) ?: return CallToolResult(
            content = listOf(TextContent("Tab not found: ${input.tab_id}")),
            isError = true
        )

        val threadId = selectedTab.conversationId
        val projectPath = selectedTab.projectPath
        val allTabs = tabManager.listTabs()
        val tabIndex = allTabs.indexOfFirst { it.tabId == input.tab_id }

        return CallToolResult(
            content = listOf(TextContent("Successfully switched to tab $tabIndex (${input.tab_id}): $projectPath (Thread ID: ${threadId.value})")),
            isError = false
        )
    }
}