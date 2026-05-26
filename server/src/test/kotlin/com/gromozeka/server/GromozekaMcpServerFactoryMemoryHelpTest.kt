package com.gromozeka.server

import com.gromozeka.application.service.memory.MEMORY_ENRICH_CONTEXT_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_LIST_NAMESPACES_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_MAINTENANCE_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_QUEUE_STATUS_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_REMEMBER_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_RUN_STATUS_TOOL_NAME
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject

class GromozekaMcpServerFactoryMemoryHelpTest {
    @Test
    fun `memory help is exposed by default and returns domain guide`() = withClearedMcpTools {
        val server = GromozekaMcpServerFactory(DefaultMemoryToolProvider).create()

        assertTrue(server.tools.containsKey(MCP_MEMORY_HELP_TOOL_NAME))

        val rememberDescription = server.tools.getValue(MEMORY_REMEMBER_TOOL_NAME).tool.description.orEmpty()
        assertContains(rememberDescription, "Use memory_help")
        assertFalse(rememberDescription.contains("claim/note/task/source"))
        assertFalse(rememberDescription.contains("global, user:lewik"))

        val result = runBlocking {
            server.tools.getValue(MCP_MEMORY_HELP_TOOL_NAME).handler(
                CallToolRequest(
                    name = MCP_MEMORY_HELP_TOOL_NAME,
                    arguments = buildJsonObject {},
                )
            )
        }

        assertFalse(result.isError == true)
        val text = (result.content.single() as TextContent).text.orEmpty()
        assertContains(text, "Gromozeka typed memory MCP guide")
        assertContains(text, "`claim`")
        assertContains(text, "`memory_enrich_context`")
        assertContains(text, "`memory_remember`")
        assertContains(text, "`memory_context`")
        assertContains(text, "namespace")
    }

    @Test
    fun `memory help is available in explicit MCP tool allowlist`() = withMcpTools(MCP_MEMORY_HELP_TOOL_NAME) {
        val server = GromozekaMcpServerFactory(EmptyToolProvider).create()

        assertTrue(server.tools.containsKey(MCP_MEMORY_HELP_TOOL_NAME))
        assertFalse(server.tools.containsKey("memory_remember"))
    }

    private fun withClearedMcpTools(block: () -> Unit) {
        val property = "gromozeka.mcp.exposed.tools"
        val previous = System.getProperty(property)
        try {
            System.clearProperty(property)
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(property)
            } else {
                System.setProperty(property, previous)
            }
        }
    }

    private fun withMcpTools(value: String, block: () -> Unit) {
        val property = "gromozeka.mcp.exposed.tools"
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, value)
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(property)
            } else {
                System.setProperty(property, previous)
            }
        }
    }

    private object EmptyToolProvider : AiToolProvider {
        override fun getTools(): List<AiToolCallback> = emptyList()
    }

    private object DefaultMemoryToolProvider : AiToolProvider {
        override fun getTools(): List<AiToolCallback> = listOf(
            SimpleToolCallback(MEMORY_QUEUE_STATUS_TOOL_NAME),
            SimpleToolCallback(MEMORY_ENRICH_CONTEXT_TOOL_NAME),
            SimpleToolCallback(MEMORY_LIST_NAMESPACES_TOOL_NAME),
            SimpleToolCallback(MEMORY_MAINTENANCE_TOOL_NAME),
            SimpleToolCallback(MEMORY_REMEMBER_TOOL_NAME),
            SimpleToolCallback(MEMORY_RUN_STATUS_TOOL_NAME),
        )
    }

    private class SimpleToolCallback(
        name: String,
    ) : AiToolCallback {
        override val definition: AiToolDefinition = AiToolDefinition(
            name = name,
            description = "$name test description",
            inputSchema = """{"type":"object","properties":{}}""",
        )

        override fun call(toolInput: String, context: ToolExecutionContext?): String =
            """{"status":"completed"}"""
    }
}
