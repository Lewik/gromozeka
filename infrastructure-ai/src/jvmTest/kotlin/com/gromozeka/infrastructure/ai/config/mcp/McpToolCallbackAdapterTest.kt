package com.gromozeka.infrastructure.ai.config.mcp

import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpToolSnapshot
import com.gromozeka.domain.tool.AiToolResult
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY_AUTOMATIC
import com.gromozeka.domain.tool.TOOL_CONTEXT_TARGET_MESSAGE_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_THREAD_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_TOOL_NAME
import com.gromozeka.domain.tool.ToolExecutionContext
import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test
    fun convertsMcpMediaAndResourcesToTypedToolResults() {
        val image = byteArrayOf(1, 2, 3)
        val audio = byteArrayOf(4, 5, 6)
        val resource = byteArrayOf(7, 8, 9)

        val results = CallToolResult(
            content = listOf(
                TextContent("ready"),
                ImageContent(Base64.getEncoder().encodeToString(image), "image/png"),
                AudioContent(Base64.getEncoder().encodeToString(audio), "audio/mpeg"),
                EmbeddedResource(
                    TextResourceContents(
                        text = "resource text",
                        uri = "file:///tmp/context.md",
                        mimeType = "text/markdown",
                    )
                ),
                EmbeddedResource(
                    BlobResourceContents(
                        blob = Base64.getEncoder().encodeToString(resource),
                        uri = "file:///tmp/report.pdf?download=1",
                        mimeType = "application/pdf",
                    )
                ),
                ResourceLink(
                    name = "documentation",
                    uri = "https://example.test/docs",
                    title = "Docs",
                    description = "Reference documentation",
                ),
            )
        ).toAiToolResults("browser_take_screenshot")

        assertEquals(AiToolResult.Text("ready"), results[0])
        assertBinaryResult(
            expectedContent = image,
            expectedFileName = "browser_take_screenshot-2.png",
            expectedMediaType = "image/png",
            actual = results[1],
        )
        assertBinaryResult(
            expectedContent = audio,
            expectedFileName = "browser_take_screenshot-3.mp3",
            expectedMediaType = "audio/mpeg",
            actual = results[2],
        )
        assertEquals(
            AiToolResult.Text("[Resource: file:///tmp/context.md]\nresource text"),
            results[3],
        )
        assertBinaryResult(
            expectedContent = resource,
            expectedFileName = "report.pdf",
            expectedMediaType = "application/pdf",
            actual = results[4],
        )
        assertEquals(
            AiToolResult.Text(
                "[Resource Link: https://example.test/docs]\n" +
                    "Name: documentation\n" +
                    "Title: Docs\n" +
                    "Reference documentation"
            ),
            results[5],
        )
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

private fun assertBinaryResult(
    expectedContent: ByteArray,
    expectedFileName: String,
    expectedMediaType: String,
    actual: AiToolResult,
) {
    val binary = actual as AiToolResult.Binary
    assertContentEquals(expectedContent, binary.content)
    assertEquals(expectedFileName, binary.fileName)
    assertEquals(expectedMediaType, binary.mediaType)
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
