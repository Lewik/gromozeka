package com.gromozeka.server

import com.gromozeka.application.service.memory.MEMORY_ENRICH_CONTEXT_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_ANSWER_QUESTION_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_EMBEDDING_STATUS_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_FORGET_SOURCE_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_LIST_NAMESPACES_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_MAINTENANCE_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_QUEUE_STATUS_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_REBUILD_EMBEDDINGS_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_REMEMBER_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_RUN_STATUS_TOOL_NAME
import com.gromozeka.domain.model.memory.MemoryNamespace
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ServerToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject

class GromozekaMcpServerFactoryMemoryHelpTest {
    @Test
    fun `explicitly enabled memory help returns domain guide`() = withClearedMcpTools {
        val server = GromozekaMcpServerFactory(DefaultMemoryToolProvider.getTools(), memoryToolsEnabled = true)
            .create(MemoryNamespace.Global)

        assertTrue(server.tools.containsKey(MCP_MEMORY_HELP_TOOL_NAME))

        val rememberDescription = server.tools.getValue(MEMORY_REMEMBER_TOOL_NAME).tool.description.orEmpty()
        assertContains(rememberDescription, "Use memory_help")
        assertFalse(rememberDescription.contains("claim/note/actionItem/source"))
        assertFalse(rememberDescription.contains("global, user:lewik"))

        val result = runBlocking {
            server.tools.getValue(MCP_MEMORY_HELP_TOOL_NAME).callForTest(
                CallToolRequest(CallToolRequestParams(
                    name = MCP_MEMORY_HELP_TOOL_NAME,
                    arguments = buildJsonObject {},
                ))
            )
        }

        assertFalse(result.isError == true)
        val text = (result.content.single() as TextContent).text.orEmpty()
        assertContains(text, "Gromozeka typed memory MCP guide")
        assertContains(text, "`claim`")
        assertContains(text, "`memory_enrich_context`")
        assertContains(text, "`memory_answer_question`")
        assertContains(text, "`memory_remember`")
        assertContains(text, "`memory_forget_source`")
        assertContains(text, "`memory_rebuild_embeddings`")
        assertContains(text, "`memory_embedding_status`")
        assertContains(text, "`memory_context`")
        assertContains(text, "namespace")
    }

    @Test
    fun `memory help is available in explicit MCP tool allowlist`() = withMcpTools(MCP_MEMORY_HELP_TOOL_NAME) {
        val server = GromozekaMcpServerFactory(EmptyToolProvider.getTools(), memoryToolsEnabled = true)
            .create(MemoryNamespace.Global)

        assertTrue(server.tools.containsKey(MCP_MEMORY_HELP_TOOL_NAME))
        assertFalse(server.tools.containsKey("memory_remember"))
    }

    @Test
    fun `memory tools and help are absent by default without changing native callbacks`() = withMcpTools("") {
        val native = DefaultMemoryToolProvider.getTools()
        val server = GromozekaMcpServerFactory(native).create(MemoryNamespace.Global)
        assertTrue(server.tools.isEmpty())
        assertEquals(10, native.size)
        assertTrue(native.all { it.available })
    }

    @Test
    fun `all wildcard and explicit allowlists cannot bypass disabled memory publication`() {
        for (configured in listOf("all", "*", GromozekaMcpToolExposure.MEMORY_TOOL_NAMES.joinToString(","))) {
            withMcpTools(configured) {
                val server = GromozekaMcpServerFactory(DefaultMemoryToolProvider.getTools())
                    .create(MemoryNamespace.Global)
                assertTrue(server.tools.isEmpty(), configured)
            }
        }
    }

    @Test
    fun `memory publication gate does not hide an unrelated MCP callback`() = withMcpTools("all") {
        val ordinary = SimpleToolCallback("test_non_memory")
        val server = GromozekaMcpServerFactory(DefaultMemoryToolProvider.getTools() + ordinary)
            .create(MemoryNamespace.Global)
        assertEquals(setOf("test_non_memory"), server.tools.keys)
        val result = runBlocking {
            server.tools.getValue("test_non_memory").callForTest(
                CallToolRequest(CallToolRequestParams(name = "test_non_memory", arguments = buildJsonObject {})))
        }
        assertFalse(result.isError == true)
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
            SimpleToolCallback(MEMORY_ANSWER_QUESTION_TOOL_NAME),
            SimpleToolCallback(MEMORY_LIST_NAMESPACES_TOOL_NAME),
            SimpleToolCallback(MEMORY_MAINTENANCE_TOOL_NAME),
            SimpleToolCallback(MEMORY_REBUILD_EMBEDDINGS_TOOL_NAME),
            SimpleToolCallback(MEMORY_EMBEDDING_STATUS_TOOL_NAME),
            SimpleToolCallback(MEMORY_REMEMBER_TOOL_NAME),
            SimpleToolCallback(MEMORY_FORGET_SOURCE_TOOL_NAME),
            SimpleToolCallback(MEMORY_RUN_STATUS_TOOL_NAME),
        )
    }

    private class SimpleToolCallback(
        name: String,
    ) : AiToolCallback {
        override val metadata = ServerToolMetadata
        override val definition: AiToolDefinition = AiToolDefinition(
            name = name,
            description = "$name test description",
            inputSchema = """{"type":"object","properties":{}}""",
        )

        override fun call(toolInput: String, context: ToolExecutionContext?): String =
            """{"status":"completed"}"""
    }
}
