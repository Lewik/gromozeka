package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult as Compaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ConversationContextTest {
    @Test
    fun coverageIsRequiredAndIndependentOfOrigin() {
        for (coverage in Compaction.Coverage.entries) {
            for (origin in Compaction.Origin.entries) {
                val result = Compaction(Compaction.Payload.ReadableSummary("summary"), origin, coverage = coverage)
                assertEquals(coverage == Compaction.Coverage.ALL_PREVIOUS, result.coversAllPrevious)
                assertEquals(result, Json.decodeFromString<Compaction>(Json.encodeToString(result)))
            }
        }
        assertFailsWith<kotlinx.serialization.SerializationException> {
            Json.decodeFromString<Compaction>("""{"payload":{"kind":"readable_summary","text":"summary"},"origin":"USER_REQUESTED"}""")
        }
    }

    @Test
    fun branchChangesStripProviderReplayWithoutChangingStoredMessages() {
        val stored = text("assistant").copy(role = Message.Role.ASSISTANT, providerMetadata = JsonObject(mapOf(
            AI_REPLAY_THREAD_METADATA_KEY to JsonPrimitive("old-thread"),
            "providerManagedTool" to JsonPrimitive(true),
            "signedRawReplay" to JsonPrimitive("stale"),
        )))
        fun request(thread: String) = AiRuntimeRequest(emptyList(), listOf(stored),
            options = AiRuntimeOptions(toolContext = mapOf("threadId" to thread)))
        assertEquals(listOf(stored), request("old-thread").projectedMessages())
        val rewritten = request("new-thread").projectedMessages().single()
        assertEquals(JsonObject(mapOf("providerManagedTool" to JsonPrimitive(true))), rewritten.providerMetadata)
        assertEquals("stale", stored.providerMetadata["signedRawReplay"]?.let { (it as JsonPrimitive).content })
        assertEquals(listOf(rewritten), request("new-thread").copy(messages = listOf(rewritten)).projectedMessages())
    }

    @Test
    fun selectiveSummaryKeepsUnselectedEarlierAndLaterMessages() {
        val a = text("a"); val b = text("b"); val c = text("c"); val d = text("d")
        val summary = summary("bc", listOf(b, c))
        assertEquals(listOf(a, summary, d), ConversationContext(listOf(a, b, c, summary, d)).messages())
    }

    @Test
    fun includesFullCheckpointAndStopsBeforeItsPrefix() {
        val old = text("old"); val full = summary("full", full = true); val tail = text("tail")
        assertEquals(listOf(full, tail), ConversationContext(listOf(old, full, tail)).messages())
    }

    @Test
    fun nestedSummariesInheritCoverageBeforeIntermediateSummariesAreFiltered() {
        val full = summary("full", full = true)
        val a = text("a"); val b = text("b"); val c = text("c"); val tail = text("tail")
        val first = summary("first", listOf(a, b))
        val second = summary("second", listOf(first, c))
        val history = listOf(full, a, b, first, c, second, tail)
        assertEquals(listOf(full, second, tail), ConversationContext(history).messages())
    }

    @Test
    fun summarizingAFullCheckpointDoesNotLoseGapsOrReleaseItsProtection() {
        val old = text("old"); val full = summary("full", full = true)
        val a = text("a"); val gap = text("gap"); val tail = text("tail")
        val partial = summary("partial", listOf(full, a))
        val context = ConversationContext(listOf(old, full, a, gap, partial, tail))
        assertEquals(listOf(gap, partial, tail), context.messages())
        assertEquals(setOf(old.id, full.id), context.protectedMessageIds())
    }

    @Test
    fun overlappingSelectiveSummariesDoNotDiscardEachOthersUniqueInformation() {
        val a = text("a"); val b = text("b"); val c = text("c")
        val ab = summary("ab", listOf(a, b)); val bc = summary("bc", listOf(b, c))
        assertEquals(listOf(ab, bc), ConversationContext(listOf(a, b, ab, c, bc)).messages())
    }

    @Test
    fun selectiveSummariesNeverProtectTheirSourcesByThemselves() {
        val source = text("source"); val partial = summary("partial", listOf(source))
        assertTrue(ConversationContext(listOf(source, partial)).protectedMessageIds().isEmpty())
    }

    @Test
    fun fullCheckpointProtectsTransitiveArchivedSourcesToo() {
        val archived = text("archived")
        val partial = summary("partial", listOf(archived))
        val full = summary("full", full = true)
        val tail = text("tail")
        assertEquals(setOf(archived.id, partial.id, full.id),
            ConversationContext(listOf(partial, full, tail)).protectedMessageIds())
    }

    @Test
    fun editsInvalidateDependentSelectiveSummariesTransitively() {
        val a = text("a"); val b = text("b"); val c = text("c")
        val ab = summary("ab", listOf(a, b)); val nested = summary("nested", listOf(ab))
        val independent = summary("independent", listOf(c))
        val context = ConversationContext(listOf(a, b, ab, c, nested, independent))
        assertEquals(setOf(ab.id, nested.id), context.dependentSummaryIds(setOf(a.id)))
    }

    @Test
    fun selectionCanReadASingleOldMessageDespiteLaterFullCheckpoint() {
        val old = text("old"); val full = summary("full", full = true); val tail = text("tail")
        assertEquals(listOf(old), ConversationContext(listOf(old, full, tail))
            .messagesForProvider(null, setOf(old.id)))
    }

    @Test
    fun selectAllRecoversOpaqueStateFromOriginalsRatherThanAPlaceholder() {
        val old = text("old"); val full = opaque("full", "OPENAI_SUBSCRIPTION"); val tail = text("tail")
        val history = listOf(old, full, tail)
        assertEquals(listOf(old, tail), ConversationContext(history)
            .messagesForProvider(null, history.mapTo(mutableSetOf(), Message::id)))
    }

    @Test
    fun nativeProviderReplaysItsCheckpointWhileOtherProvidersRecoverOriginals() {
        val old = text("old"); val full = opaque("full", "CLAUDE_CODE"); val tail = text("tail")
        val context = ConversationContext(listOf(old, full, tail))
        assertEquals(listOf(full, tail), context.messagesForProvider("CLAUDE_CODE"))
        assertEquals(listOf(old, tail), context.messagesForProvider("ANTHROPIC_API"))
        assertEquals(listOf(old, tail), context.messagesForProvider("OPENAI_SUBSCRIPTION"))
    }

    @Test
    fun opaqueCheckpointWithoutSourcesFailsExplicitly() {
        assertFailsWith<IllegalArgumentException> {
            ConversationContext(listOf(opaque("checkpoint", "OPENAI_SUBSCRIPTION"))).messagesForProvider("CLAUDE_CODE")
        }
    }

    @Test
    fun preservesCurrentReasoningButDropsReplayBoundToReplacedHistory() {
        val source = text("source")
        val oldTail = text("old-tail").copy(
            content = listOf(Message.ContentItem.Thinking("reason", signature = "old-signature")) + text("old-tail").content,
            providerMetadata = JsonObject(mapOf("raw" to JsonPrimitive("old"))),
        )
        val current = oldTail.copy(id = Message.Id("current"))
        val result = Compaction(
            payload = Compaction.Payload.ReadableSummary("summary"), origin = Compaction.Origin.USER_REQUESTED,
            coverage = Compaction.Coverage.SELECTED_MESSAGES,
            sourceMessageIds = listOf(source.id), invalidatedReplayMessageIds = listOf(oldTail.id),
        )
        val compact = text("compact").copy(content = listOf(result))
        val context = ConversationContext(listOf(source, compact, oldTail, current)).messages()
        assertEquals(listOf(compact, oldTail.withoutProviderReplay(), current), context)
        assertEquals("old-signature", (oldTail.content.first() as Message.ContentItem.Thinking).signature)
    }

    @Test
    fun rejectsForwardOrCyclicCoverageReferences() {
        val future = text("future")
        assertFailsWith<IllegalArgumentException> {
            ConversationContext(listOf(summary("bad", listOf(future)), future)).messages()
        }
    }

    private fun text(id: String) = Message(
        id = Message.Id(id), conversationId = Conversation.Id("conversation"),
        role = Message.Role.USER, content = listOf(Message.ContentItem.UserMessage(id)),
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    private fun summary(id: String, sources: List<Message> = emptyList(), full: Boolean = false) = text(id).copy(
        role = Message.Role.ASSISTANT,
        content = listOf(Compaction(
            payload = Compaction.Payload.ReadableSummary(id), origin = Compaction.Origin.USER_REQUESTED,
            sourceMessageIds = sources.map(Message::id),
            coverage = if (full) Compaction.Coverage.ALL_PREVIOUS else Compaction.Coverage.SELECTED_MESSAGES,
        )),
    )

    private fun opaque(id: String, provider: String, sources: List<Message> = emptyList()) = text(id).copy(
        role = Message.Role.ASSISTANT,
        content = listOf(Compaction(
            payload = Compaction.Payload.OpaqueProviderState(JsonObject(emptyMap())),
            origin = Compaction.Origin.PROVIDER_AUTO, sourceMessageIds = sources.map(Message::id),
            providerScope = Compaction.ProviderScope(provider), coverage = Compaction.Coverage.ALL_PREVIOUS,
        )),
    )
}
