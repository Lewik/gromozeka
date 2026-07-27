package com.gromozeka.infrastructure.ai.config.mcp

import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpToolSnapshot
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY_AUTOMATIC
import com.gromozeka.domain.tool.TOOL_CONTEXT_TARGET_MESSAGE_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_THREAD_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_TOOL_NAME
import com.gromozeka.domain.tool.ToolExecutionContext
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class McpToolCallbackAdapterTest {
    @Test
    fun forwardsOnlyGromozekaConversationContextWhenEnabled() {
        val arguments = mapOf<String, Any>(
            "target" to "previous_user_message",
            "_context" to mapOf("conversationId" to "spoofed"),
        ).withGrzConversationContext(
            context = ToolExecutionContext(
                mapOf(
                    TOOL_CONTEXT_CONVERSATION_ID to "conversation-1",
                    TOOL_CONTEXT_THREAD_ID to "thread-1",
                    TOOL_CONTEXT_TARGET_MESSAGE_ID to "message-1",
                    TOOL_CONTEXT_AGENT_DEFINITION_ID to "agent-1",
                    TOOL_CONTEXT_TOOL_NAME to "mcp__memory__memory_answer_question",
                    TOOL_CONTEXT_MEMORY_RESULT_DELIVERY to TOOL_CONTEXT_MEMORY_RESULT_DELIVERY_AUTOMATIC,
                    "projectId" to "project-1",
                )
            ),
            enabled = true,
        )

        assertEquals("previous_user_message", arguments["target"])
        assertEquals(
            mapOf(
                TOOL_CONTEXT_CONVERSATION_ID to "conversation-1",
                TOOL_CONTEXT_THREAD_ID to "thread-1",
                TOOL_CONTEXT_TARGET_MESSAGE_ID to "message-1",
                TOOL_CONTEXT_AGENT_DEFINITION_ID to "agent-1",
                TOOL_CONTEXT_TOOL_NAME to "mcp__memory__memory_answer_question",
                TOOL_CONTEXT_MEMORY_RESULT_DELIVERY to TOOL_CONTEXT_MEMORY_RESULT_DELIVERY_AUTOMATIC,
            ),
            arguments["_context"],
        )
    }

    @Test
    fun doesNotForwardGromozekaConversationContextByDefault() {
        val arguments = mapOf<String, Any>(
            "target" to "previous_user_message",
        ).withGrzConversationContext(
            context = ToolExecutionContext(
                mapOf(TOOL_CONTEXT_CONVERSATION_ID to "conversation-1")
            ),
            enabled = false,
        )

        assertFalse(arguments.containsKey("_context"))
    }

    @Test
    fun rejectsNonObjectArgumentsInsteadOfCallingRemoteTool() {
        val client = RecordingMcpConnectedClient()
        val callback = callback(client)

        assertFailsWith<IllegalStateException> {
            callback.call("""["not", "an", "object"]""")
        }
        assertEquals(null, client.arguments)
    }

    @Test
    fun preservesNullArgumentsForRemoteTool() {
        val client = RecordingMcpConnectedClient()
        val callback = callback(client)

        callback.call("""{"optional":null,"nested":{"value":null},"items":[null,"value"]}""")

        assertEquals(
            mapOf(
                "optional" to null,
                "nested" to mapOf("value" to null),
                "items" to listOf(null, "value"),
            ),
            client.arguments,
        )
    }

    @Test
    fun propagatesRemoteToolFailureToRuntimeErrorHandling() {
        val client = RecordingMcpConnectedClient(failure = IllegalStateException("remote failure"))
        val callback = callback(client)

        val error = assertFailsWith<IllegalStateException> {
            callback.call("""{"message":"hello"}""")
        }

        assertEquals("Error executing MCP tool echo: remote failure", error.message)
    }

    private fun callback(client: McpConnectedClient) =
        McpToolCallbackAdapter(
            serverId = McpServerId("test"),
            client = client,
            tool = McpToolSnapshot(
                remoteName = "echo",
                description = "Echo",
                inputSchema = """{"type":"object"}""",
            ),
        )
}

private class RecordingMcpConnectedClient(
    private val failure: Throwable? = null,
) : McpConnectedClient {
    override val serverInfo = Implementation(name = "test", version = "1")
    override val serverInstructions: String? = null
    override val supportsToolsListChanged: Boolean = false

    var arguments: Map<String, Any?>? = null
        private set

    override suspend fun listAllTools(): List<Tool> = emptyList()

    override suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any?>,
    ): String {
        failure?.let { throw it }
        this.arguments = arguments
        return "ok"
    }

    override fun setToolsListChangedHandler(handler: () -> Unit) = Unit

    override fun close() = Unit

    override fun forceClose() = Unit
}
