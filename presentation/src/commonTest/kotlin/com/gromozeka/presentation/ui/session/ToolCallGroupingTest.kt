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

        val grouped = groupToolCallEntries(entries, successfulResults("call-1", "call-2", "call-3"))

        assertEquals(1, grouped.size)
        val group = assertIs<MessageSegment.ToolActivityGroup>(grouped.single().segment)
        assertEquals(listOf("call-1", "call-2", "call-3"), group.calls.map { it.content.id.value })
        assertEquals(entries(firstMessage).first().key, grouped.single().key)
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

        val grouped = groupToolCallEntries(
            entries,
            successfulResults("call-1", "call-2", "call-3", "call-4"),
        )

        assertEquals(3, grouped.size)
        assertIs<MessageSegment.Content>(grouped[0].segment)
        assertEquals(listOf("call-1", "call-2"), groupCalls(grouped[1]))
        assertEquals(listOf("call-3", "call-4"), groupCalls(grouped[2]))
    }

    @Test
    fun `keeps running and failed calls outside successful groups`() {
        val message = message(
            "message-1",
            listOf(
                toolCall("success-1"),
                toolCall("success-2"),
                toolCall("running"),
                toolCall("failed"),
                toolCall("success-3"),
                toolCall("success-4"),
            ),
        )
        val results = successfulResults("success-1", "success-2", "success-3", "success-4") +
            ("failed" to toolResult("failed", isError = true))

        val grouped = groupToolCallEntries(entries(message), results)

        assertEquals(4, grouped.size)
        assertEquals(listOf("success-1", "success-2"), groupCalls(grouped[0]))
        assertEquals("running", assertIs<MessageSegment.Content>(grouped[1].segment).toolCallId())
        assertEquals("failed", assertIs<MessageSegment.Content>(grouped[2].segment).toolCallId())
        assertEquals(listOf("success-3", "success-4"), groupCalls(grouped[3]))
    }

    @Test
    fun `summarizes repeated tools in first appearance order`() {
        val message = message(
            "message-1",
            listOf(
                toolCall("read-1", "grz_read_file"),
                toolCall("edit-1", "grz_edit_file"),
                toolCall("read-2", "grz_read_file"),
            ),
        )
        val calls = entries(message).map { entry ->
            val segment = assertIs<MessageSegment.Content>(entry.segment)
            ToolCallReference(
                messageId = message.id,
                contentIndex = segment.contentIndex,
                content = assertIs<Conversation.Message.ContentItem.ToolCall>(segment.content),
            )
        }

        assertEquals(
            listOf(
                ToolInvocationSummary("grz_read_file", 2),
                ToolInvocationSummary("grz_edit_file", 1),
            ),
            summarizeToolInvocations(calls),
        )
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

    private fun MessageSegment.Content.toolCallId(): String =
        assertIs<Conversation.Message.ContentItem.ToolCall>(content).id.value

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

    private fun toolCall(
        id: String,
        name: String = "grz_read_file",
    ) = Conversation.Message.ContentItem.ToolCall(
        id = Conversation.Message.ContentItem.ToolCall.Id(id),
        call = Conversation.Message.ContentItem.ToolCall.Data(
            name = name,
            input = buildJsonObject {},
        ),
    )

    private fun successfulResults(vararg ids: String) = ids.associateWith(::toolResult)

    private fun toolResult(
        id: String,
        isError: Boolean = false,
    ) = Conversation.Message.ContentItem.ToolResult(
        toolUseId = Conversation.Message.ContentItem.ToolCall.Id(id),
        toolName = "grz_read_file",
        result = emptyList(),
        isError = isError,
    )

    private fun assistantText() = Conversation.Message.ContentItem.AssistantMessage(
        structured = Conversation.Message.StructuredText("Visible text"),
    )
}
