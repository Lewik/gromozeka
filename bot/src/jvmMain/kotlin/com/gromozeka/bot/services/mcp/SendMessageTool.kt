package com.gromozeka.bot.services.mcp

import com.gromozeka.bot.ui.viewmodel.AppViewModel
import com.gromozeka.shared.domain.session.SessionUuid
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.springframework.stereotype.Service

@Service
class SendMessageTool(
    private val appViewModel: AppViewModel
) : GromozekaMcpTool {
    
    @Serializable
    data class Input(
        val message: String,
        val target_tab_id: String? = null,
        val target_session_id: String? = null,
        val set_as_current: Boolean = false
    )
    
    override val definition = Tool(
        name = "send_message",
        description = "Send a message to a specific tab/session for inter-tab communication",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("target_tab_id", buildJsonObject {
                    put("type", "string")
                    put("description", "ID of the target tab to send message to")
                })
                put("target_session_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Alternative: Session UUID to send message to")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Message content to send")
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
        return try {
            val input = Json.decodeFromJsonElement<Input>(request.arguments ?: JsonObject(emptyMap()))
            
            if (input.target_tab_id == null && input.target_session_id == null) {
                return CallToolResult(
                    content = listOf(TextContent("Error: either target_tab_id or target_session_id must be specified")),
                    isError = true
                )
            }
            
            val tabs = appViewModel.tabs.first()
            
            // Find target tab
            val targetTabIndex = if (input.target_tab_id != null) {
                // Find by tab ID
                tabs.indexOfFirst { tab ->
                    val tabUiState = tab.uiState.value
                    tabUiState.tabId == input.target_tab_id
                }
            } else {
                // Find by session ID
                val sessionUuid = SessionUuid(input.target_session_id!!)
                tabs.indexOfFirst { tab ->
                    tab.sessionId == sessionUuid
                }
            }
            
            if (targetTabIndex == -1) {
                val identifier = input.target_tab_id ?: input.target_session_id
                return CallToolResult(
                    content = listOf(TextContent("Error: target tab/session not found: $identifier")),
                    isError = true
                )
            }
            
            val targetTab = tabs[targetTabIndex]
            
            // Send message to target session via SessionViewModel
            targetTab.sendMessageToSession(input.message)
            
            // Switch to target tab if requested
            if (input.set_as_current) {
                appViewModel.selectTab(targetTabIndex)
            }
            
            val targetProjectPath = targetTab.projectPath
            val targetSessionUuid = targetTab.sessionId.value
            val tabIdInfo = input.target_tab_id ?: "session:${input.target_session_id}"
            val switchInfo = if (input.set_as_current) " (set as current)" else ""
            
            CallToolResult(
                content = listOf(TextContent(
                    "Successfully sent message to tab $targetTabIndex ($tabIdInfo): $targetProjectPath$switchInfo\n" +
                    "Target session: $targetSessionUuid\n" +
                    "Message: ${input.message.take(100)}${if (input.message.length > 100) "..." else ""}"
                )),
                isError = false
            )
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent("Error sending message: ${e.message}")),
                isError = true
            )
        }
    }
}