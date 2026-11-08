package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult as Compaction
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Instant

class SparseConversationHistoryTest {
    @Test
    fun sparseReadsMatchTheOriginalProjectionAcrossNestedCoverageAndProviderRecovery() {
        repeat(120) { seed ->
            val random = Random(seed)
            val history = mutableListOf<Message>()
            repeat(45) { i ->
                val id = "m-$i"
                val message = if (i > 0 && random.nextInt(4) == 0) {
                    val full = random.nextBoolean()
                    val sources = if (full) emptyList() else history.shuffled(random).take(3).sortedBy(history::indexOf).map { it.id }
                    val provider = if (random.nextBoolean()) null else if (random.nextBoolean()) "OPENAI_SUBSCRIPTION" else "CLAUDE_CODE"
                    text(id).copy(role = Message.Role.ASSISTANT, content = listOf(Compaction(
                        payload = if (provider == null) Compaction.Payload.ReadableSummary(id)
                            else Compaction.Payload.OpaqueProviderState(buildJsonObject { put("encrypted_content", "opaque-$id") }),
                        origin = Compaction.Origin.USER_REQUESTED,
                        coverage = if (full) Compaction.Coverage.ALL_PREVIOUS else Compaction.Coverage.SELECTED_MESSAGES,
                        sourceMessageIds = sources,
                        providerScope = provider?.let { Compaction.ProviderScope(it) },
                        invalidatedReplayMessageIds = listOf(Message.Id("m-${i + 2}")),
                    )) + if (random.nextBoolean()) listOf(ContentItem.AssistantMessage(Message.StructuredText("tail-$id"))) else emptyList())
                } else if (random.nextBoolean()) text(id) else text(id).copy(role = Message.Role.ASSISTANT,
                    content = listOf(ContentItem.Thinking("reason", signature = "signature-$id"),
                        ContentItem.AssistantMessage(Message.StructuredText(id))),
                    providerMetadata = buildJsonObject { put("raw", id) })
                history += message
            }
            val full = ConversationContext(history)
            val reference = ConversationContextReference(history)
            assertEquals(reference.messages(), full.messages(), "coverage seed=$seed")
            assertEquals(reference.protectedMessageIds(), full.protectedMessageIds(), "protected seed=$seed")
            assertEquals(reference.dependentSummaryIds(setOf(history[0].id)), full.dependentSummaryIds(setOf(history[0].id)))
            for (provider in listOf(null, "OPENAI_SUBSCRIPTION", "CLAUDE_CODE")) {
                val ids = select(history, provider)
                val sparse = history.filter { it.id in ids }
                assertEquals(reference.messagesForProvider(provider), ConversationContext(sparse).messagesForProvider(provider),
                    "provider=$provider seed=$seed")
                assertEquals(reference.messages(), ConversationContext(sparse).messages(), "tool discovery seed=$seed")
            }
        }
    }

    @Test
    fun retainsEnrichmentAnchorsWithoutLoadingTheCoveredArchive() {
        val archive = (0 until 100).map { text("old-$it") }.toMutableList()
        archive[80] = archive[80].copy(instructions = listOf(Message.Instruction.UserInstruction("mode", "Mode", "latest")))
        val checkpoint = text("checkpoint").copy(role = Message.Role.ASSISTANT, content = listOf(Compaction(
            Compaction.Payload.ReadableSummary("summary"), Compaction.Origin.USER_REQUESTED,
            coverage = Compaction.Coverage.ALL_PREVIOUS,
        )))
        val tail = text("tail")
        val history = archive + checkpoint + tail
        val ids = select(history, null)
        assertEquals(setOf(archive[79].id, archive[80].id, archive[99].id, checkpoint.id, tail.id), ids)
        assertEquals(listOf(checkpoint, tail), ConversationContext(history.filter { it.id in ids }).messagesForProvider(null))
    }

