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
    fun sameProviderOpaqueCheckpointBeforeRuntimeBoundaryLoadsPortableSources() {
        val provider = "OPENAI_SUBSCRIPTION"
        val source = text("source").copy(
            role = Message.Role.ASSISTANT,
            content = listOf(
                ContentItem.Thinking("stale reasoning", signature = "signature"),
                ContentItem.AssistantMessage(Message.StructuredText("visible answer")),
            ),
        )
        val checkpoint = text("checkpoint").copy(role = Message.Role.ASSISTANT, content = listOf(Compaction(
            Compaction.Payload.OpaqueProviderState(buildJsonObject { put("encrypted_content", "opaque") }),
            Compaction.Origin.PROVIDER_AUTO,
            coverage = Compaction.Coverage.ALL_PREVIOUS,
            providerScope = Compaction.ProviderScope(provider),
        )))
        val boundary = text("boundary").copy(
            role = Message.Role.RUNTIME,
            author = Message.Author.Runtime(),
            content = emptyList(),
            instructions = listOf(Message.Instruction.RuntimeObservation("current state", "state", "revision")),
        ).withDurableRuntimeObservationBoundary()

        for (persistedBoundary in listOf(false, true)) {
            val stored = listOf(source, checkpoint) + if (persistedBoundary) listOf(boundary) else emptyList()
            val ids = select(stored, provider, prepareRuntimeObservationBoundary = !persistedBoundary)
            assertTrue(source.id in ids)
            val request = stored.filter { it.id in ids } + if (persistedBoundary) emptyList() else listOf(boundary)
            assertEquals(
                listOf(source.withoutProviderReplay(), boundary),
                ConversationContext(request).messagesForProvider(provider),
            )
        }
    }

    @Test
    fun supersededRuntimeObservationGenerationsAreExcludedBeforeHydration() {
        fun observation(id: String, revision: String, boundary: Boolean = false): Message {
            val message = text(id).copy(
                role = Message.Role.RUNTIME,
                author = Message.Author.Runtime(),
                content = emptyList(),
                instructions = listOf(Message.Instruction.RuntimeObservation("state-$revision", "state", revision)),
            )
            return if (boundary) message.withDurableRuntimeObservationBoundary() else message
        }
        val human = text("human")
        val oldBaseline = observation("old-baseline", "old-0", boundary = true)
        val oldDelta = observation("old-delta", "old-1")
        val currentBaseline = observation("current-baseline", "current-0", boundary = true)
        val currentDelta = observation("current-delta", "current-1")
        val history = listOf(human, oldBaseline, oldDelta, currentBaseline, currentDelta)

        val selected = select(history, null)

        assertTrue(human.id in selected)
        assertTrue(currentBaseline.id in selected)
        assertTrue(currentDelta.id in selected)
        assertTrue(oldBaseline.id !in selected)
        assertTrue(oldDelta.id !in selected)
        assertEquals(
            ConversationContext(history).messagesForProvider(null),
            ConversationContext(history.filter { it.id in selected }).messagesForProvider(null),
        )
    }

    @Test
    fun rawExternalHistoryKeepsCompactedRowsButDropsRuntimeStateBeforeCheckpointBoundary() {
        fun observation(id: String, boundary: Boolean = false): Message {
            val message = text(id).copy(
                role = Message.Role.RUNTIME,
                author = Message.Author.Runtime(),
                content = emptyList(),
                instructions = listOf(Message.Instruction.RuntimeObservation(id, "state", id)),
            )
            return if (boundary) message.withDurableRuntimeObservationBoundary() else message
        }
        val archived = text("archived")
        val oldRuntime = observation("old-runtime", boundary = true)
        val checkpoint = text("checkpoint").copy(
            role = Message.Role.ASSISTANT,
            content = listOf(ContentItem.ContextCompactionResult(
                ContentItem.ContextCompactionResult.Payload.ReadableSummary("summary"),
                ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
            )),
        ).withDurableRuntimeObservationBoundary()
        val currentRuntime = observation("current-runtime")
        val tail = text("tail")
        val history = listOf(archived, oldRuntime, checkpoint, currentRuntime, tail)

        val selected = selectRawExternalHistoryMessageIds(
            history.map { MessageContextHeader(it.id, MessageContextMetadata.from(it)) },
        )

        assertEquals(listOf(archived.id, checkpoint.id, currentRuntime.id, tail.id), history.mapNotNull {
            it.id.takeIf(selected::contains)
        })
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

    private fun select(
        history: List<Message>,
        provider: String?,
        prepareRuntimeObservationBoundary: Boolean = false,
    ): Set<Message.Id> {
        val headers = history.map { MessageContextHeader(it.id, MessageContextMetadata.from(it)) }
        val checkpoints = history.filter { MessageContextMetadata.from(it).hasCompaction }
        return selectModelHistoryMessageIds(headers, checkpoints, provider, prepareRuntimeObservationBoundary)
    }

    private fun text(id: String) = Message(Message.Id(id), Conversation.Id("conversation"), role = Message.Role.USER,
        content = listOf(ContentItem.UserMessage(id)), createdAt = Instant.parse("2026-11-08T00:00:00.123456789Z"))
}
