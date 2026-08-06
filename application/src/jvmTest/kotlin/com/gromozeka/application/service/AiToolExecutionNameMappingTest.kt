package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AiToolExecutionNameMappingTest {
    @Test
    fun `execution alias translation changes only the tool name`() {
        val input = buildJsonObject {
            put("query", "original body")
            putJsonObject(AI_TOOL_EXECUTION_TARGET_FIELD) {
                put(AI_TOOL_EXECUTION_WORKER_ID_FIELD, "worker-a")
            }
        }
        val call = Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id("call-1"),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = "mcp__browser__search__v2",
                input = input,
            ),
        )

        val translated = listOf(call).withExecutionToolNames(
            mapOf(call.id.value to "mcp__browser__search")
        ).single()

        assertEquals("mcp__browser__search", translated.call.name)
        assertEquals(input, translated.call.input)
        assertEquals(
            buildJsonObject { put("query", "original body") },
            translated.call.input.withoutExecutionTarget(),
        )
    }

    @Test
    fun `execution result is restored to the model alias`() {
        val result = Conversation.Message.ContentItem.ToolResult(
            toolUseId = Conversation.Message.ContentItem.ToolCall.Id("call-1"),
            toolName = "mcp__browser__search",
            result = listOf(
                Conversation.Message.ContentItem.ToolResult.Data.Text("ok")
            ),
            isError = false,
        )

        val translated = result.withModelToolName(
            mapOf(result.toolUseId.value to "mcp__browser__search__v2")
        )

        assertEquals("mcp__browser__search__v2", translated.toolName)
        assertEquals("mcp__browser__search", translated.executionToolName)
        assertEquals(result.result, translated.result)
        assertFalse(translated.isError)
    }
}
