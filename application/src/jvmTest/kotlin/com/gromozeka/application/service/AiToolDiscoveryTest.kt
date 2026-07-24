package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AiToolSearchServiceTest {
    private val service = AiToolSearchService()

    @Test
    fun `ranks realistic capability matches within top eight`() {
        val tools = listOf(
            tool("grz_execute_command", "Run shell commands and long-lived processes on a workspace."),
            tool("grz_get_command_task", "Read incremental output from a running command task.", "next_output_byte"),
            tool("grz_cancel_command_task", "Cancel a running command and terminate its process tree."),
            tool("grz_read_file", "Read file contents from a filesystem workspace.", "path offset limit"),
            tool("grz_write_file", "Create or overwrite a file in a filesystem workspace.", "path content"),
            tool("grz_edit_file", "Replace exact text in an existing file.", "path old_string new_string"),
            tool("memory_remember", "Remember durable user facts, preferences, and project decisions."),
            tool("memory_enrich_context", "Recall memory that is relevant to the current context."),
            tool("memory_answer_question", "Answer a question using long-term memory."),
            tool("mcp__calendar__create_event", "Create and schedule a calendar event or meeting."),
            tool("mcp__calendar__list_events", "List calendar events for a date range."),
            tool("mcp__gmail__send_email", "Send an email message to recipients."),
            tool("mcp__gmail__search_email", "Search email messages and threads."),
            tool("mcp__github__create_issue", "Create a GitHub issue in a repository."),
            tool("mcp__github__search_code", "Search source code hosted on GitHub."),
            tool("activate_agent_skill", "Load the instructions for an Agent Skill."),
            tool("read_agent_skill_resource", "Read a resource bundled with an Agent Skill."),
            tool("grz_find_definition", "Find the definition of a code symbol."),
            tool("grz_find_references", "Find references to a code symbol."),
            tool("grz_get_diagnostics", "Get compiler diagnostics for source code."),
            tool("grz_get_hover", "Get type and documentation information for a code symbol."),
            tool("grz_create_filesystem_workspace", "Create a filesystem workspace."),
            tool("grz_attach_filesystem_workspace", "Attach a workspace to an exact worker mount."),
            tool("list_tabs", "List open application tabs."),
        )

        val expectations = mapOf(
            "run a long shell command" to "grz_execute_command",
            "read file contents from workspace" to "grz_read_file",
            "schedule a calendar meeting" to "mcp__calendar__create_event",
            "remember durable user preferences" to "memory_remember",
            "send an email to a recipient" to "mcp__gmail__send_email",
            "next output byte from a running process" to "grz_get_command_task",
        )

        expectations.forEach { (query, expected) ->
            val names = service.search(tools, query).map { it.tool.definition.name }
            assertTrue(expected in names, "Expected $expected in top results for '$query', got $names")
        }
    }

    @Test
    fun `exact tool name is ranked first`() {
        val tools = listOf(
            tool("grz_read_file", "Read file contents."),
            tool("read_agent_skill_resource", "Read a resource file from an Agent Skill."),
            tool("mcp__drive__read_file", "Read a Google Drive file."),
        )

        val result = service.search(tools, "grz_read_file")

        assertEquals("grz_read_file", result.first().tool.definition.name)
    }

    @Test
    fun `supports unicode queries without language-specific word lists`() {
        val tools = listOf(
            tool("memory_enrich_context", "Вспомнить релевантные предпочтения и контекст пользователя."),
            tool("grz_read_file", "Прочитать содержимое файла."),
        )

        val result = service.search(tools, "вспомнить предпочтения")

        assertEquals("memory_enrich_context", result.first().tool.definition.name)
    }

    @Test
    fun `returns no matches for unrelated vocabulary`() {
        val tools = listOf(
            tool("grz_read_file", "Read file contents."),
            tool("memory_remember", "Remember durable user preferences."),
        )

        assertTrue(service.search(tools, "xylophone nebula quasar").isEmpty())
    }

    @Test
    fun `validates query and limit`() {
        assertFailsWith<IllegalArgumentException> {
            service.search(emptyList(), " ")
        }
        assertFailsWith<IllegalArgumentException> {
            service.search(emptyList(), "files", limit = SEARCH_TOOLS_MAX_LIMIT + 1)
        }
    }
}

class AiToolRuntimeCatalogServiceTest {
    private val service = AiToolRuntimeCatalogService()
    private val conversationId = Conversation.Id("conversation")
    private val instant = Instant.fromEpochMilliseconds(0)

    @Test
    fun `starts with search and agent pinned tools`() {
        val catalog = catalog(
            tool(SEARCH_TOOLS_TOOL_NAME, "Search tools."),
            tool("grz_read_file", "Read files."),
            tool("grz_write_file", "Write files."),
        )

        val available = service.availableTools(
            agent = agent(pinnedTools = listOf("grz_read_file")),
            catalog = catalog,
            messages = emptyList(),
        )

        assertEquals(listOf("grz_read_file", SEARCH_TOOLS_TOOL_NAME), available.map { it.definition.name })
    }

