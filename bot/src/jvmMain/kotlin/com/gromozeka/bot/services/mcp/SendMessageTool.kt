package com.gromozeka.bot.services.mcp

import com.gromozeka.bot.ui.viewmodel.AppViewModel
import com.gromozeka.shared.domain.message.ChatMessage
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service

@Service
class SendMessageTool(
    private val applicationContext: ApplicationContext,
) : GromozekaMcpTool {

    @Serializable
    data class Input(
        val message: String,
        val sender_tab_id: String? = null,
        val target_tab_id: String? = null,
        val set_as_current: Boolean = false,
    )

    override val definition = Tool(
        name = "send_message",
        description = "Send a message to a specific tab/session for inter-tab communication",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Message content to send")
                })
                put("sender_tab_id", buildJsonObject {
                    put("type", "string")
                    put("description", "ID of the sender tab (you received tab is in prompt)")
                })
                put("target_tab_id", buildJsonObject {
                    put("type", "string")
                    put("description", "ID of the target tab to send message to")
                })
                put("set_as_current", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to set target tab as current after sending (default: false)")
                    put("default", false)
                })
            },
            required = listOf("message")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val appViewModel = applicationContext.getBean(AppViewModel::class.java)
        val input = Json.decodeFromJsonElement<Input>(request.arguments)
        val senderTabId = input.sender_tab_id
        println("[SendMessageTool] Executing with senderTabId=$senderTabId, targetTabId=${input.target_tab_id}")

        if (input.target_tab_id == null) {
            return CallToolResult(
                content = listOf(TextContent("Error: either target_tab_id or target_session_id must be specified")),
                isError = true
            )
        }

        val tabs = appViewModel.tabs.first()

        // Find target tab
        val targetTabIndex = tabs.indexOfFirst { tab ->
            val tabUiState = tab.uiState.value
            tabUiState.tabId == input.target_tab_id
        }

        if (targetTabIndex == -1) {
            val identifier = input.target_tab_id
            return CallToolResult(
                content = listOf(TextContent("Error: target tab/session not found: $identifier")),
                isError = true
            )
        }

        val targetTab = tabs[targetTabIndex]

        // Determine sender based on provided senderTabId
        val sender = if (senderTabId != null) {
            ChatMessage.Sender.Tab(senderTabId)
        } else {
            ChatMessage.Sender.User
        }

        // Send message to target session via TabViewModel
        targetTab.sendMessageToSession(input.message, sender)
        println("[SendMessageTool] Sent message from senderTabId=$senderTabId to targetTab=${targetTab.uiState.value.tabId} (sessionId=${targetTab.sessionId})")

        // Switch to target tab if requested
        if (input.set_as_current) {
            appViewModel.selectTab(targetTabIndex)
        }

        val targetProjectPath = targetTab.projectPath
        val targetSessionUuid = targetTab.sessionId.value
        val tabIdInfo = input.target_tab_id
        val switchInfo = if (input.set_as_current) " (set as current)" else ""

        return CallToolResult(
            content = listOf(
                TextContent(
                    "Successfully sent message to tab $targetTabIndex ($tabIdInfo): $targetProjectPath$switchInfo\n" +
                            "Target session: $targetSessionUuid\n" +
                            "Message: ${input.message.take(100)}${if (input.message.length > 100) "..." else ""}"
                )
            ),
            isError = false
        )
    }
}