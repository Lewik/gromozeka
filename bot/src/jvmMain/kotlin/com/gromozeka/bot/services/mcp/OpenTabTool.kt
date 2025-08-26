package com.gromozeka.bot.services.mcp

import com.gromozeka.bot.ui.viewmodel.AppViewModel
import com.gromozeka.shared.domain.message.ChatMessage
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
import java.util.*
import kotlin.time.Clock

@Service
class OpenTabTool(
    private val applicationContext: ApplicationContext,
) : GromozekaMcpTool {

    @Serializable
    data class Input(
        val project_path: String,
        val initial_message: String? = null,
        val resume_session_id: String? = null,
        val set_as_current: Boolean = true,
        val parent_tab_id: String,
    )

    override val definition = Tool(
        name = "open_tab",
        description = "Open a new tab with Claude session for specified project. Note: User can see tab creation in UI, no need to report tab creation details.",
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
                    put("description", "ID of the parent (current) tab for inter-tab communication")
                })
            },
            required = listOf("project_path")
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val appViewModel = applicationContext.getBean(AppViewModel::class.java)
        val input = Json.decodeFromJsonElement<Input>(request.arguments)

        val messageText = input.initial_message ?: "Ready to work on this project"
        val chatMessage = ChatMessage(
            role = ChatMessage.Role.USER,
            content = listOf(ChatMessage.ContentItem.UserMessage(messageText)),
            instructions = emptyList(),
            sender = ChatMessage.Sender.Tab(input.parent_tab_id),
            uuid = UUID.randomUUID().toString(),
            timestamp = Clock.System.now(),
            llmSpecificMetadata = null
        )

        val newTabIndex = appViewModel.createTab(
            projectPath = input.project_path,
            resumeSessionId = input.resume_session_id,
            initialMessage = chatMessage,
            setAsCurrent = input.set_as_current
        )

        // Get information about the created tab
        val newTab = appViewModel.tabs.first()[newTabIndex]
        val tabId = newTab.uiState.first().tabId

        val focusStatus = if (input.set_as_current) "current" else "background"

        return CallToolResult(
            content = listOf(
                TextContent(
                    "Successfully opened new tab at index $newTabIndex ($focusStatus): ${input.project_path}\n" +
                            "Tab ID: $tabId\n" +
                            if (messageText.isNotBlank()) "\nMessage sent: ${messageText.take(150)}${if (messageText.length > 150) "..." else ""}" else ""
                )
            ),
            isError = false
        )
    }
}