    @Test
    fun preservesPairsAndOriginalCollaborationCheckpoints() {
        val user = text("user")
        val call = text("call").copy(role = Message.Role.ASSISTANT, content = listOf(ContentItem.ToolCall(
            ContentItem.ToolCall.Id("call-id"), ContentItem.ToolCall.Data("tool", buildJsonObject { put("x", 1) }))))
        val result = text("result").copy(content = listOf(ContentItem.ToolResult(ContentItem.ToolCall.Id("call-id"), "tool",
            listOf(ContentItem.ToolResult.Data.Text("answer")))))
        val checkpoint = text("checkpoint").copy(role = Message.Role.ASSISTANT, content = listOf(Compaction(
            Compaction.Payload.ReadableSummary("summary"), Compaction.Origin.USER_REQUESTED,
            coverage = Compaction.Coverage.SELECTED_MESSAGES, sourceMessageIds = listOf(call.id, result.id),
        )))
        val corrected = checkpoint.copy(author = Message.Author.Agent(AgentDefinition.Id("agent"), "Agent"),
            content = listOf(ContentItem.AssistantMessage(Message.StructuredText("display correction"))),
            providerMetadata = buildJsonObject { put(COLLABORATION_ORIGINAL_CONTENT, Json.encodeToJsonElement(checkpoint.content)) })
        for (summary in listOf(checkpoint, corrected)) {
            val history = listOf(user, call, result, summary, text("tail"))
            val sparse = history.filter { it.id in select(history, null) }
            assertEquals(ConversationContextReference(history.map(Message::originalCollaborationMessage)).messagesForProvider(null),
                ConversationContext(sparse.map(Message::originalCollaborationMessage)).messagesForProvider(null))
            assertEquals(ConversationContextReference(history).messages(), ConversationContext(sparse).messages())
        }
    }

    @Test
    fun missingForeignSourcesAndInvalidCoverageFailBeforeHydration() {
        val opaque = text("checkpoint").copy(role = Message.Role.ASSISTANT, content = listOf(Compaction(
            Compaction.Payload.OpaqueProviderState(JsonObject(emptyMap())), Compaction.Origin.PROVIDER_AUTO,
            coverage = Compaction.Coverage.SELECTED_MESSAGES, sourceMessageIds = listOf(Message.Id("foreign")),
            providerScope = Compaction.ProviderScope("OPENAI_SUBSCRIPTION"),
        )))
        assertFailsWith<IllegalStateException> { select(listOf(opaque), null) }
        assertEquals(setOf(opaque.id), select(listOf(opaque), "OPENAI_SUBSCRIPTION"))
        val forward = opaque.copy(content = listOf((opaque.content.single() as Compaction).copy(
            payload = Compaction.Payload.ReadableSummary("summary"), sourceMessageIds = listOf(Message.Id("future")))))
        assertFailsWith<IllegalArgumentException> { select(listOf(forward, text("future")), null) }
        assertFailsWith<IllegalArgumentException> {
            selectModelHistoryMessageIds(listOf(MessageContextHeader(text("a").id, MessageContextMetadata.from(text("a")))), listOf(opaque), null)
        }
    }

    @Test
    fun retainsNativeUsageObservationsEvenWhenSelectiveCoverageHidesTheirMessages() {
        fun observation(id: String, tokens: JsonElement) = text(id).copy(role = Message.Role.ASSISTANT,
            content = listOf(ContentItem.AssistantMessage(Message.StructuredText(id))),
            providerMetadata = buildJsonObject {
                put("provider", "OPENAI_SUBSCRIPTION"); put("connectionId", "connection"); put("model", "model")
                put("openaiSubscriptionContextTokens", tokens); put("openaiSubscriptionContextEstimate", 100)
            })
        val good = observation("good", JsonPrimitive(1000))
        val nonNumeric = observation("non-numeric", JsonPrimitive("unknown"))
        val malformed = observation("malformed", JsonObject(emptyMap()))
        fun history(observations: List<Message>): List<Message> = observations + text("summary").copy(
            role = Message.Role.ASSISTANT, content = listOf(Compaction(Compaction.Payload.ReadableSummary("summary"),
                Compaction.Origin.USER_REQUESTED, coverage = Compaction.Coverage.SELECTED_MESSAGES,
                sourceMessageIds = observations.map { it.id }))) + text("tail")
        val ordinary = select(history(listOf(good, nonNumeric)), null)
        assertTrue(good.id !in ordinary && nonNumeric.id !in ordinary)
        val measured = select(history(listOf(good, nonNumeric)), "OPENAI_SUBSCRIPTION")
        assertTrue(good.id in measured && nonNumeric.id !in measured)
        val invalid = select(history(listOf(good, malformed)), "OPENAI_SUBSCRIPTION")
        assertTrue(malformed.id in invalid && good.id !in invalid)
    }

    private fun select(history: List<Message>, provider: String?): Set<Message.Id> {
        val headers = history.map { MessageContextHeader(it.id, MessageContextMetadata.from(it)) }
        val checkpoints = history.filter { MessageContextMetadata.from(it).hasCompaction }
        return selectModelHistoryMessageIds(headers, checkpoints, provider)
    }

    private fun text(id: String) = Message(Message.Id(id), Conversation.Id("conversation"), role = Message.Role.USER,
        content = listOf(ContentItem.UserMessage(id)), createdAt = Instant.parse("2026-11-08T00:00:00.123456789Z"))
}
