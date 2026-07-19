package com.gromozeka.infrastructure.ai.mcp.tools

import com.gromozeka.domain.repository.TabManager
import com.gromozeka.domain.model.Tab
import com.gromozeka.domain.model.ConversationInitiator
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.SettingsProvider
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
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
    private val settingsProvider: SettingsProvider,
) : GromozekaMcpTool {

    @Serializable
    data class Input(
        val project_id: String,
        val workspace_id: String,
        val initial_message: String? = null,
        val set_as_current: Boolean = true,
        val parent_tab_id: String,
        val agent_name: String,
        val agent_prompt: String? = null,
        val expects_response: Boolean = false,
        val model_configuration_id: String? = null,
    )

    override val definition = Tool(
        name = "create_agent",
        description = "Create a new agent colleague in an explicit logical project and filesystem workspace. The agent appears in a new conversation tab.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("project_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Logical project ID for the new conversation")
                })
                put("workspace_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Filesystem workspace ID for the new conversation")
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
                put("model_configuration_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional server-side AI model configuration id. If omitted, uses the Default chat runtime assignment.")
                })
            },
            required = listOf(
                "project_id",
                "workspace_id",
                "parent_tab_id",
                "agent_name",
            )
        ),
        outputSchema = null,
        annotations = null
    )

    override suspend fun execute(request: CallToolRequest): CallToolResult {
        val input = Json.decodeFromJsonElement<Input>(request.argumentsOrEmpty())

        val agent = if (input.agent_prompt != null) {
            val inlinePrompt = promptService.createEnvironmentPrompt(
                name = "${input.agent_name} prompt",
                content = input.agent_prompt
            )
            agentService.createAgent(
                name = input.agent_name,
                prompts = listOf(inlinePrompt.id),
                runtimeSelection = input.model_configuration_id?.let {
                    AiRuntimeSelection(AiModelConfiguration.Id(it))
                } ?: settingsProvider.runtimeSelectionFor(AiRuntimeAssignment.Purpose.DEFAULT_CHAT),
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
            projectId = Project.Id(input.project_id),
            workspaceId = Workspace.Id(input.workspace_id),
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
                    "Successfully created agent '$agentInfo'\n" +
                            "Project ID: ${input.project_id}\n" +
                            "Workspace ID: ${input.workspace_id}\n" +
                            "Agent ID: ${tabId.value} (${if (input.set_as_current) "focused" else "background"})\n" +
                            if (input.expects_response) "Expecting response back to parent agent\n" else "" +
                            if (baseMessageText.isNotBlank()) "\nInitial message sent: ${baseMessageText.take(150)}${if (baseMessageText.length > 150) "..." else ""}" else ""
                )
            ),
            isError = false
        )
    }
}
