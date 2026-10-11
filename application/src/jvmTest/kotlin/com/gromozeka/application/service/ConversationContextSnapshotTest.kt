package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.projectedMessages
import kotlinx.serialization.json.JsonObject
import kotlin.test.*
import kotlin.time.Instant

class ConversationContextSnapshotTest {
    @Test fun `parallel tool group is excluded until every result is terminal`() {
        val history = listOf(message("user", text()), message("calls", call("a"), call("b")), message("a", result("a")))
        assertEquals(listOf("user"), completeToolGroupPrefix(history).map { it.id.value })
        val streaming = message("b", result("b").copy(state = Conversation.Message.BlockState.STREAMING))
        assertEquals(history.take(1), completeToolGroupPrefix(history + streaming))
        val complete = history + message("b", result("b"))
        assertEquals(complete, completeToolGroupPrefix(complete))
        assertEquals(3, history.size) // Never appends a synthetic result or changes stored history.
    }

    @Test fun `streaming assistant and incomplete suffix never enter snapshot`() {
        val first = message("user", text())
        val streaming = message("stream", text().copy(state = Conversation.Message.BlockState.STREAMING))
        assertEquals(listOf(first), completeToolGroupPrefix(listOf(first, streaming, message("later", text()))))
        assertEquals(listOf(first), completeToolGroupPrefix(listOf(first, message("call", call("a")))))
    }

    @Test fun `error and interrupted results close their group`() {
        val messages = listOf(message("call", call("a")), message("error", result("a").copy(
            isError = true, state = Conversation.Message.BlockState.INTERRUPTED)))
        assertEquals(messages, completeToolGroupPrefix(messages))
    }

    @Test fun `orphan and duplicate calls fail rather than repair the target`() {
        assertFailsWith<ContextSnapshotUnavailableException> { completeToolGroupPrefix(listOf(message("orphan", result("a")))) }
        assertFailsWith<ContextSnapshotUnavailableException> { completeToolGroupPrefix(listOf(message("dup", call("a"), call("a")))) }
    }

    @Test fun `coverage is projected before enforcing complete tool groups`() {
        val user = message("user", text())
        val call = message("call", call("a"))
        val result = message("result", result("a"))
        val summary = message("summary", Conversation.Message.ContentItem.ContextCompactionResult(
            payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary("covered group"),
            origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY,
            coverage = Conversation.Message.ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES,
            sourceMessageIds = listOf(call.id, result.id),
        ))
        val raw = listOf(user, call, result, summary)
        val projected = AiRuntimeRequest(emptyList(), raw, options = AiRuntimeOptions()).projectedMessages()
        assertEquals(listOf(user, summary), completeToolGroupPrefix(projected))
        assertEquals(4, raw.size)
    }

    private fun text() = Conversation.Message.ContentItem.UserMessage("input")
    private fun call(id: String) = Conversation.Message.ContentItem.ToolCall(
        Conversation.Message.ContentItem.ToolCall.Id(id),
        Conversation.Message.ContentItem.ToolCall.Data("read", JsonObject(emptyMap())),
    )
    private fun result(id: String) = Conversation.Message.ContentItem.ToolResult(
        Conversation.Message.ContentItem.ToolCall.Id(id), "read", listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("result")),
    )
    private fun message(id: String, vararg content: Conversation.Message.ContentItem) = Conversation.Message(
        Conversation.Message.Id(id), Conversation.Id("target"), role = Conversation.Message.Role.USER,
        content = content.toList(), createdAt = Instant.fromEpochMilliseconds(0),
    )
}
