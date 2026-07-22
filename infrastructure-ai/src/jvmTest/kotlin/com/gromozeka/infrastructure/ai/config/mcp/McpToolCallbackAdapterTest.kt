package com.gromozeka.infrastructure.ai.config.mcp

import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY
import com.gromozeka.domain.tool.TOOL_CONTEXT_MEMORY_RESULT_DELIVERY_AUTOMATIC
import com.gromozeka.domain.tool.TOOL_CONTEXT_TARGET_MESSAGE_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_THREAD_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_TOOL_NAME
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
