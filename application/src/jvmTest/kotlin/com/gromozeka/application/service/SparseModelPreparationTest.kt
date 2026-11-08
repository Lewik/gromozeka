package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult as Compaction
import com.gromozeka.domain.model.ai.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

class SparseModelPreparationTest {
    private val conversation = Conversation.Id("conversation")
    private val actor = User.Id("user")
    private val readonly = Message.Instruction.UserInstruction("readonly", "Readonly", "Read only")
    private val writable = Message.Instruction.UserInstruction("writable", "Writable", "May write")
    private val groups = listOf(MessageInstructionGroup(id = "access", title = "Access", controls = listOf(
        MessageInstructionGroup.Control(data = readonly, shortLabel = "R"), MessageInstructionGroup.Control(data = writable, shortLabel = "W"),
    ), retentionMode = MessageInstructionGroup.RetentionMode.STICKY_LATEST))

    @Test
    fun `covered sticky winners and precise human predecessors do not change the model input`() {
        val history = (0 until 50).map { text("old-$it", it) }.toMutableList()
        history[0] = history[0].copy(instructions = listOf(readonly))
        history[10] = history[10].copy(instructions = listOf(writable))
        val checkpoint = summary("partial", history.drop(1), full = false)
        val full = history + checkpoint + text("tail", 50)
        val sparse = sparse(full, null)
        assertTrue(sparse.size < 10)
        val expected = prepare(full, null)
        val actual = prepare(sparse, null)
        assertEquals(expected, actual)
        assertTrue(actual.first().instructions.none { it is Message.Instruction.UserInstruction })
        val time = actual.last().instructions.filterIsInstance<Message.Instruction.MessageTemporalRuntimeContext>().single()
        assertEquals(1L, time.context.elapsedSincePreviousUserMessageSeconds)
    }

    @Test
    fun `secret placement and tool discovery remain unchanged across a full checkpoint`() {
        val archive = (0 until 30).map { text("old-$it", it) }
        val checkpoint = summary("checkpoint", archive, full = true)
        val call = text("search", 31).copy(role = Message.Role.ASSISTANT, content = listOf(ContentItem.ToolCall(
            ContentItem.ToolCall.Id("call"), ContentItem.ToolCall.Data(SEARCH_TOOLS_TOOL_NAME, JsonObject(emptyMap())))))
        val result = text("result", 32).copy(content = listOf(ContentItem.ToolResult(
            ContentItem.ToolCall.Id("call"), SEARCH_TOOLS_TOOL_NAME, listOf(ContentItem.ToolResult.Data.Text("""{"tools":[{"name":"example"}]}""")))))
        val full = archive + checkpoint + call + result
        val sparse = sparse(full, "OPENAI_SUBSCRIPTION")
        val expected = prepare(full, "OPENAI_SUBSCRIPTION", reveal = true)
        assertEquals(expected, prepare(sparse, "OPENAI_SUBSCRIPTION", reveal = true))
        assertTrue(expected.none { message -> message.instructions.any { it is Message.Instruction.RevealedSecretRuntimeContext } })
        val catalog = DistributedAiToolCatalogSnapshot(emptyList(), emptyMap(), emptyList(), "revision", "environment")
        val discovery = AiToolRuntimeCatalogService()
        assertEquals(setOf("example"), discovery.discoveredToolNames(full, catalog))
        assertEquals(discovery.discoveredToolNames(full, catalog), discovery.discoveredToolNames(sparse, catalog))
    }

    @Test
    fun `opaque recovery preserves portable input and drops only the same native replay`() {
        val archive = (0 until 12).map { text("old-$it", it) }
        val checkpoint = summary("checkpoint", archive, full = true).copy(content = listOf(Compaction(
            Compaction.Payload.OpaqueProviderState(buildJsonObject { put("encrypted_content", "synthetic-checkpoint") }),
            Compaction.Origin.PROVIDER_AUTO, coverage = Compaction.Coverage.ALL_PREVIOUS,
            providerScope = Compaction.ProviderScope("OPENAI_SUBSCRIPTION"),
        )))
        val tail = text("tail", 15)
        val full = archive + checkpoint + tail
        for (provider in listOf(null, "OPENAI_SUBSCRIPTION", "CLAUDE_CODE")) {
            assertEquals(prepare(full, provider), prepare(sparse(full, provider), provider))
        }
    }

    private fun prepare(messages: List<Message>, provider: String?, reveal: Boolean = false): List<Message> {
        val secrets = PendingSecretRevealService()
        if (reveal) secrets.queue(conversation, actor, mapOf("synthetic" to "synthetic-secret"))
        val consumed = secrets.consume(conversation, actor, messages)
        val temporal = MessageTemporalContextService().enrich(consumed, enabled = true)
        val sticky = StickyMessageInstructionService().materialize(temporal, groups)
        return AiRuntimeRequest(emptyList(), sticky, options = AiRuntimeOptions(toolContext = mapOf("threadId" to "thread")))
            .projectedMessages(provider)
    }

    private fun sparse(messages: List<Message>, provider: String?): List<Message> {
        val ids = selectModelHistoryMessageIds(
            messages.map { MessageContextHeader(it.id, MessageContextMetadata.from(it)) },
            messages.filter { MessageContextMetadata.from(it).hasCompaction }, provider,
        )
        return messages.filter { it.id in ids }
    }

    private fun summary(id: String, sources: List<Message>, full: Boolean) = text(id, 50).copy(role = Message.Role.ASSISTANT,
        content = listOf(Compaction(Compaction.Payload.ReadableSummary("summary"), Compaction.Origin.USER_REQUESTED,
            sourceMessageIds = sources.map { it.id },
            coverage = if (full) Compaction.Coverage.ALL_PREVIOUS else Compaction.Coverage.SELECTED_MESSAGES)))
    private fun text(id: String, at: Int) = Message(Message.Id(id), conversation, role = Message.Role.USER,
        content = listOf(ContentItem.UserMessage(id)), createdAt = Instant.parse("2026-11-08T00:00:00.123456789Z") + at.seconds)
}
