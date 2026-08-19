package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class MessageSearchTextTest {
    @Test
    fun `search index contains only visible user and assistant text`() {
        val message = Conversation.Message(
            id = Conversation.Message.Id("message-1"),
            conversationId = Conversation.Id("conversation-1"),
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(
                Conversation.Message.ContentItem.UserMessage("Visible user text"),
                Conversation.Message.ContentItem.Thinking("Private thinking"),
                Conversation.Message.ContentItem.ToolCall(
                    id = Conversation.Message.ContentItem.ToolCall.Id("tool-call-1"),
                    call = Conversation.Message.ContentItem.ToolCall.Data(
                        name = "secret_tool",
                        input = buildJsonObject { put("secret", "tool input") },
                    ),
                ),
                Conversation.Message.ContentItem.ToolResult(
                    toolUseId = Conversation.Message.ContentItem.ToolCall.Id("tool-call-1"),
                    toolName = "secret_tool",
                    result = listOf(
                        Conversation.Message.ContentItem.ToolResult.Data.Text("tool output")
                    ),
                ),
                Conversation.Message.ContentItem.AssistantMessage(
                    Conversation.Message.StructuredText(fullText = "Visible assistant text")
                ),
                Conversation.Message.ContentItem.System(
                    level = Conversation.Message.ContentItem.System.SystemLevel.INFO,
                    content = "System notification",
                ),
            ),
            createdAt = Instant.parse("2026-08-19T10:00:00Z"),
        )

        assertEquals(
            "Visible user text\nVisible assistant text",
            message.searchText(),
        )
    }
}
