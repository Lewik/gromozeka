package com.gromozeka.infrastructure.ai.mcp.tools

import com.gromozeka.domain.repository.TabManager
import com.gromozeka.domain.model.Tab
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import org.springframework.stereotype.Service

@Service
class ListTabsTool(
    private val tabManager: TabManager,
) : GromozekaMcpTool {

    override val definition = Tool(
        name = "list_tabs",
        description = "Get list of all open tabs with their information",
        inputSchema = ToolSchema(
            properties = buildJsonObject {},
            required = emptyList()
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val tabs = tabManager.listTabs()

        val responseText = if (tabs.isEmpty()) {
            "No active tabs found."
        } else {
            "Active tabs (${tabs.size}):\n" +
                    tabs.mapIndexed { index, tab ->
                        val status = if (tab.isWaitingForResponse) "(waiting)" else "(ready)"
                        val parentInfo = tab.parentTabId?.let { " parent:${it.value}" } ?: ""

                        "[$index] project=${tab.projectId.value} $status\n" +
                                "    Tab ID: ${tab.tabId.value} | Conversation: ${tab.conversationId.value}$parentInfo"
                    }.joinToString("\n")
        }

        return CallToolResult(
            content = listOf(TextContent(responseText)),
            isError = false
        )
    }
}
