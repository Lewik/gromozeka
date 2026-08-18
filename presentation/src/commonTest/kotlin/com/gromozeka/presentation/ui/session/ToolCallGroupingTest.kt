package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class ToolCallGroupingTest {
    @Test
    fun `groups adjacent calls across tool-only assistant iterations`() {
        val firstMessage = message("message-1", listOf(toolCall("call-1"), toolCall("call-2")))
        val secondMessage = message("message-2", listOf(toolCall("call-3")))
        val entries = entries(firstMessage) + entries(secondMessage)

        val grouped = groupToolCallEntries(entries)

        assertEquals(1, grouped.size)
        val group = assertIs<MessageSegment.ToolActivityGroup>(grouped.single().segment)
        assertEquals(listOf("call-1", "call-2", "call-3"), group.calls.map { it.content.id.value })
        assertEquals("message-1:tool-group:call-1", grouped.single().key)
    }

    @Test
    fun `visible content and mixed messages preserve group boundaries`() {
        val mixedMessage = message(
            "message-1",
            listOf(assistantText(), toolCall("call-1"), toolCall("call-2")),
        )
        val nextMessage = message("message-2", listOf(toolCall("call-3"), toolCall("call-4")))
        val entries = listOf(
            entry(mixedMessage, MessageSegment.Content(0, mixedMessage.content[0]), first = true, last = false),
            entry(mixedMessage, MessageSegment.Content(1, mixedMessage.content[1]), first = false, last = false),
            entry(mixedMessage, MessageSegment.Content(2, mixedMessage.content[2]), first = false, last = true),
        ) + entries(nextMessage)

        val grouped = groupToolCallEntries(entries)

        assertEquals(3, grouped.size)
        assertIs<MessageSegment.Content>(grouped[0].segment)
        assertEquals(listOf("call-1", "call-2"), groupCalls(grouped[1]))
        assertEquals(listOf("call-3", "call-4"), groupCalls(grouped[2]))
    }

    private fun entries(message: Conversation.Message): List<MessageListEntry> = message.content.mapIndexed { index, content ->
        entry(
            message = message,
            segment = MessageSegment.Content(index, content),
            first = index == 0,
            last = index == message.content.lastIndex,
        )
    }

    private fun entry(
        message: Conversation.Message,
        segment: MessageSegment,
        first: Boolean,
        last: Boolean,
    ) = MessageListEntry(message, segment, first, last)

    private fun groupCalls(entry: MessageListEntry): List<String> =
        assertIs<MessageSegment.ToolActivityGroup>(entry.segment).calls.map { it.content.id.value }

    private fun message(
        id: String,
        content: List<Conversation.Message.ContentItem>,
    ) = Conversation.Message(
        id = Conversation.Message.Id(id),
        conversationId = Conversation.Id("conversation-1"),
        role = Conversation.Message.Role.ASSISTANT,
        content = content,
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    private fun toolCall(id: String) = Conversation.Message.ContentItem.ToolCall(
        id = Conversation.Message.ContentItem.ToolCall.Id(id),
        call = Conversation.Message.ContentItem.ToolCall.Data(
            name = "grz_read_file",
            input = buildJsonObject {},
        ),
    )

    private fun assistantText() = Conversation.Message.ContentItem.AssistantMessage(
        structured = Conversation.Message.StructuredText("Visible text"),
    )
}
