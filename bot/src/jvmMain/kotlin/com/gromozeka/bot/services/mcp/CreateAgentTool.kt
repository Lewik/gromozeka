package com.gromozeka.bot.services.mcp

import com.gromozeka.bot.services.DefaultAgentProvider
import com.gromozeka.bot.ui.viewmodel.AppViewModel
import com.gromozeka.bot.ui.state.ConversationInitiator
import com.gromozeka.shared.domain.conversation.ConversationTree
import com.gromozeka.shared.services.AgentService
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
class CreateAgentTool(
    private val applicationContext: ApplicationContext,
    private val agentService: AgentService,
    private val defaultAgentProvider: DefaultAgentProvider,
) : GromozekaMcpTool {

    @Serializable
    data class Input(
        val project_path: String,
        val initial_message: String? = null,
        val set_as_current: Boolean = true,
        val parent_tab_id: String,
        val agent_name: String,
        val agent_prompt: String? = null,
        val expects_response: Boolean = false,
    )

    override val definition = Tool(
        name = "create_agent",
        description = "Create a new agent colleague to work on specified project. The agent will appear in a new tab for conversation.",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                put("project_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Path to the project directory for the agent to work on")
                })
                put("initial_message", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional initial message to send to the new agent")
                })
                put("set_as_current", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to switch focus to the new agent conversation (default: true)")
                    put("default", true)
                })
                put("parent_tab_id", buildJsonObject {
                    put("type", "string")
                    put("description", "ID of the current agent (for tracking who created this agent)")
                })
                put("agent_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Name for the new agent (e.g. 'Code Reviewer', 'Security Expert', 'Researcher')")
                })
                put("agent_prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "Custom system prompt defining the agent's role and behavior. If not provided, creates default Gromozeka agent")
                })
                put("expects_response", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether parent agent expects a response back from the created agent (default: false)")
                    put("default", false)
                })
            },
            required = listOf(
                "project_path",
                "parent_tab_id",
                "agent_name",
            )
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val appViewModel = applicationContext.getBean(AppViewModel::class.java)
        val input = Json.decodeFromJsonElement<Input>(request.arguments)

        val agent = if (input.agent_prompt != null) {
            agentService.createAgent(
                name = input.agent_name,
                systemPrompt = input.agent_prompt,
                description = "Created via MCP from parent tab: ${input.parent_tab_id}",
                isBuiltin = false
            )
        } else {
            defaultAgentProvider.getDefault()
        }

        val baseMessageText = input.initial_message ?: "Ready to work on this project"

        // Create instructions for the new agent
        val allInstructions = mutableListOf<ConversationTree.Message.Instruction>()

        // Add source instruction (replaces sender)
        allInstructions.add(ConversationTree.Message.Instruction.Source.Agent(input.parent_tab_id))

        // Add response expected instruction if needed
        if (input.expects_response) {
            allInstructions.add(ConversationTree.Message.Instruction.ResponseExpected(targetTabId = input.parent_tab_id))
        }

        val chatMessage = ConversationTree.Message(
            role = ConversationTree.Message.Role.USER,
            content = listOf(ConversationTree.Message.ContentItem.UserMessage(baseMessageText)),
            instructions = allInstructions,
            id = ConversationTree.Message.Id(UUID.randomUUID().toString()),
            timestamp = Clock.System.now()
        )

        val newTabIndex = appViewModel.createTab(
            projectPath = input.project_path,
            agent = agent,
            conversationId = null,
            initialMessage = chatMessage,
            setAsCurrent = input.set_as_current,
            initiator = ConversationInitiator.Agent(input.parent_tab_id)
        )

        // Get information about the created tab
        val newTab = appViewModel.tabs.first()[newTabIndex]
        val tabId = newTab.uiState.first().tabId

        val agentInfo = input.agent_name ?: "Gromozeka"
        
        return CallToolResult(
            content = listOf(
                TextContent(
                    "Successfully created agent '$agentInfo' for project: ${input.project_path}\n" +
                            "Agent ID: $tabId (${if (input.set_as_current) "focused" else "background"})\n" +
                            if (input.expects_response) "Expecting response back to parent agent\n" else "" +
                            if (baseMessageText.isNotBlank()) "\nInitial message sent: ${baseMessageText.take(150)}${if (baseMessageText.length > 150) "..." else ""}" else ""
                )
            ),
            isError = false
        )
    }
}