package com.gromozeka.bot.services.mcp

import com.gromozeka.bot.ui.viewmodel.AppViewModel
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service

@Service
class ListTabsTool(
    private val applicationContext: ApplicationContext,
) : GromozekaMcpTool {

    override val definition = Tool(
        name = "list_tabs",
        description = "Get list of all open tabs with their information",
        inputSchema = Tool.Input(
            properties = buildJsonObject {},
            required = emptyList()
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val appViewModel = applicationContext.getBean(AppViewModel::class.java)
        val tabViewModels = appViewModel.tabs.first()

        val responseText = if (tabViewModels.isEmpty()) {
            "No active tabs found."
        } else {
            "Active tabs (${tabViewModels.size}):\n" +
                    tabViewModels.mapIndexed { index, tabViewModel ->
                        val uiState = tabViewModel.uiState.first()
                        val status = if (uiState.isWaitingForResponse) "(waiting)" else "(ready)"
                        val parentInfo = uiState.parentTabId?.let { " parent:$it" } ?: ""

                        "[$index] ${tabViewModel.projectPath} $status\n" +
                                "    Tab ID: ${uiState.tabId} | Session: ${tabViewModel.sessionId.value}$parentInfo"
                    }.joinToString("\n")
        }

        return CallToolResult(
            content = listOf(TextContent(responseText)),
            isError = false
        )
    }
}