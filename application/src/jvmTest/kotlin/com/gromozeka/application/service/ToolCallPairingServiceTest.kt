package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class ToolCallPairingServiceTest {
    @Test
    fun `selection of visible tool call includes hidden tool result message`() {
        val toolCallId = Conversation.Message.ContentItem.ToolCall.Id("tool-1")
        val toolCallMessage = message(
            id = "call",
            role = Conversation.Message.Role.ASSISTANT,
            content = Conversation.Message.ContentItem.ToolCall(
                id = toolCallId,
                call = Conversation.Message.ContentItem.ToolCall.Data(
                    name = "read_file",
                    input = JsonObject(emptyMap()),
                ),
            ),
        )
        val toolResultMessage = message(
            id = "result",
            role = Conversation.Message.Role.USER,
            content = Conversation.Message.ContentItem.ToolResult(
                toolUseId = toolCallId,
                toolName = "read_file",
                result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("raw output")),
            ),
        )

        val selected = ToolCallPairingService().includePairedToolMessages(
            listOf(toolCallMessage, toolResultMessage),
            listOf(toolCallMessage.id),
        )

        assertEquals(setOf(toolCallMessage.id, toolResultMessage.id), selected)
    }

    private fun message(
        id: String,
        role: Conversation.Message.Role,
        content: Conversation.Message.ContentItem,
    ): Conversation.Message = Conversation.Message(
        id = Conversation.Message.Id(id),
        conversationId = Conversation.Id("conversation"),
        role = role,
        content = listOf(content),
        createdAt = Clock.System.now(),
    )
}
