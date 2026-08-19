package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolLoadingPolicy
import com.gromozeka.domain.tool.ServerToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlin.time.Instant
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
            tool("open_agent_skill", "Open the instructions for an Agent Skill package."),
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
    fun `does not return management tools for missing materialization capability`() {
        val tools = listOf(
            tool("grz_skill_list", "List imported Agent Skills owned by one project."),
            tool("grz_skill_get", "Read one imported Agent Skill package."),
            tool("grz_skill_import_inline", "Import one Agent Skill package."),
        )

        assertTrue(service.search(tools, "materialize skill").isEmpty())
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
    fun `starts with core and agent configured tools`() {
        val catalog = catalog(
            tool(SEARCH_TOOLS_TOOL_NAME, "Search tools."),
            tool("grz_read_file", "Read files."),
            tool("custom_agent_tool", "Custom agent tool."),
            tool("on_demand_tool", "Discoverable only."),
        )

        val selection = service.selectTools(
            agent = agent(pinnedTools = listOf("custom_agent_tool")),
            catalog = catalog,
            messages = emptyList(),
            memoryEnabled = false,
        )

        assertEquals(
            listOf("custom_agent_tool", "grz_read_file", SEARCH_TOOLS_TOOL_NAME),
            selection.tools.map { it.definition.name },
        )
    }

    @Test
    fun `preloads the complete shell file command and monitor tool set`() {
        val expected = listOf(
            "grz_cancel_command_monitor",
            "grz_cancel_command_task",
            "grz_edit_file",
            "grz_execute_command",
            "grz_get_command_monitor",
            "grz_get_command_task",
            "grz_list_commands_and_monitors",
            "grz_monitor_command",
            "grz_read_file",
            "grz_write_file",
            SEARCH_TOOLS_TOOL_NAME,
        ).sorted()
        val catalog = catalog(*expected.map { tool(it, "$it description") }.toTypedArray())

        val selection = service.selectTools(agent(), catalog, emptyList(), memoryEnabled = false)

        assertEquals(expected, selection.tools.map { it.definition.name })
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
                            input = buildJsonObject { put("query", "calendar") },
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
                                """{"tools":[{"name":"calendar_create_event"}]}"""
                            )
                        ),
                    )
                ),
            ),
        )
        val catalog = catalog(
            tool(SEARCH_TOOLS_TOOL_NAME, "Search tools."),
            tool("grz_read_file", "Read files."),
            tool("calendar_create_event", "Create an event."),
        )

        val selection = service.selectTools(
            agent = agent(pinnedTools = listOf("grz_read_file")),
            catalog = catalog,
            messages = messages,
            memoryEnabled = false,
        )

        assertEquals(
            listOf("calendar_create_event", "grz_read_file", SEARCH_TOOLS_TOOL_NAME),
            selection.tools.map { it.definition.name },
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
                                """{"tools":[{"name":"custom_tool"}]}"""
                            ),
                            Conversation.Message.ContentItem.ToolResult.Data.Text("not-json"),
                        ),
                    )
                ),
            )
        )
        val catalog = catalog(
            tool(SEARCH_TOOLS_TOOL_NAME, "Search tools."),
            tool("custom_tool", "Custom tool."),
        )

        val selection = service.selectTools(agent(), catalog, messages, memoryEnabled = false)

        assertEquals(listOf(SEARCH_TOOLS_TOOL_NAME), selection.tools.map { it.definition.name })
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
                                """{"tools":[{"name":"custom_tool"}]}"""
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
            tool("custom_tool", "Custom tool."),
        )

        val selection = service.selectTools(agent(), catalog, messages, memoryEnabled = false)

        assertEquals(listOf(SEARCH_TOOLS_TOOL_NAME), selection.tools.map { it.definition.name })
    }

    @Test
    fun `reports an unavailable pinned tool without failing selection`() {
        val catalog = catalog(tool(SEARCH_TOOLS_TOOL_NAME, "Search tools."))

        val selection = service.selectTools(
            agent = agent(pinnedTools = listOf("missing_tool")),
            catalog = catalog,
            messages = emptyList(),
            memoryEnabled = false,
        )

        assertEquals(listOf(SEARCH_TOOLS_TOOL_NAME), selection.tools.map { it.definition.name })
        assertTrue("missing_tool" in selection.unavailableToolNames)
        assertTrue(selection.unavailableToolsSystemPrompt()!!.contains("\"missing_tool\""))
    }

    @Test
    fun `preloads available tools marked by runtime policy`() {
        val catalog = catalog(
            tool(SEARCH_TOOLS_TOOL_NAME, "Search tools."),
            tool(
                "preloaded_tool",
                "Always available.",
                loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE,
            ),
            tool("on_demand_tool", "Discoverable only."),
        )

        val selection = service.selectTools(agent(), catalog, emptyList(), memoryEnabled = false)

        assertEquals(
            listOf("preloaded_tool", SEARCH_TOOLS_TOOL_NAME),
            selection.tools.map { it.definition.name },
        )
    }

    @Test
    fun `preloads memory tools only when memory is enabled`() {
        val catalog = catalog(
            tool(SEARCH_TOOLS_TOOL_NAME, "Search tools."),
            tool(
                "memory_enrich_context",
                "Recall memory.",
                loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_MEMORY_ENABLED,
            ),
        )

        val disabled = service.selectTools(agent(), catalog, emptyList(), memoryEnabled = false)
        val enabled = service.selectTools(agent(), catalog, emptyList(), memoryEnabled = true)

        assertEquals(listOf(SEARCH_TOOLS_TOOL_NAME), disabled.tools.map { it.definition.name })
        assertEquals(
            listOf("memory_enrich_context", SEARCH_TOOLS_TOOL_NAME),
            enabled.tools.map { it.definition.name },
        )
    }

    @Test
    fun `configured logical names expand to active contract variants`() {
        val search = tool("search_tools__v2", "Search tools.")
        val read = tool("grz_read_file__v3", "Read files.")
        val catalog = DistributedAiToolCatalogSnapshot(
            tools = listOf(search, read),
            entries = mapOf(
                search.definition.name to DistributedAiTool(
                    descriptor = AiToolDescriptor(
                        search.definition.copy(name = SEARCH_TOOLS_TOOL_NAME),
                        search.metadata,
                    ),
                    workers = emptyList(),
                    logicalName = SEARCH_TOOLS_TOOL_NAME,
                    modelName = search.definition.name,
                    executionName = SEARCH_TOOLS_TOOL_NAME,
                ),
                read.definition.name to DistributedAiTool(
                    descriptor = AiToolDescriptor(
                        read.definition.copy(name = "grz_read_file"),
                        read.metadata,
                    ),
                    workers = emptyList(),
                    logicalName = "grz_read_file",
                    modelName = read.definition.name,
                    executionName = "grz_read_file",
                ),
            ),
            registrations = emptyList(),
            environmentRevision = "revision",
            environmentPrompt = "",
        )

        val selection = service.selectTools(agent(), catalog, emptyList(), memoryEnabled = false)

        assertEquals(
            listOf("grz_read_file__v3", "search_tools__v2"),
            selection.tools.map { it.definition.name },
        )
        assertTrue("grz_read_file" !in selection.unavailableToolNames)
        assertTrue(SEARCH_TOOLS_TOOL_NAME !in selection.unavailableToolNames)
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
    loadingPolicy: AiToolLoadingPolicy = AiToolLoadingPolicy.ON_DEMAND,
): AiToolCallback =
    object : AiToolCallback {
        override val metadata = ServerToolMetadata.copy(loadingPolicy = loadingPolicy)
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
