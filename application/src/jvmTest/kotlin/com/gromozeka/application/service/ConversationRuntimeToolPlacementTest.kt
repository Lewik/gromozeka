package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ConversationRuntimeWorkerAffinity
import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConversationRuntimeToolPlacementTest {
    @Test
    fun `tool execution requirements include local agent capability for local tools`() {
        val affinity = ConversationRuntimeWorkerAffinity(
            kind = ConversationRuntimeWorkerAffinity.Kind.WORKSPACE,
            value = "/tmp/workspace",
        )
        val requirements = conversationRuntimeToolExecutionRequirements(
            toolCalls = listOf(toolCall("grz_read_file")),
            availableTools = listOf(
                tool(
                    name = "grz_read_file",
                    metadata = AiToolMetadata(
                        requiredRuntimeCapabilities = setOf(ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL)
                    ),
                )
            ),
            localAgentAffinity = affinity,
        )

        assertEquals(
            setOf(
                ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
            ),
            requirements.capabilities,
        )
        assertEquals(affinity, requirements.affinity)
    }

    @Test
    fun `tool execution requirements keep generic tools generic`() {
        val requirements = conversationRuntimeToolExecutionRequirements(
            toolCalls = listOf(toolCall("brave_web_search")),
            availableTools = listOf(tool("brave_web_search")),
        )

        assertEquals(
            setOf(ConversationRuntimeWorkerCapability.TOOL_EXECUTION),
            requirements.capabilities,
        )
    }

    @Test
    fun `tool execution requirements fail fast for unknown tools`() {
        assertFailsWith<IllegalStateException> {
            conversationRuntimeToolExecutionRequirements(
                toolCalls = listOf(toolCall("missing_tool")),
                availableTools = emptyList(),
            )
        }
    }

    private fun toolCall(name: String): Conversation.Message.ContentItem.ToolCall =
        Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id("call-$name"),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = name,
                input = JsonObject(emptyMap()),
            ),
        )

    private fun tool(
        name: String,
        metadata: AiToolMetadata = AiToolMetadata(),
    ): AiToolCallback =
        object : AiToolCallback {
            override val definition = AiToolDefinition(
                name = name,
                description = "test tool",
                inputSchema = "{}",
            )
            override val metadata = metadata
            override fun call(toolInput: String, context: ToolExecutionContext?): String = "ok"
        }
}
