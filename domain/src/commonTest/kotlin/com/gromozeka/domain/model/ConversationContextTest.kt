package com.gromozeka.domain.model

import com.gromozeka.domain.model.Conversation.Message
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult as Compaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class ConversationContextTest {
    @Test
    fun imageSourcesRoundTripInsideMessagesWithOneDiscriminator() {
        val sources = listOf(
            Message.ImageSource.Base64ImageSource("aW1hZ2U=", "image/jpeg"),
            Message.ImageSource.UrlImageSource("https://example.test/image.jpg"),
            Message.ImageSource.FileImageSource("image-file"),
        )
        for (json in listOf(Json, Json { encodeDefaults = true })) {
            for (source in sources) {
                val message = text("image").copy(content = listOf(Message.ContentItem.ImageItem(source)))
                val encoded = json.encodeToString(message)
                assertEquals(message, json.decodeFromString<Message>(encoded))
                val encodedSource = json.parseToJsonElement(encoded).jsonObject.getValue("content")
                    .jsonArray.single().jsonObject.getValue("source").jsonObject
                assertEquals(JsonPrimitive(source.type), encodedSource["type"])
                assertEquals(1, Regex("\"type\":\"${source.type}\"").findAll(encoded).count())
            }
        }
    }

    @Test
    fun documentSourcesRoundTripInsideMessagesWithOneDiscriminator() {
        val source = Message.DocumentSource.Base64DocumentSource("cGRm", "application/pdf", "document.pdf")
        val message = text("document").copy(content = listOf(Message.ContentItem.DocumentItem(source)))
        for (json in listOf(Json, Json { encodeDefaults = true })) {
            val encoded = json.encodeToString(message)
            assertEquals(message, json.decodeFromString<Message>(encoded))
            assertEquals(1, Regex("\"type\":\"base64\"").findAll(encoded).count())
        }
    }

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
    fun runtimeObservationRoundTrips() {
        val observation = Message.Instruction.RuntimeObservation("runtime state")
        assertEquals(observation, Json.decodeFromString(Json.encodeToString(observation)))
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
            .messagesForProvider(target(null), setOf(old.id)))
    }

    @Test
    fun selectAllSkipsAnIncompatibleOpaqueCheckpointWithoutReadingItsArchive() {
        val old = text("old"); val full = opaque("full", "OPENAI_SUBSCRIPTION"); val tail = text("tail")
        val history = listOf(old, full, tail)
        assertEquals(listOf(tail), ConversationContext(history)
            .messagesForProvider(target(null), history.mapTo(mutableSetOf(), Message::id)))
    }

    @Test
    fun nativeProviderReplaysItsCheckpointWhileOtherProvidersContinueWithTheTail() {
        val old = text("old"); val full = opaque("full", "CLAUDE_CODE"); val tail = text("tail")
        val context = ConversationContext(listOf(old, full, tail))
        assertEquals(listOf(full, tail), context.messagesForProvider(target("CLAUDE_CODE")))
        assertEquals(listOf(tail), context.messagesForProvider(target("ANTHROPIC_API")))
        assertEquals(listOf(tail), context.messagesForProvider(target("OPENAI_SUBSCRIPTION")))
    }

    @Test
    fun selectiveSummaryStillCoversOriginalsRecoveredFromOpaqueCheckpoint() {
        val a = text("a"); val b = text("b")
        val checkpoint = opaque("checkpoint", "CLAUDE_CODE", listOf(a, b))
        val partial = summary("partial", listOf(a)); val tail = text("tail")
        val history = listOf(a, b, checkpoint, partial, tail)
        val context = ConversationContext(history)
        val selection = history.mapTo(mutableSetOf(), Message::id)

        for (provider in listOf(null, "ANTHROPIC_API", "OPENAI_SUBSCRIPTION")) {
            assertEquals(listOf(partial, tail), context.messagesForProvider(target(provider), selection))
        }
        assertEquals(listOf(checkpoint, partial, tail), context.messagesForProvider(target("CLAUDE_CODE"), selection))
        assertEquals(setOf(a.id, b.id, checkpoint.id), context.protectedMessageIds())
    }

    @Test
    fun nestedOpaqueRecoveryRespectsTransitiveSelectiveCoverage() {
        val a = text("a"); val b = text("b")
        val first = opaque("first", "CLAUDE_CODE", listOf(a, b))
        val partial = summary("partial", listOf(a))
        val second = opaque("second", "CLAUDE_CODE", listOf(first, partial))
        val replacement = summary("replacement", listOf(partial)); val tail = text("tail")
        val history = listOf(a, b, first, partial, second, replacement, tail)
        val context = ConversationContext(history)

        assertEquals(listOf(replacement, tail), context.messagesForProvider(target(null), history.mapTo(mutableSetOf(), Message::id)))
        assertEquals(listOf(second, replacement, tail), context.messagesForProvider(target("CLAUDE_CODE")))
        assertEquals(setOf(a.id, b.id, first.id, partial.id, second.id), context.protectedMessageIds())
    }

    @Test
    fun recoveredReadableCheckpointCanBeCoveredByALaterSelectiveSummary() {
        val a = text("a"); val b = text("b")
        val readable = summary("readable", listOf(a, b), full = true)
        val checkpoint = opaque("checkpoint", "CLAUDE_CODE", listOf(readable))
        val replacement = summary("replacement", listOf(readable)); val tail = text("tail")
        val context = ConversationContext(listOf(a, b, readable, checkpoint, replacement, tail))

        assertEquals(listOf(replacement, tail), context.messagesForProvider(target(null)))
        assertEquals(listOf(checkpoint, replacement, tail), context.messagesForProvider(target("CLAUDE_CODE")))
    }

    @Test
    fun opaqueRecoveryDoesNotApplyCoverageFromUnselectedLaterSummaries() {
        val a = text("a"); val b = text("b")
        val checkpoint = opaque("checkpoint", "CLAUDE_CODE", listOf(a, b))
        val partial = summary("partial", listOf(a)); val tail = text("tail")
        val context = ConversationContext(listOf(a, b, checkpoint, partial, tail))

        assertEquals(emptyList(), context.messagesForProvider(target(null), setOf(checkpoint.id)))
        assertEquals(listOf(tail), context.messagesForProvider(target(null), setOf(a.id, b.id, checkpoint.id, tail.id)))
    }

    @Test
    fun opaqueCheckpointWithoutSourcesIsSkippedForAnIncompatibleTarget() {
        assertEquals(emptyList(), ConversationContext(listOf(opaque("checkpoint", "OPENAI_SUBSCRIPTION")))
            .messagesForProvider(target("CLAUDE_CODE")))
    }

    @Test
    fun opaqueCheckpointRequiresCompatibleKnownModelScope() {
        val checkpoint = opaque("checkpoint", "OPENAI_SUBSCRIPTION").copy(content = listOf(
            (opaque("checkpoint", "OPENAI_SUBSCRIPTION").content.single() as Compaction).copy(
                providerScope = Compaction.ProviderScope(
                    provider = "OPENAI_SUBSCRIPTION",
                    connectionId = "connection",
                    modelConfigurationId = "configuration-a",
                    modelName = "model-a",
                ),
            ),
        ))
        val tail = text("tail")

        assertEquals(listOf(checkpoint, tail), ConversationContext(listOf(checkpoint, tail)).messagesForProvider(
            Compaction.ProviderScope("OPENAI_SUBSCRIPTION", "connection", "configuration-a", "model-a"),
        ))
        assertEquals(listOf(tail), ConversationContext(listOf(checkpoint, tail)).messagesForProvider(
            Compaction.ProviderScope("OPENAI_SUBSCRIPTION", "connection", "configuration-b", "model-b"),
        ))
    }

    @Test
    fun ordinaryProviderReplayDoesNotRequireNativeCompactionSupport() {
        val checkpoint = opaque("checkpoint", AiConnection.Kind.ANTHROPIC_API.name)
        val replay = text("assistant").copy(
            role = Message.Role.ASSISTANT,
            content = listOf(Message.ContentItem.Thinking("reason", signature = "signed")) + text("assistant").content,
            providerMetadata = buildJsonObject {
                put("provider", JsonPrimitive(AiConnection.Kind.ANTHROPIC_API.name))
                put("connectionId", JsonPrimitive("connection"))
                put("modelConfigurationId", JsonPrimitive("configuration"))
                put("model", JsonPrimitive("model"))
                put("anthropicContent", JsonPrimitive("signed-content"))
            },
        )
        val target = Compaction.ProviderScope(
            AiConnection.Kind.ANTHROPIC_API.name,
            "connection",
            "configuration",
            "model",
        )

        assertEquals(listOf(replay), ConversationContext(listOf(checkpoint, replay)).messagesForProvider(
            nativeCompactionTarget = null,
            replayTarget = target,
        ))
    }

    @Test
    fun compatibleMetadataOnlyReplayCarrierIsRetained() {
        val metadata = buildJsonObject {
            put("provider", JsonPrimitive(AiConnection.Kind.OPENAI_API.name))
            put("connectionId", JsonPrimitive("connection"))
            put("modelConfigurationId", JsonPrimitive("configuration"))
            put("model", JsonPrimitive("model"))
            put("openAiApiReasoningItems", JsonPrimitive("encrypted-reasoning"))
        }
        val carrier = text("carrier").copy(
            role = Message.Role.ASSISTANT,
            content = emptyList(),
            providerMetadata = metadata,
        )
        val matching = Compaction.ProviderScope(
            AiConnection.Kind.OPENAI_API.name,
            "connection",
            "configuration",
            "model",
        )
        val foreign = matching.copy(modelName = "other-model")

        assertEquals(listOf(carrier), ConversationContext(listOf(carrier)).messagesForProvider(
            nativeCompactionTarget = matching,
            replayTarget = matching,
        ))
        assertEquals(emptyList(), ConversationContext(listOf(carrier)).messagesForProvider(
            nativeCompactionTarget = matching,
            replayTarget = foreign,
        ))
    }

    @Test
    fun incompatibleProviderManagedToolCallAndResultAreDroppedTogether() {
        val callId = Message.ContentItem.ToolCall.Id("provider-call")
        val metadata = buildJsonObject {
            put(AI_PROVIDER_MANAGED_TOOL_METADATA_KEY, JsonPrimitive(true))
            put("provider", JsonPrimitive("OPENAI_SUBSCRIPTION"))
            put("connectionId", JsonPrimitive("connection"))
            put("modelConfigurationId", JsonPrimitive("configuration-a"))
            put("model", JsonPrimitive("model-a"))
        }
        val call = text("call").copy(
            role = Message.Role.ASSISTANT,
            content = listOf(Message.ContentItem.ToolCall(
                callId,
                Message.ContentItem.ToolCall.Data("web.run", JsonObject(emptyMap())),
            )),
            providerMetadata = metadata,
        )
        val result = text("result").copy(content = listOf(Message.ContentItem.ToolResult(
            callId,
            "web.run",
            listOf(Message.ContentItem.ToolResult.Data.Text("result")),
        )))
        val tail = text("tail")

        assertEquals(listOf(tail), ConversationContext(listOf(call, result, tail)).messagesForProvider(
            Compaction.ProviderScope("OPENAI_SUBSCRIPTION", "connection", "configuration-b", "model-b"),
        ))
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

    private fun target(provider: String?): Compaction.ProviderScope? = provider?.let { Compaction.ProviderScope(it) }
}
