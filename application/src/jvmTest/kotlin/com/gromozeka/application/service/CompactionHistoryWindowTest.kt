package com.gromozeka.application.service

import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.MessageCompactionTextRenderer
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationContext
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class CompactionHistoryWindowTest {
    private fun window(messages: List<Conversation.Message>) = CompactionHistoryWindow(messages.map(CompactionMessageHeader::from))
    private fun prefix(messages: List<Conversation.Message>, pairs: ToolCallPairingService) =
        messages.take(window(messages).prefix(pairs).size)
    private val pairs = ToolCallPairingService()
    private fun message(id: String, text: String) = Conversation.Message(Conversation.Message.Id(id), Conversation.Id("conversation"),
        role = Conversation.Message.Role.USER, content = listOf(ContentItem.UserMessage(text)), createdAt = Instant.fromEpochSeconds(0))

    @Test fun `boundary crossing message remains whole rather than fitting a hard cap`() {
        val old = message("old", "old")
        val crossing = message("crossing", "x".repeat(45_000))
        val last = message("last", "y".repeat(40_000))
        val history = listOf(old, crossing, last)
        assertEquals(listOf(old), prefix(history, pairs))
        assertEquals(85_000L, history.drop(1).sumOf(MessageCompactionTextRenderer::characterCount))
        assertTrue(window(history).isFullSelection(history.mapTo(mutableSetOf()) { it.id }))
        assertFalse(window(history).isFullSelection(setOf(old.id, last.id)))
    }

    @Test fun `exact boundary retains exactly the last complete message`() {
        val history = listOf(message("old", "old"), message("last", "x".repeat(80_000)))
        assertEquals(history.take(1), prefix(history, pairs))
    }

    @Test fun `whole tool group moves the boundary back without dropping intervening messages`() {
        val old = message("old", "old")
        val call = message("call", "").copy(role = Conversation.Message.Role.ASSISTANT, content = listOf(
            ContentItem.ToolCall(ContentItem.ToolCall.Id("a"), ContentItem.ToolCall.Data("read", JsonObject(emptyMap()))),
            ContentItem.ToolCall(ContentItem.ToolCall.Id("b"), ContentItem.ToolCall.Data("read", JsonObject(emptyMap())))))
        val intervening = message("between", "intervening")
        val first = message("result-a", "").copy(content = listOf(ContentItem.ToolResult(ContentItem.ToolCall.Id("a"), "read", listOf(ContentItem.ToolResult.Data.Text("small")))))
        val last = message("result-b", "").copy(content = listOf(ContentItem.ToolResult(ContentItem.ToolCall.Id("b"), "read", listOf(ContentItem.ToolResult.Data.Text("x".repeat(80_000))))))
        assertEquals(listOf(old), prefix(listOf(old, call, intervening, first, last), pairs))
    }

    @Test fun `an unfinished client tool exchange stays outside the compacted prefix`() {
        val old = message("old", "older work")
        val open = message("open", "").copy(role = Conversation.Message.Role.ASSISTANT,
            content = listOf(ContentItem.ToolCall(ContentItem.ToolCall.Id("pending"), ContentItem.ToolCall.Data("read", JsonObject(emptyMap())))))
        val latest = message("latest", "x".repeat(80_000))
        assertEquals(listOf(old), prefix(listOf(old, open, latest), pairs))
        val managed = open.copy(providerMetadata = JsonObject(mapOf(
            com.gromozeka.domain.model.ai.AI_PROVIDER_MANAGED_TOOL_METADATA_KEY to kotlinx.serialization.json.JsonPrimitive(true))))
        assertEquals(listOf(old, managed), prefix(listOf(old, managed, latest), pairs))
    }

    @Test fun `existing checkpoint is compacted again instead of preserving its archived originals as raw tail`() {
        val old = message("old", "o".repeat(300_000))
        val checkpoint = message("checkpoint", "").copy(role = Conversation.Message.Role.ASSISTANT, content = listOf(
            ContentItem.ContextCompactionResult(ContentItem.ContextCompactionResult.Payload.ReadableSummary("old handoff"),
                ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY, sourceMessageIds = listOf(old.id),
                coverage = ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES)))
        val recent = message("recent", "Recent work")
        val history = listOf(old, checkpoint, recent)
        assertEquals(listOf(old, checkpoint), prefix(history, pairs))
        assertTrue(window(history).isFullSelection(setOf(checkpoint.id, recent.id)))
        assertEquals(listOf(checkpoint, recent), ConversationContext(history).messages())
    }

    @Test fun `selective summary does not cut the retained raw suffix short`() {
        val ancient = message("ancient", "older work")
        val old = message("old", "old")
        val recent = message("recent", "a".repeat(45_000))
        val selected = message("selected", "selected")
        val partial = message("partial", "").copy(content = listOf(ContentItem.ContextCompactionResult(
            ContentItem.ContextCompactionResult.Payload.ReadableSummary("selected summary"),
            ContentItem.ContextCompactionResult.Origin.USER_REQUESTED, sourceMessageIds = listOf(old.id, selected.id),
            coverage = ContentItem.ContextCompactionResult.Coverage.SELECTED_MESSAGES)))
        val last = message("last", "b".repeat(40_000))
        assertEquals(listOf(ancient, old), prefix(listOf(ancient, old, recent, selected, partial, last), pairs))
    }

    @Test fun `one oversized or small recent message is never split or silently discarded`() {
        for (text in listOf("small", "x".repeat(400_000))) {
            assertTrue(prefix(listOf(message("only", text)), pairs).isEmpty())
        }
    }

    @Test fun `runtime messages are ordinary readable compaction input`(): Unit = runBlocking {
        val dialogue = message("dialogue", "portable dialogue")
        val runtime = message("runtime", "runtime JSON").copy(
            role = Conversation.Message.Role.RUNTIME,
            author = Conversation.Message.Author.Runtime(),
            content = listOf(ContentItem.RuntimeJson(kotlinx.serialization.json.JsonPrimitive("runtime JSON"))),
        )
        val recent = message("recent", "x".repeat(80_000))
        val history = listOf(dialogue, runtime, recent)
        val selection = window(history).prefix(pairs)
        val repository = Mockito.mock(ThreadMessageRepository::class.java)
        val thread = Conversation.Thread.Id("thread")
        Mockito.`when`(repository.compactionBodies(thread, selection.map { it.id })).thenReturn(
            history.filter { message -> selection.any { it.id == message.id } },
        )

        val readable = window(history).messages(repository, thread, selection, provider = null)

        assertEquals(listOf(dialogue, runtime), readable)
    }
}
