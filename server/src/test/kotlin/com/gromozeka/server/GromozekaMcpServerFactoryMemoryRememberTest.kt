package com.gromozeka.server

import com.gromozeka.application.service.memory.MEMORY_REMEMBER_TOOL_NAME
import com.gromozeka.application.service.memory.MEMORY_WRITE_SURFACE_CONTEXT_KEY
import com.gromozeka.domain.service.AiToolProvider
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ToolExecutionContext
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GromozekaMcpServerFactoryMemoryRememberTest {
    @Test
    fun `memory remember MCP schema exposes only explicit content inputs`() = withMcpTools(MEMORY_REMEMBER_TOOL_NAME) {
        val callback = CapturingToolCallback()
        val server = GromozekaMcpServerFactory(FakeToolProvider(callback).getTools()).create()

        val tool = server.tools.getValue(MEMORY_REMEMBER_TOOL_NAME).tool
        val properties = requireNotNull(requireNotNull(tool.inputSchema).properties)

        assertFalse(tool.description.orEmpty().contains("previous_user_message"))
        assertFalse(properties.containsKey("target"))
        assertFalse(properties.containsKey("target_message_id"))
        assertFalse(properties.containsKey("user_consent_confirmed"))
        assertTrue(properties.containsKey("text"))
        assertTrue(properties.containsKey("file_path"))
        assertTrue(properties.containsKey("raw_url"))
    }

    @Test
    fun `memory remember MCP call injects provided content target and user consent`() = withMcpTools(MEMORY_REMEMBER_TOOL_NAME) {
        val callback = CapturingToolCallback()
        val server = GromozekaMcpServerFactory(FakeToolProvider(callback).getTools()).create()

        runBlocking {
            val result = server.tools.getValue(MEMORY_REMEMBER_TOOL_NAME).callForTest(
                CallToolRequest(CallToolRequestParams(
                    name = MEMORY_REMEMBER_TOOL_NAME,
                    arguments = buildJsonObject {
                        put("text", "Remember that the API endpoint is https://example.test")
                    },
                ))
            )

            assertFalse(result.isError == true)
        }

        assertContains(callback.lastToolInput.orEmpty(), """"target":"provided_text"""")
        assertContains(callback.lastToolInput.orEmpty(), """"user_consent_confirmed":true""")
        assertEquals("mcp", callback.lastContext?.getString(MEMORY_WRITE_SURFACE_CONTEXT_KEY))
    }

    @Test
    fun `memory remember MCP call treats text with document type as provided document`() = withMcpTools(MEMORY_REMEMBER_TOOL_NAME) {
        val callback = CapturingToolCallback()
        val server = GromozekaMcpServerFactory(FakeToolProvider(callback).getTools()).create()

        runBlocking {
            val result = server.tools.getValue(MEMORY_REMEMBER_TOOL_NAME).callForTest(
                CallToolRequest(CallToolRequestParams(
                    name = MEMORY_REMEMBER_TOOL_NAME,
                    arguments = buildJsonObject {
                        put("text", "# API Notes")
                        put("document_type", "markdown")
                        put("source_ref", "docs/api.md")
                    },
                ))
            )

            assertFalse(result.isError == true)
        }

        assertContains(callback.lastToolInput.orEmpty(), """"target":"provided_document"""")
        assertContains(callback.lastToolInput.orEmpty(), """"source_ref":"docs/api.md"""")
    }

    @Test
    fun `memory remember MCP call rejects conversation targets`() = withMcpTools(MEMORY_REMEMBER_TOOL_NAME) {
        val callback = CapturingToolCallback()
        val server = GromozekaMcpServerFactory(FakeToolProvider(callback).getTools()).create()

        val result = runBlocking {
            server.tools.getValue(MEMORY_REMEMBER_TOOL_NAME).callForTest(
                CallToolRequest(CallToolRequestParams(
                    name = MEMORY_REMEMBER_TOOL_NAME,
                    arguments = buildJsonObject {
                        put("target", "previous_user_message")
                    },
                ))
            )
        }

        assertTrue(result.isError == true)
        assertContains((result.content.single() as TextContent).text.orEmpty(), "Unsupported fields: [target]")
        assertNull(callback.lastToolInput)
    }

    @Test
    fun `memory remember MCP call rejects multiple explicit content inputs before callback`() = withMcpTools(MEMORY_REMEMBER_TOOL_NAME) {
        val callback = CapturingToolCallback()
        val server = GromozekaMcpServerFactory(FakeToolProvider(callback).getTools()).create()

        val result = runBlocking {
            server.tools.getValue(MEMORY_REMEMBER_TOOL_NAME).callForTest(
                CallToolRequest(CallToolRequestParams(
                    name = MEMORY_REMEMBER_TOOL_NAME,
                    arguments = buildJsonObject {
                        put("text", "Remember this")
                        put("raw_url", "https://example.test/memory.md")
                    },
                ))
            )
        }

        assertTrue(result.isError == true)
        assertContains((result.content.single() as TextContent).text.orEmpty(), "requires exactly one")
        assertNull(callback.lastToolInput)
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

    private class FakeToolProvider(
        private val callback: AiToolCallback,
    ) : AiToolProvider {
        override fun getTools(): List<AiToolCallback> = listOf(callback)
    }

    private class CapturingToolCallback : AiToolCallback {
        var lastToolInput: String? = null
        var lastContext: ToolExecutionContext? = null

        override val definition: AiToolDefinition = AiToolDefinition(
            name = MEMORY_REMEMBER_TOOL_NAME,
            description = "Internal memory remember description.",
            inputSchema = """{"type":"object","properties":{"target":{"type":"string"}}}""",
        )

        override fun call(toolInput: String, context: ToolExecutionContext?): String {
            lastToolInput = toolInput
            lastContext = context
            return """{"status":"completed"}"""
        }
    }
}
