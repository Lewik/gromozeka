package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.time.Clock

class MessageSquashTextTest {
    @Test
    fun `concatenation keeps assistant tool call and tool result content`() {
        val toolCallId = Conversation.Message.ContentItem.ToolCall.Id("tool-1")
        val text = listOf(
            message(
                id = "assistant",
                role = Conversation.Message.Role.ASSISTANT,
                content = listOf(
                    Conversation.Message.ContentItem.AssistantMessage(
                        Conversation.Message.StructuredText(fullText = "I will inspect it")
                    ),
                    Conversation.Message.ContentItem.ToolCall(
                        id = toolCallId,
                        call = Conversation.Message.ContentItem.ToolCall.Data(
                            name = "read_file",
                            input = JsonObject(mapOf("path" to JsonPrimitive("README.md"))),
                        ),
                    ),
                ),
            ),
            message(
                id = "result",
                role = Conversation.Message.Role.USER,
                content = listOf(
                    Conversation.Message.ContentItem.ToolResult(
                        toolUseId = toolCallId,
                        toolName = "read_file",
                        result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("raw output")),
                    )
                ),
            ),
        ).toConcatenatedCompactionText()

        assertContains(text, "I will inspect it")
        assertContains(text, "[tool_call:read_file]")
        assertContains(text, "[tool_result:read_file]")
        assertContains(text, "raw output")
    }

    private fun message(
        id: String,
        role: Conversation.Message.Role,
        content: List<Conversation.Message.ContentItem>,
    ): Conversation.Message = Conversation.Message(
        id = Conversation.Message.Id(id),
        conversationId = Conversation.Id("conversation"),
        role = role,
        content = content,
        createdAt = Clock.System.now(),
    )
}
