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

        val grouped = groupWithResults(entries, successfulResults("call-1", "call-2", "call-3"))

        assertEquals(1, grouped.size)
        val group = assertIs<MessageSegment.ActivityGroup>(grouped.single().segment)
        assertEquals(listOf("call-1", "call-2", "call-3"), group.activities.map { (it.activity as ChatActivity.Tool).call.id.value })
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

        val grouped = groupWithResults(
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

        val grouped = groupWithResults(entries(message), results)

        assertEquals(4, grouped.size)
        assertEquals(listOf("success-1", "success-2"), groupCalls(grouped[0]))
        assertEquals("running", assertIs<MessageSegment.Activity>(grouped[1].segment).toolCallId())
        assertEquals("failed", assertIs<MessageSegment.Activity>(grouped[2].segment).toolCallId())
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
        val activities = message.content.map { ChatActivity.Tool(it as Conversation.Message.ContentItem.ToolCall, null) }
        assertEquals(
            listOf(ActivitySummary(ActivityKind.Tool("grz_read_file"), 2), ActivitySummary(ActivityKind.Tool("grz_edit_file"), 1)),
            summarizeActivities(activities),
        )
    }

    @Test
    fun `keeps reasoning first in summaries even when it follows tools`() {
        val activities = listOf(
            ChatActivity.Tool(toolCall("read"), null),
            ChatActivity.Reasoning(Conversation.Message.ContentItem.Thinking("")),
        )
        assertEquals(ActivityKind.Reasoning, summarizeActivities(activities).first().kind)
    }

    @Test
    fun `partial and interrupted tool results stay outside completed groups`() {
        val message = message("tool-states", listOf(
            toolCall("done-one"), toolCall("done-two"), toolCall("partial"), toolCall("interrupted"),
        ))
        val results = successfulResults("done-one", "done-two") + mapOf(
            "partial" to toolResult("partial").copy(state = Conversation.Message.BlockState.STREAMING),
            "interrupted" to toolResult("interrupted").copy(state = Conversation.Message.BlockState.INTERRUPTED),
        )
        val grouped = groupWithResults(entries(message), results)
        assertEquals(3, grouped.size)
        assertIs<MessageSegment.ActivityGroup>(grouped.first().segment)
        assertEquals(ActivityState.RUNNING, assertIs<MessageSegment.Activity>(grouped[1].segment).activity.state)
        assertEquals(ActivityState.INTERRUPTED, assertIs<MessageSegment.Activity>(grouped[2].segment).activity.state)
    }

    @Test
    fun `groups readable and hidden reasoning with tools in original order`() {
        val message = message("mixed", listOf(
            Conversation.Message.ContentItem.Thinking("First thought"),
            toolCall("read"),
            Conversation.Message.ContentItem.Thinking(""),
            toolCall("write"),
        ))
        val group = assertIs<MessageSegment.ActivityGroup>(
            groupWithResults(entries(message), successfulResults("read", "write")).single().segment,
        )
        assertEquals(listOf(0, 1, 2, 3), group.activities.map { it.contentIndex })
        assertEquals(
            listOf(ActivitySummary(ActivityKind.Reasoning, 2), ActivitySummary(ActivityKind.Tool("grz_read_file"), 2)),
            summarizeActivities(group.activities.map { it.activity }),
        )
    }

    @Test
    fun `does not join activities from different authors`() {
        val first = message("first", listOf(toolCall("one"))).copy(author = Conversation.Message.Author.Agent(
            com.gromozeka.domain.model.AgentDefinition.Id("one"), "First",
        ))
        val second = message("second", listOf(toolCall("two"))).copy(author = Conversation.Message.Author.Agent(
            com.gromozeka.domain.model.AgentDefinition.Id("two"), "Second",
        ))
        assertEquals(2, groupWithResults(entries(first) + entries(second), successfulResults("one", "two")).size)
    }

    @Test
    fun `interrupted and running reasoning remain outside completed groups`() {
        val message = message("states", listOf(
            Conversation.Message.ContentItem.Thinking("done"),
            toolCall("one"),
            Conversation.Message.ContentItem.Thinking("", state = Conversation.Message.BlockState.STREAMING),
            Conversation.Message.ContentItem.Thinking("", state = Conversation.Message.BlockState.INTERRUPTED),
            toolCall("two"),
        ))
        val grouped = groupWithResults(entries(message), successfulResults("one", "two"))
        assertEquals(4, grouped.size)
        assertIs<MessageSegment.ActivityGroup>(grouped.first().segment)
        assertEquals(ActivityState.RUNNING, assertIs<MessageSegment.Activity>(grouped[1].segment).activity.state)
        assertEquals(ActivityState.INTERRUPTED, assertIs<MessageSegment.Activity>(grouped[2].segment).activity.state)
    }

    private fun groupWithResults(
        entries: List<MessageListEntry>,
        results: Map<String, Conversation.Message.ContentItem.ToolResult>,
    ): List<MessageListEntry> = groupActivityEntries(entries.map { entry ->
        val segment = entry.segment as? MessageSegment.Content ?: return@map entry
        val activity = when (val content = segment.content) {
            is Conversation.Message.ContentItem.ToolCall -> ChatActivity.Tool(content, results[content.id.value])
            is Conversation.Message.ContentItem.Thinking -> ChatActivity.Reasoning(content)
            else -> return@map entry
        }
        entry.copy(segment = MessageSegment.Activity(segment.contentIndex, activity))
    })

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
        assertIs<MessageSegment.ActivityGroup>(entry.segment).activities.map { (it.activity as ChatActivity.Tool).call.id.value }

    private fun MessageSegment.Activity.toolCallId(): String =
        assertIs<ChatActivity.Tool>(activity).call.id.value

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
