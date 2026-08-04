package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkerToolExecutionFailureTest {
    @Test
    fun `transport failure becomes an error result for every tool without retrying`() {
        val calls = listOf(toolCall("call-1", "grz_computer_act"), toolCall("call-2", "other_tool"))

        val result = workerToolExecutionFailure(
            toolCalls = calls,
            error = IllegalStateException("Gateway timed out"),
        )

        assertFalse(result.returnDirect)
        assertEquals(calls.map { it.id }, result.results.map { it.toolUseId })
        assertTrue(result.results.all { it.isError })
        result.results.forEach { toolResult ->
            val message = (toolResult.result.single() as Conversation.Message.ContentItem.ToolResult.Data.Text).content
            assertTrue(message.contains("outcome is unknown"))
            assertTrue(message.contains("Do not retry automatically"))
        }
    }

    private fun toolCall(id: String, name: String): Conversation.Message.ContentItem.ToolCall =
        Conversation.Message.ContentItem.ToolCall(
            id = Conversation.Message.ContentItem.ToolCall.Id(id),
            call = Conversation.Message.ContentItem.ToolCall.Data(
                name = name,
                input = buildJsonObject {},
            ),
        )
}
