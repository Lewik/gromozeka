package com.gromozeka.infrastructure.ai.mcp.tools

import com.gromozeka.domain.repository.TabManager
import com.gromozeka.domain.model.Tab
import com.gromozeka.domain.model.ConversationInitiator
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.AgentDomainService
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
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock

@Service
class CreateAgentTool(
    private val tabManager: TabManager,
    private val agentService: AgentDomainService,
    private val promptService: com.gromozeka.domain.service.PromptDomainService,
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
        val input = Json.decodeFromJsonElement<Input>(request.arguments)

        val agent = if (input.agent_prompt != null) {
            val inlinePrompt = promptService.createEnvironmentPrompt(
                name = "${input.agent_name} prompt",
                content = input.agent_prompt
            )
            agentService.createAgent(
                name = input.agent_name,
                prompts = listOf(inlinePrompt.id),
                description = "Created via MCP from parent tab: ${input.parent_tab_id}",
                type = AgentDefinition.Type.Inline
            )
        } else {
            agentService.findAll()
                .firstOrNull { it.type is AgentDefinition.Type.Builtin && it.name == "Gromozeka" }
                ?: error("Default agent 'Gromozeka' not found")
        }

        val baseMessageText = input.initial_message ?: "Ready to work on this project"

        // Create instructions for the new agent
        val allInstructions = mutableListOf<Conversation.Message.Instruction>()

        // Add source instruction (replaces sender)
        allInstructions.add(Conversation.Message.Instruction.Source.Agent(input.parent_tab_id))

        // Add response expected instruction if needed
        if (input.expects_response) {
            allInstructions.add(Conversation.Message.Instruction.ResponseExpected(targetTabId = input.parent_tab_id))
        }

        val chatMessage = Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = Conversation.Id(""), // Will be set when conversation is created
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(baseMessageText)),
            createdAt = Clock.System.now(),
            instructions = allInstructions
        )

        val newTabIndex = tabManager.createTab(
            projectPath = input.project_path,
            agent = agent,
            conversationId = null,
            initialMessage = chatMessage,
            setAsCurrent = input.set_as_current,
            initiator = ConversationInitiator.Agent(Tab.Id(input.parent_tab_id))
        )

        // Get information about the created tab
        val allTabs = tabManager.listTabs()
        val tabId = allTabs[newTabIndex].tabId

        val agentInfo = input.agent_name ?: "Gromozeka"
        
        return CallToolResult(
            content = listOf(
                TextContent(
                    "Successfully created agent '$agentInfo' for project: ${input.project_path}\n" +
                            "Agent ID: ${tabId.value} (${if (input.set_as_current) "focused" else "background"})\n" +
                            if (input.expects_response) "Expecting response back to parent agent\n" else "" +
                            if (baseMessageText.isNotBlank()) "\nInitial message sent: ${baseMessageText.take(150)}${if (baseMessageText.length > 150) "..." else ""}" else ""
                )
            ),
            isError = false
        )
    }
}
