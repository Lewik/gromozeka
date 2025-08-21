package com.gromozeka.bot.services.mcp

import com.gromozeka.bot.ui.viewmodel.AppViewModel
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service

@Service
class SwitchTabTool(
    private val applicationContext: ApplicationContext,
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
        val appViewModel = applicationContext.getBean(AppViewModel::class.java)
        val input = Json.decodeFromJsonElement<Input>(request.arguments)

        // Select tab and get the selected tab back
        val selectedTab = appViewModel.selectTab(input.tab_id) ?: return CallToolResult(
            content = listOf(TextContent("Tab not found: ${input.tab_id}")),
            isError = true
        )

        val sessionId = selectedTab.sessionId
        val projectPath = selectedTab.projectPath
        val currentTabs = appViewModel.tabs.first()
        val tabIndex = currentTabs.indexOf(selectedTab)

        return CallToolResult(
            content = listOf(TextContent("Successfully switched to tab $tabIndex (${input.tab_id}): $projectPath (Session ID: ${sessionId.value})")),
            isError = false
        )

    }
}