    @Test
    fun `loads tools returned by a paired search result`() {
        val callId = Conversation.Message.ContentItem.ToolCall.Id("search-call")
        val messages = listOf(
            message(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(
                    Conversation.Message.ContentItem.ToolCall(
                        id = callId,
                        call = Conversation.Message.ContentItem.ToolCall.Data(
                            name = SEARCH_TOOLS_TOOL_NAME,
                            input = buildJsonObject { put("query", "write files") },
                        ),
                    )
                ),
            ),
            message(
                role = Conversation.Message.Role.USER,
                content = listOf(
                    Conversation.Message.ContentItem.ToolResult(
                        toolUseId = callId,
                        toolName = SEARCH_TOOLS_TOOL_NAME,
                        result = listOf(
                            Conversation.Message.ContentItem.ToolResult.Data.Text(
                                """{"tools":[{"name":"grz_write_file"}]}"""
                            )
                        ),
                    )
                ),
            ),
        )
        val catalog = catalog(
            tool(SEARCH_TOOLS_TOOL_NAME, "Search tools."),
            tool("grz_read_file", "Read files."),
            tool("grz_write_file", "Write files."),
        )

        val available = service.availableTools(
            agent = agent(pinnedTools = listOf("grz_read_file")),
            catalog = catalog,
            messages = messages,
        )

        assertEquals(
            listOf("grz_read_file", "grz_write_file", SEARCH_TOOLS_TOOL_NAME),
            available.map { it.definition.name },
        )
    }

    @Test
    fun `does not trust orphaned or malformed search results`() {
        val messages = listOf(
            message(
                role = Conversation.Message.Role.USER,
                content = listOf(
                    Conversation.Message.ContentItem.ToolResult(
                        toolUseId = Conversation.Message.ContentItem.ToolCall.Id("missing-call"),
                        toolName = SEARCH_TOOLS_TOOL_NAME,
                        result = listOf(
                            Conversation.Message.ContentItem.ToolResult.Data.Text(
                                """{"tools":[{"name":"grz_write_file"}]}"""
                            ),
                            Conversation.Message.ContentItem.ToolResult.Data.Text("not-json"),
                        ),
                    )
                ),
            )
        )
        val catalog = catalog(
            tool(SEARCH_TOOLS_TOOL_NAME, "Search tools."),
            tool("grz_write_file", "Write files."),
        )

        val available = service.availableTools(agent(), catalog, messages)

        assertEquals(listOf(SEARCH_TOOLS_TOOL_NAME), available.map { it.definition.name })
    }

    @Test
    fun `resets discovered tools after compaction`() {
        val callId = Conversation.Message.ContentItem.ToolCall.Id("search-call")
        val messages = listOf(
            message(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(
                    Conversation.Message.ContentItem.ToolCall(
                        id = callId,
                        call = Conversation.Message.ContentItem.ToolCall.Data(
                            name = SEARCH_TOOLS_TOOL_NAME,
                            input = JsonObject(emptyMap()),
                        ),
                    )
                ),
            ),
            message(
                role = Conversation.Message.Role.USER,
                content = listOf(
                    Conversation.Message.ContentItem.ToolResult(
                        toolUseId = callId,
                        toolName = SEARCH_TOOLS_TOOL_NAME,
                        result = listOf(
                            Conversation.Message.ContentItem.ToolResult.Data.Text(
                                """{"tools":[{"name":"grz_write_file"}]}"""
                            )
                        ),
                    )
                ),
            ),
            message(
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(
                    Conversation.Message.ContentItem.ContextCompactionResult(
                        payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary(
                            "Earlier conversation."
                        ),
                        origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY,
                    )
                ),
            ),
        )
        val catalog = catalog(
            tool(SEARCH_TOOLS_TOOL_NAME, "Search tools."),
            tool("grz_write_file", "Write files."),
        )

        val available = service.availableTools(agent(), catalog, messages)

        assertEquals(listOf(SEARCH_TOOLS_TOOL_NAME), available.map { it.definition.name })
    }

    @Test
    fun `fails fast when a pinned tool is unavailable`() {
        val catalog = catalog(tool(SEARCH_TOOLS_TOOL_NAME, "Search tools."))

        assertFailsWith<IllegalArgumentException> {
            service.availableTools(
                agent = agent(pinnedTools = listOf("missing_tool")),
                catalog = catalog,
                messages = emptyList(),
            )
        }
    }

    private fun agent(pinnedTools: List<String> = emptyList()): AgentDefinition =
        AgentDefinition(
            id = AgentDefinition.Id("agent"),
            projectId = Project.Id("project"),
            name = "Test",
            prompts = emptyList(),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model")),
            tools = pinnedTools,
            type = AgentDefinition.Type.Project,
            createdAt = instant,
            updatedAt = instant,
        )

    private fun catalog(vararg tools: AiToolCallback): DistributedAiToolCatalogSnapshot =
        DistributedAiToolCatalogSnapshot(
            tools = tools.toList(),
            entries = emptyMap(),
            registrations = emptyList(),
            environmentRevision = "revision",
            environmentPrompt = "",
        )

    private fun message(
        role: Conversation.Message.Role,
        content: List<Conversation.Message.ContentItem>,
    ): Conversation.Message =
        Conversation.Message(
            id = Conversation.Message.Id("message-${role.name}-${content.hashCode()}"),
            conversationId = conversationId,
            role = role,
            content = content,
            createdAt = instant,
        )
}

private fun tool(
    name: String,
    description: String,
    parameterDescription: String = "",
): AiToolCallback =
    object : AiToolCallback {
        override val definition = AiToolDefinition(
            name = name,
            description = description,
            inputSchema = """
                {
                  "type": "object",
                  "properties": {
                    "value": {
                      "type": "string",
                      "description": "$parameterDescription"
                    }
                  }
                }
            """.trimIndent(),
        )

        override fun call(toolInput: String, context: ToolExecutionContext?): String =
            error("Not used")
    }
