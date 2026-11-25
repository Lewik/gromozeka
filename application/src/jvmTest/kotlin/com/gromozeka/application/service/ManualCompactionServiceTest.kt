package com.gromozeka.application.service

import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationPin
import com.gromozeka.domain.model.ConversationContext
import com.gromozeka.domain.model.compactions
import com.gromozeka.domain.model.withoutProviderReplay
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult as Compaction
import com.gromozeka.domain.model.ai.AI_REPLAY_THREAD_METADATA_KEY
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.projectedMessages
import com.gromozeka.domain.model.ai.AiToolChoice
import org.mockito.Mockito
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.SquashType
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiAssistantMessage
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.ConversationCompactionRepository
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.ThreadMessageLink
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.repository.ThreadRepository
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiRuntime
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.ConversationHistoryMutation
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTaskRequirements
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.NoOpDeclarativeStateChangePublisher
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.domain.service.ResolvedAiRuntime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ManualCompactionServiceTest {
    @Test
    fun `foreign nested native checkpoints load only the surviving readable handoff and its tail`() = runBlocking {
        val f = Fixture()
        val readable = f.checkpoint()
        val tail = f.third.copy(id = Conversation.Message.Id("old-tail"))
        val inner = f.checkpoint(opaque = true).copy(id = Conversation.Message.Id("inner"))
        val outer = inner.copy(id = Conversation.Message.Id("outer"), content = listOf(
            inner.compactions().single().copy(coverage = Compaction.Coverage.SELECTED_MESSAGES, sourceMessageIds = listOf(inner.id))
        ))
        listOf(readable, tail, inner, outer).forEach(f::append)
        val history = f.threadMessages.messages(f.initialThread.id)
        val window = CompactionHistoryWindow(f.threadMessages.compactionHeaders(f.initialThread.id))
        val input = window.headers.takeLast(1)
        val selected = setOf(outer.id)
        val foreign = window.messages(f.threadMessages, f.initialThread.id, input, null)
        assertEquals(ConversationContext(history).messagesForProvider(null, selected), foreign)
        assertEquals(listOf(readable.id, tail.id), foreign.map { it.id })
        assertEquals(listOf(readable.id, tail.id, inner.id, outer.id), f.threadMessages.bodyReads)

        f.threadMessages.bodyReads.clear()
        val native = window.messages(f.threadMessages, f.initialThread.id, input, AiConnection.Kind.OPENAI_SUBSCRIPTION.name)
        assertEquals(listOf(outer), native)
        assertEquals(listOf(outer.id), f.threadMessages.bodyReads)
    }

    @Test
    fun `manual selection in a 16000 message thread reads only the tool exchange including through commit`() = runBlocking {
        val f = Fixture()
        repeat(15_996) { f.append(f.third.copy(id = Conversation.Message.Id("archived-$it"))) }
        f.append(f.checkpoint())
        val updated = f.service().compactRuntimeHistory(f.conversation.id, listOf(f.first.id), SquashType.DISTILL)
        val checkpoint = f.threadMessages.messages(updated.currentThread).last().compactions().single()
        assertEquals(listOf(f.first.id, f.toolResult.id), checkpoint.sourceMessageIds)
        assertEquals(checkpoint.sourceMessageIds, f.threadMessages.bodyReads)
    }

    @Test
    fun `repeated native prefix commits preserve only previously eligible reasoning on the committed branch`() = runBlocking {
        val f = Fixture()
        val provider = AiConnection.Kind.OPENAI_API.name
        val scope = Compaction.ProviderScope(provider, "gateway", "configuration", "model")
        val thinking = Conversation.Message.ContentItem.Thinking("", signature = "opaque")
        fun tail(id: String, thread: String, model: String = "model") = f.third.copy(
            id = Conversation.Message.Id(id), role = Conversation.Message.Role.ASSISTANT, content = listOf(thinking),
            providerMetadata = JsonObject(mapOf(
                "provider" to JsonPrimitive(provider), "connectionId" to JsonPrimitive("gateway"),
                "model" to JsonPrimitive(model), "modelConfigurationId" to JsonPrimitive("configuration"),
                AI_REPLAY_THREAD_METADATA_KEY to JsonPrimitive(thread),
            )),
        )
        val original = f.initialThread.id
        val kept = tail("keep", original.value)
        f.append(kept)
        f.append(tail("foreign-branch", "other-thread"))
        f.append(tail("foreign-model", original.value, "other-model"))
        val sources = listOf(f.first.id, f.toolResult.id, f.third.id)
        suspend fun commit(id: String, thread: Conversation.Thread.Id, prefix: List<Conversation.Message.Id>): Conversation.Thread.Id {
            val checkpoint = f.checkpoint(opaque = true)
            val result = checkpoint.compactions().single().copy(
                providerScope = scope,
                sourceMessageIds = emptyList(),
                sourceBoundaryMessageId = prefix.last(),
            )
            return f.committer.commit(thread, checkpoint.copy(id = Conversation.Message.Id(id), content = listOf(result))).currentThread
        }
        fun reasoning(history: List<Conversation.Message>, thread: String) =
            AiRuntimeRequest(emptyList(), history, options = AiRuntimeOptions(toolContext = mapOf("threadId" to thread)))
                .projectedMessages(provider).filter { thinking in it.content }.map { it.id }

        val first = commit("cp1", original, sources)
        assertEquals(listOf(kept.id), reasoning(f.threadMessages.messages(first), first.value))
        val second = commit("cp2", first, sources + Conversation.Message.Id("cp1"))
        val latest = f.threadMessages.messages(second).dropWhile { it.id.value != "cp2" }
        assertEquals(listOf(kept.id), reasoning(latest, second.value))
        assertTrue(reasoning(latest, "real-fork").isEmpty())
        assertEquals(kept, f.messages.values.getValue(kept.id))
    }

    @Test
    fun `every strategy commits one canonical compaction in conversation order`() = runBlocking {
        SquashType.entries.forEach { strategy ->
            val fixture = Fixture()
            val service = fixture.service()

            val updated = service.compactRuntimeHistory(
                conversationId = fixture.conversation.id,
                messageIds = listOf(fixture.third.id, fixture.first.id),
                strategy = strategy,
            )

            assertEquals(2, fixture.threads.values.size)
            assertEquals(4, fixture.messages.values.size)
            assertEquals(updated.currentThread, fixture.conversations.values.getValue(fixture.conversation.id).currentThread)
            assertEquals(listOf(fixture.first, fixture.toolResult, fixture.third), fixture.threadMessages.messages(fixture.initialThread.id))

            val compactedMessages = fixture.threadMessages.messages(updated.currentThread)
            assertEquals(4, compactedMessages.size)
            val result = ConversationContext(compactedMessages).messages().single().content.single() as
                Conversation.Message.ContentItem.ContextCompactionResult
            assertEquals(listOf(fixture.first.id, fixture.toolResult.id, fixture.third.id), result.sourceMessageIds)
            assertEquals(Compaction.Coverage.SELECTED_MESSAGES, result.coverage)
            assertEquals(strategy.expectedResultStrategy(), result.strategy)
            assertEquals(Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED, result.origin)
            val text = (result.payload as
                Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary).text

            if (strategy == SquashType.CONCATENATE) {
                assertContains(text, "I will inspect it")
                assertContains(text, "[tool_call:read_file]")
                assertContains(text, "[tool_result:read_file")
                assertContains(text, "raw output")
                assertNull(result.providerScope)
                assertNull(result.promptTemplate)
            } else {
                assertEquals("AI compacted text", text)
                assertEquals(AiConnection.Kind.OPENAI_SUBSCRIPTION.name, result.providerScope?.provider)
                assertEquals("connection", result.providerScope?.connectionId)
                assertEquals("model-config", result.providerScope?.modelConfigurationId)
                assertEquals("gpt-test", result.providerScope?.modelName)
                assertEquals(2, result.promptTemplate?.version)
                assertContains(result.promptTemplate?.id.orEmpty(), strategy.name.lowercase())
            }
        }
    }

    @Test fun `AI squash uses current ordered pins while concatenate makes no model request`() = runBlocking<Unit> {
        for (strategy in SquashType.entries) {
            val f = Fixture()
            f.pins.values = listOf(testPin(f.conversation.id, "SQUASH_PIN"))
            f.service().compactRuntimeHistory(f.conversation.id, listOf(f.first.id, f.third.id), strategy)
            if (strategy == SquashType.CONCATENATE) {
                assertTrue(f.pins.reads.isEmpty()); assertTrue(f.runtimeProvider.requests.isEmpty())
            } else {
                assertEquals(listOf(f.conversation.id), f.pins.reads)
                assertTrue(f.runtimeProvider.requests.single().systemPrompts.last().contains("SQUASH_PIN"))
                assertEquals(AiToolChoice.None, f.runtimeProvider.requests.single().options.toolChoice)
            }
            assertEquals("SQUASH_PIN", f.pins.values.single().text)
        }
    }

    @Test
    fun `AI failure leaves conversation storage unchanged`() = runBlocking {
        val fixture = Fixture(runtimeFailure = IllegalStateException("provider failed"))
        val service = fixture.service()

        assertFailsWith<IllegalStateException> {
            service.compactRuntimeHistory(
                conversationId = fixture.conversation.id,
                messageIds = listOf(fixture.first.id, fixture.third.id),
                strategy = SquashType.COMPACT,
            )
        }

        assertEquals(fixture.initialThread.id, fixture.conversations.values.getValue(fixture.conversation.id).currentThread)
        assertEquals(1, fixture.threads.values.size)
        assertEquals(3, fixture.messages.values.size)
        assertEquals(3, fixture.threadMessages.links.values.flatten().size)
    }

    @Test
    fun `concurrent thread change leaves no partial compaction state`() = runBlocking {
        val fixture = Fixture(commitConflict = true)

        assertFailsWith<IllegalStateException> {
            fixture.service().compactRuntimeHistory(
                conversationId = fixture.conversation.id,
                messageIds = listOf(fixture.first.id, fixture.third.id),
                strategy = SquashType.CONCATENATE,
            )
        }

        assertEquals(listOf(1, 3, 1, 3), fixture.storageCounts())
        assertEquals(fixture.initialThread.id, fixture.conversations.values.getValue(fixture.conversation.id).currentThread)
    }

    @Test
    fun `select all after a checkpoint compacts the effective context without duplicate originals`() = runBlocking {
        val fixture = Fixture()
        val service = fixture.service()
        val first = service.compactRuntimeHistory(
            fixture.conversation.id, listOf(fixture.first.id, fixture.third.id), SquashType.COMPACT,
        )
        val firstHistory = fixture.threadMessages.messages(first.currentThread)
        val updated = service.compactRuntimeHistory(
            fixture.conversation.id, firstHistory.map { it.id }, SquashType.DISTILL,
        )
        val request = fixture.runtimeProvider.requests.last()
        val text = (request.messages.single().content.single() as Conversation.Message.ContentItem.UserMessage).text
        assertContains(text, "AI compacted text")
        assertFalse(text.contains("raw output"))
        val history = fixture.threadMessages.messages(updated.currentThread)
        assertEquals(5, history.size)
        assertEquals(1, ConversationContext(history).messages().size)
        assertEquals(firstHistory, fixture.threadMessages.messages(first.currentThread))
    }

    @Test
    fun `single old source can be compacted or distilled without unlocking a full checkpoint`() = runBlocking {
        listOf(SquashType.COMPACT, SquashType.DISTILL).forEach { strategy ->
            val fixture = Fixture()
            val boundary = fixture.checkpoint(opaque = true)
            fixture.append(boundary)
            val updated = fixture.service().compactRuntimeHistory(
                fixture.conversation.id, listOf(fixture.third.id), strategy,
            )
            val history = fixture.threadMessages.messages(updated.currentThread)
            assertEquals(5, history.size)
            assertTrue(fixture.third.id in ConversationContext(history).protectedMessageIds())
            assertEquals(listOf(boundary, history.last()), ConversationContext(history).messages())
            assertFalse(history.last().id in ConversationContext(history).protectedMessageIds())
            val request = fixture.runtimeProvider.requests.single()
            assertTrue(request.messages.single().providerMetadata.isEmpty())
            assertEquals(AiToolChoice.None, request.options.toolChoice)
            assertEquals("MESSAGE_SQUASH", request.options.usagePurpose)
            val text = (request.messages.single().content.single() as Conversation.Message.ContentItem.UserMessage).text
            assertContains(text, "Keep this decision")
            assertFalse(text.contains("raw output"))
        }
    }

    @Test
    fun `single message concatenate is rejected before storage or provider calls`() = runBlocking {
        val fixture = Fixture()
        assertFailsWith<IllegalArgumentException> {
            fixture.service().compactRuntimeHistory(fixture.conversation.id, listOf(fixture.third.id), SquashType.CONCATENATE)
        }
        assertEquals(listOf(1, 3, 1, 3), fixture.storageCounts())
        assertTrue(fixture.runtimeProvider.requests.isEmpty())
    }

    @Test
    fun `select all with opaque checkpoint recovers original material for helper`() = runBlocking {
        val fixture = Fixture()
        fixture.append(fixture.checkpoint(opaque = true))
        val original = fixture.threadMessages.messages(fixture.initialThread.id)
        val updated = fixture.service().compactRuntimeHistory(
            fixture.conversation.id, original.map { it.id }, SquashType.COMPACT,
        )
        val text = fixture.runtimeProvider.requests.single().messages.single().content.single().toString()
        assertContains(text, "raw output")
        assertContains(text, "Keep this decision")
        assertFalse(text.contains("encrypted_content"))
        assertEquals(1, ConversationContext(fixture.threadMessages.messages(updated.currentThread)).messages().size)
    }

    @Test
    fun `repeated select all after opaque and selective compaction respects coverage including after fork`() = runBlocking {
        for (strategy in listOf(SquashType.COMPACT, SquashType.DISTILL)) {
            for (forked in listOf(false, true)) {
                for (coverToolPair in listOf(false, true)) {
                    val case = "$strategy fork=$forked toolPair=$coverToolPair"
                    val fixture = Fixture()
                    val service = fixture.service()
                    fixture.append(fixture.checkpoint(opaque = true))
                    val original = fixture.threadMessages.messages(fixture.initialThread.id)
                    val selected = if (coverToolPair) fixture.first else fixture.third
                    val partial = service.compactRuntimeHistory(fixture.conversation.id, listOf(selected.id), strategy)
                    val partialHistory = fixture.threadMessages.messages(partial.currentThread)
                    assertEquals(
                        if (coverToolPair) listOf(fixture.first.id, fixture.toolResult.id) else listOf(fixture.third.id),
                        partialHistory.last().compactions().single().sourceMessageIds,
                        case,
                    )

                    val target = if (forked) fixture.historyService().fork(partial.id) else partial
                    val targetHistory = fixture.threadMessages.messages(target.currentThread)
                    val targetIds = targetHistory.map { it.id }
                    assertTrue(targetHistory.flatMap { it.compactions() }.flatMap { it.sourceMessageIds }.all { it in targetIds }, message = case)
                    val updated = service.compactRuntimeHistory(target.id, targetIds, strategy)

                    val request = fixture.runtimeProvider.requests.last()
                    val message = request.messages.single()
                    val text = (message.content.single() as Conversation.Message.ContentItem.UserMessage).text
                    assertEquals(Conversation.Message.Role.USER, message.role, message = case)
                    assertTrue(message.providerMetadata.isEmpty(), message = case)
                    assertEquals(AiToolChoice.None, request.options.toolChoice, message = case)
                    assertEquals("MESSAGE_SQUASH", request.options.usagePurpose, message = case)
                    assertEquals(1, Regex("AI compacted text").findAll(text).count(), message = case)
                    assertFalse(text.contains("encrypted_content"), message = case)
                    if (coverToolPair) {
                        assertContains(text, "Keep this decision", message = case)
                        assertFalse(text.contains("I will inspect it"), message = case)
                        assertFalse(text.contains("raw output"), message = case)
                        assertFalse(text.contains("[tool_call:"), message = case)
                        assertFalse(text.contains("[tool_result:"), message = case)
                    } else {
                        assertFalse(text.contains("Keep this decision"), message = case)
                        assertContains(text, "I will inspect it", message = case)
                        assertContains(text, "raw output", message = case)
                        assertContains(text, "[tool_call:read_file]", message = case)
                        assertContains(text, "[tool_result:read_file error=false call_id=", message = case)
                    }

                    val history = fixture.threadMessages.messages(updated.currentThread)
                    assertEquals(targetHistory, history.dropLast(1), message = case)
                    val checkpoint = history.last().compactions().single()
                    assertTrue(checkpoint.sourceMessageIds.isEmpty(), message = case)
                    assertEquals(targetIds.last(), checkpoint.sourceBoundaryMessageId, message = case)
                    assertEquals(Compaction.Coverage.ALL_PREVIOUS, checkpoint.coverage, message = case)
                    assertEquals(Compaction.Origin.USER_REQUESTED, checkpoint.origin, message = case)
                    assertTrue(targetIds.all { it in ConversationContext(history).protectedMessageIds() }, message = case)
                    assertEquals(listOf(history.last()), ConversationContext(history).messages(), message = case)
                    assertEquals(targetHistory, fixture.threadMessages.messages(target.currentThread), message = case)
                    assertEquals(original, fixture.threadMessages.messages(fixture.initialThread.id), message = case)
                    assertEquals(partialHistory, fixture.threadMessages.messages(partial.currentThread), message = case)
                    if (forked) {
                        assertEquals(partial.currentThread, fixture.conversations.values.getValue(partial.id).currentThread, message = case)
                    }

                    val repeated = service.compactRuntimeHistory(target.id, history.map { it.id }, strategy)
                    val repeatedText = (fixture.runtimeProvider.requests.last().messages.single().content.single()
                        as Conversation.Message.ContentItem.UserMessage).text
                    assertEquals(1, Regex("AI compacted text").findAll(repeatedText).count(), message = case)
                    assertFalse(repeatedText.contains("raw output"), message = case)
                    assertFalse(repeatedText.contains("Keep this decision"), message = case)
                    val repeatedHistory = fixture.threadMessages.messages(repeated.currentThread)
                    val repeatedCheckpoint = repeatedHistory.last().compactions().single()
                    assertTrue(repeatedCheckpoint.sourceMessageIds.isEmpty(), message = case)
                    assertEquals(history.last().id, repeatedCheckpoint.sourceBoundaryMessageId, message = case)
                    assertEquals(1, ConversationContext(repeatedHistory).messages().size, message = case)
                    assertEquals(history, fixture.threadMessages.messages(updated.currentThread), message = case)
                    assertEquals(3, fixture.runtimeProvider.requests.size, message = case)
                }
            }
        }
    }

    @Test
    fun `editing selective source invalidates nested summaries and restores the other sources`() = runBlocking {
        val fixture = Fixture()
        val first = fixture.service().compactRuntimeHistory(
            fixture.conversation.id, listOf(fixture.first.id, fixture.third.id), SquashType.COMPACT,
        )
        val summaryId = fixture.threadMessages.messages(first.currentThread).last().id
        val tail = fixture.third.copy(id = Conversation.Message.Id("tail"))
        fixture.append(tail, first.currentThread)
        val second = fixture.service().compactRuntimeHistory(fixture.conversation.id, listOf(summaryId), SquashType.DISTILL)
        val before = fixture.threadMessages.messages(second.currentThread)
        val updated = fixture.historyService().editRuntimeHistory(
            fixture.conversation.id, fixture.third.id, listOf(Conversation.Message.ContentItem.UserMessage("Changed decision")),
        )!!
        val after = fixture.threadMessages.messages(updated.currentThread)
        assertEquals(4, after.size)
        assertTrue(after.all { it.compactions().isEmpty() })
        assertEquals(listOf(fixture.first, fixture.toolResult), after.take(2))
        assertEquals(listOf(fixture.third.id), after[2].originalIds)
        assertEquals(tail, after.last())
        assertEquals(before, fixture.threadMessages.messages(second.currentThread))
    }

    @Test
    fun `deleting selective source invalidates summary while deleting summary restores all sources`() = runBlocking {
        listOf(false, true).forEach { deleteSummary ->
            val fixture = Fixture()
            val compacted = fixture.service().compactRuntimeHistory(
                fixture.conversation.id, listOf(fixture.first.id, fixture.third.id), SquashType.COMPACT,
            )
            val selected = if (deleteSummary) fixture.threadMessages.messages(compacted.currentThread).last().id else fixture.third.id
            val updated = fixture.historyService().deleteRuntimeHistory(fixture.conversation.id, listOf(selected))!!
            val remaining = fixture.threadMessages.messages(updated.currentThread)
            assertEquals(if (deleteSummary) listOf(fixture.first, fixture.toolResult, fixture.third)
                else listOf(fixture.first, fixture.toolResult), remaining)
        }
    }

    @Test
    fun `full checkpoint and its sources reject edit and delete without changing storage`() = runBlocking {
        val fixture = Fixture()
        val checkpoint = fixture.checkpoint()
        fixture.append(checkpoint)
        val counts = fixture.storageCounts()
        listOf(fixture.third.id, checkpoint.id).forEach { id ->
            assertFailsWith<IllegalArgumentException> {
                fixture.historyService().editRuntimeHistory(fixture.conversation.id, id,
                    listOf(Conversation.Message.ContentItem.UserMessage("Not allowed")))
            }
            assertFailsWith<IllegalArgumentException> {
                fixture.historyService().deleteRuntimeHistory(fixture.conversation.id, listOf(id))
            }
        }
        assertEquals(counts, fixture.storageCounts())
    }

    @Test
    fun `tool pair deletion cannot bypass full checkpoint protection`() = runBlocking {
        val fixture = Fixture()
        val checkpoint = fixture.checkpoint()
        fixture.messages.values[checkpoint.id] = checkpoint
        fixture.threadMessages.links[fixture.initialThread.id] = listOf(fixture.first, checkpoint, fixture.toolResult, fixture.third)
            .mapIndexed { index, message -> ThreadMessageLink(fixture.initialThread.id, message.id, index) }.toMutableList()
        val counts = fixture.storageCounts()
        assertFailsWith<IllegalArgumentException> {
            fixture.historyService().deleteRuntimeHistory(fixture.conversation.id, listOf(fixture.toolResult.id))
        }
        assertEquals(counts, fixture.storageCounts())
    }

    @Test
    fun `fork remaps selective coverage and keeps source mutations independent`() = runBlocking {
        val fixture = Fixture()
        val compacted = fixture.service().compactRuntimeHistory(
            fixture.conversation.id, listOf(fixture.first.id, fixture.third.id), SquashType.COMPACT,
        )
        val sourceHistory = fixture.threadMessages.messages(compacted.currentThread)
        val fork = fixture.historyService().fork(fixture.conversation.id)
        val forkHistory = fixture.threadMessages.messages(fork.currentThread)
        assertEquals(4, forkHistory.size)
        assertEquals(1, ConversationContext(forkHistory).messages().size)
        assertEquals(forkHistory.take(3).map { it.id }, forkHistory.last().compactions().single().sourceMessageIds)
        assertTrue(forkHistory.all { it.conversationId == fork.id && it.id !in sourceHistory.map { it.id } })
        val edited = fixture.historyService().editRuntimeHistory(fork.id, forkHistory[2].id,
            listOf(Conversation.Message.ContentItem.UserMessage("Fork-specific decision")))!!
        assertEquals(3, fixture.threadMessages.messages(edited.currentThread).size)
        assertEquals(sourceHistory, fixture.threadMessages.messages(compacted.currentThread))
    }

    @Test
    fun `unreadable or streaming selection fails before invoking the helper`() = runBlocking {
        for (content in listOf(emptyList(), listOf(Conversation.Message.ContentItem.UserMessage(
            "Not finished", state = Conversation.Message.BlockState.STREAMING,
        )))) {
            val fixture = Fixture()
            fixture.messages.values[fixture.third.id] = fixture.third.copy(content = content)
            val counts = fixture.storageCounts()
            assertFailsWith<IllegalArgumentException> {
                fixture.service().compactRuntimeHistory(fixture.conversation.id, listOf(fixture.third.id), SquashType.COMPACT)
            }
            assertEquals(counts, fixture.storageCounts())
            assertTrue(fixture.runtimeProvider.requests.isEmpty())
        }
    }

    @Test
    fun `Runtime rows are ordinary manual compaction input`() = runBlocking {
        for (strategy in listOf(SquashType.COMPACT, SquashType.DISTILL)) {
            val fixture = Fixture()
            val runtime = fixture.third.copy(
                id = Conversation.Message.Id("runtime-${strategy.name.lowercase()}"),
                role = Conversation.Message.Role.RUNTIME,
                author = Conversation.Message.Author.Runtime(),
                content = listOf(Conversation.Message.ContentItem.RuntimeJson(
                    kotlinx.serialization.json.JsonPrimitive("RUNTIME_JSON_${strategy.name}"),
                )),
            )
            fixture.append(runtime)

            fixture.service().compactRuntimeHistory(fixture.conversation.id, listOf(runtime.id), strategy)

            assertContains(fixture.runtimeProvider.requests.single().toString(), "RUNTIME_JSON_${strategy.name}")
        }

        val fixture = Fixture()
        val runtime = fixture.third.copy(
            id = Conversation.Message.Id("runtime-concatenate"),
            role = Conversation.Message.Role.RUNTIME,
            author = Conversation.Message.Author.Runtime(),
            content = listOf(Conversation.Message.ContentItem.RuntimeJson(
                kotlinx.serialization.json.JsonPrimitive("RUNTIME_JSON_CONCATENATE"),
            )),
        )
        fixture.append(runtime)

        val updated = fixture.service().compactRuntimeHistory(
            fixture.conversation.id,
            listOf(fixture.third.id, runtime.id),
            SquashType.CONCATENATE,
        )

        assertContains(fixture.threadMessages.messages(updated.currentThread).last().toString(), "RUNTIME_JSON_CONCATENATE")
    }

    @Test
    fun `opaque checkpoint without active originals fails without reading unrelated archives`() = runBlocking {
        val fixture = Fixture()
        val checkpoint = fixture.checkpoint(opaque = true)
        fixture.messages.values[checkpoint.id] = checkpoint
        fixture.threadMessages.links[fixture.initialThread.id] = mutableListOf(ThreadMessageLink(fixture.initialThread.id, checkpoint.id, 0))
        val counts = fixture.storageCounts()
        assertFailsWith<IllegalArgumentException> {
            fixture.service().compactRuntimeHistory(fixture.conversation.id, listOf(checkpoint.id), SquashType.COMPACT)
        }
        assertEquals(counts, fixture.storageCounts())
        assertTrue(fixture.runtimeProvider.requests.isEmpty())
    }

    @Test fun `full compact keeps the recent suffix while full distill and concatenate consume all history`() = runBlocking {
        for (strategy in SquashType.entries) {
            val f = Fixture()
            val crossing = f.third.copy(id = Conversation.Message.Id("crossing"), content = listOf(Conversation.Message.ContentItem.UserMessage("a".repeat(45_000))))
            val latest = f.third.copy(id = Conversation.Message.Id("latest"), content = listOf(Conversation.Message.ContentItem.UserMessage("b".repeat(40_000))))
            f.append(crossing); f.append(latest)
            val all = f.threadMessages.messages(f.initialThread.id)
            val compacted = f.service().compactRuntimeHistory(f.conversation.id, all.map { it.id }, strategy)
            val active = ConversationContext(f.threadMessages.messages(compacted.currentThread)).messages()
            val checkpoint = active.first().compactions().single()
            assertTrue(checkpoint.sourceMessageIds.isEmpty())
            assertEquals(Compaction.Coverage.ALL_PREVIOUS, checkpoint.coverage)
            assertEquals(Compaction.Origin.USER_REQUESTED, checkpoint.origin)
            if (strategy == SquashType.COMPACT) {
                assertEquals(3, active.size)
                assertEquals(listOf(crossing, latest), active.takeLast(2))
                assertEquals(all[2].id, checkpoint.sourceBoundaryMessageId)
            } else {
                assertEquals(listOf(active.first()), active)
                assertEquals(latest.id, checkpoint.sourceBoundaryMessageId)
            }
            if (strategy == SquashType.CONCATENATE) assertTrue(f.runtimeProvider.requests.isEmpty())

            val queued = f.third.copy(
                id = Conversation.Message.Id("queued-after-${strategy.name.lowercase()}"),
                content = listOf(Conversation.Message.ContentItem.UserMessage("queued after compact")),
            )
            assertNotNull(f.historyService().appendRuntimeMessage(f.conversation.id, queued))
            assertEquals(queued, f.threadMessages.messages(compacted.currentThread).last())
            assertEquals(queued, ConversationContext(f.threadMessages.messages(compacted.currentThread)).messages().last())
        }
        for (strategy in listOf(SquashType.COMPACT, SquashType.DISTILL)) {
            val f = Fixture()
            val crossing = f.third.copy(id = Conversation.Message.Id("crossing"), content = listOf(Conversation.Message.ContentItem.UserMessage("a".repeat(45_000))))
            val latest = f.third.copy(id = Conversation.Message.Id("latest"), content = listOf(Conversation.Message.ContentItem.UserMessage("b".repeat(40_000))))
            f.append(crossing); f.append(latest)
            val selection = f.service().compactRuntimeHistory(f.conversation.id, listOf(crossing.id), strategy)
            val after = ConversationContext(f.threadMessages.messages(selection.currentThread)).messages()
            assertTrue(after.none { it.id == crossing.id })
            assertTrue(after.any { it.id == latest.id && it.content == latest.content })
        }
    }

    @Test fun `queued history mutation is stale after an earlier copy-on-write change`() = runBlocking {
        val f = Fixture()
        val history = f.historyService()
        val initialThread = f.conversation.currentThread
        val edited = assertNotNull(history.editRuntimeHistory(
            f.conversation.id,
            f.third.id,
            listOf(Conversation.Message.ContentItem.UserMessage("first mutation")),
        ))
        val before = f.storageCounts()
        val executor = ConversationRuntimeHistoryMutationExecutor(history, f.service())
        val mutation = ConversationHistoryMutation.Delete(listOf(f.first.id))
        val task = ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("stale-history-mutation"),
            conversationId = f.conversation.id,
            actorUserId = User.Id("user"),
            payload = ConversationRuntimeTask.Payload.HistoryMutation(mutation, initialThread),
            placement = QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "stale-history-mutation",
            requirements = ConversationRuntimeTaskRequirements(
                setOf(ConversationRuntimeCapability.CONVERSATION_TURN),
                ConversationRuntimeTaskTarget.Server,
            ),
            createdAt = Clock.System.now(),
        )

        assertFailsWith<IllegalStateException> {
            executor.execute(f.conversation.id, mutation, initialThread, task) {}
        }
        assertEquals(edited.currentThread, f.conversations.values.getValue(f.conversation.id).currentThread)
        assertEquals(before, f.storageCounts())
        assertTrue(f.runtimeProvider.requests.isEmpty())
    }

    @Test fun `full manual compaction with only the raw suffix fails explicitly without generation`() = runBlocking {
        val f = Fixture()
        val history = f.threadMessages.messages(f.initialThread.id)
        val before = f.storageCounts()
        assertFailsWith<IllegalArgumentException> { f.service().compactRuntimeHistory(f.conversation.id, history.map { it.id }, SquashType.COMPACT) }
        assertEquals(before, f.storageCounts())
        assertTrue(f.runtimeProvider.requests.isEmpty())
    }

    @Test fun `history edits reject both Runtime authored completion messages before any write`() = runBlocking {
        val f = Fixture()
        val callId = Conversation.Message.ContentItem.ToolCall.Id("runtime-completion")
        val blocks = listOf(
            Conversation.Message.ContentItem.ToolCall(callId,
                Conversation.Message.ContentItem.ToolCall.Data("tool", kotlinx.serialization.json.JsonObject(emptyMap()))),
            Conversation.Message.ContentItem.ToolResult(callId, "tool", listOf(
                Conversation.Message.ContentItem.ToolResult.Data.Text("Server-owned outcome"))),
        )
        val counts = f.storageCounts()
        for (block in blocks) {
            f.messages.values[f.third.id] = f.third.copy(
                role = if (block is Conversation.Message.ContentItem.ToolCall) Conversation.Message.Role.ASSISTANT else Conversation.Message.Role.USER,
                author = Conversation.Message.Author.Runtime(), content = listOf(block),
            )
            assertFailsWith<IllegalArgumentException> {
                f.historyService().editRuntimeHistory(f.conversation.id, f.third.id,
                    listOf(Conversation.Message.ContentItem.UserMessage("client replacement")))
            }
            assertEquals(counts, f.storageCounts())
            assertEquals(block, f.messages.values.getValue(f.third.id).content.single())
        }
    }

    private class Fixture(
        runtimeFailure: Throwable? = null,
        commitConflict: Boolean = false,
    ) {
        val initialThread = Conversation.Thread(
            id = Conversation.Thread.Id("thread-initial"),
            conversationId = Conversation.Id("conversation"),
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        val conversation = Conversation(
            id = initialThread.conversationId,
            projectId = Project.Id("project"),
            participants = setOf(
                Conversation.Participant.User(User.Id("user")),
                Conversation.Participant.Agent(AgentDefinition.Id("agent")),
            ),
            displayName = "Test",
            currentThread = initialThread.id,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        private val toolCallId = Conversation.Message.ContentItem.ToolCall.Id("tool-1")
        val first = message(
            id = "first",
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(
                Conversation.Message.ContentItem.AssistantMessage(
                    Conversation.Message.StructuredText(fullText = "I will inspect it")
                ),
                Conversation.Message.ContentItem.ToolCall(
                    id = toolCallId,
                    call = Conversation.Message.ContentItem.ToolCall.Data(
                        name = "read_file",
                        input = JsonObject(mapOf("path" to JsonPrimitive("README.md"))),
                    ),
                ),
            ),
        )
        val toolResult = message(
            id = "tool-result",
            role = Conversation.Message.Role.USER,
            content = listOf(
                Conversation.Message.ContentItem.ToolResult(
                    toolUseId = toolCallId,
                    toolName = "read_file",
                    result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("raw output")),
                )
            ),
        )
        val third = message(
            id = "third",
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Keep this decision")),
        )
        val conversations = InMemoryConversationRepository(conversation)
        val messages = InMemoryMessageRepository(first, toolResult, third)
        val threads = InMemoryThreadRepository(initialThread)
        val threadMessages = InMemoryThreadMessageRepository(messages).also { repository ->
            repository.links[initialThread.id] = mutableListOf(
                ThreadMessageLink(initialThread.id, first.id, 0),
                ThreadMessageLink(initialThread.id, toolResult.id, 1),
                ThreadMessageLink(initialThread.id, third.id, 2),
            )
        }
        private val compactions = InMemoryConversationCompactionRepository(
            conversations = conversations,
            messages = messages,
            threads = threads,
            threadMessages = threadMessages,
            forceConflict = commitConflict,
        )
        val runtimeProvider = FixedRuntimeProvider(runtimeFailure)
        val pins = conversations.pinCatalog
        private val configurationProvider = FixedConfigurationProvider()

        private val pairingService = ToolCallPairingService()
        val committer = ContextCompactionCommitService(
            conversations, compactions, threadMessages, pairingService, NoOpDeclarativeStateChangePublisher,
        )

        fun service(): ManualCompactionService {
            val artifacts = Mockito.mock(ConversationArtifactApplicationService::class.java) { invocation ->
                if (invocation.method.name.startsWith("materializeSnapshot")) invocation.arguments[1]
                else org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)
            }
            val agentService = Mockito.mock(com.gromozeka.domain.service.AgentDomainService::class.java) { invocation ->
                if (invocation.method.name.startsWith("findById")) AgentDefinition(
                    id = AgentDefinition.Id("agent"), name = "Agent", prompts = emptyList(),
                    runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model-config")),
                    type = AgentDefinition.Type.Global, createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
                ) else org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)
            }
            val conversationPins = ConversationPinnedPrompt(pins)
            val textCompactions = CompactionTextService(
                aiRuntimeProvider = runtimeProvider,
                aiConfigurationProvider = configurationProvider,
                artifacts = artifacts,
                conversationPins = conversationPins,
            )
            val fullCompactions = ConversationCompactionService(
                configurations = configurationProvider,
                history = threadMessages,
                textCompactions = textCompactions,
                committer = committer,
                runtimeCoordinator = mock(),
                events = mock(),
                runtimes = runtimeProvider,
                preparations = mock(),
                preparationScope = CompactionPreparationScope(
                    conversations = conversations,
                    agents = agentService,
                    users = mock(),
                    projects = mock(),
                    workers = mock(),
                    configurations = configurationProvider,
                    pins = conversationPins,
                ),
                toolPairs = pairingService,
            )
            return ManualCompactionService(
                agents = agentService,
                conversationRepository = conversations,
                threadMessageRepository = threadMessages,
                toolCallPairingService = pairingService,
                textCompactions = textCompactions,
                fullCompactions = fullCompactions,
                compactionCommitter = committer,
            )
        }

        fun checkpoint(opaque: Boolean = false) = message(
            "boundary", Conversation.Message.Role.ASSISTANT, listOf(Compaction(
                payload = if (opaque) Compaction.Payload.OpaqueProviderState(JsonObject(mapOf("encrypted_content" to JsonPrimitive("opaque"))))
                    else Compaction.Payload.ReadableSummary("Earlier context"),
                origin = Compaction.Origin.PROVIDER_AUTO,
                strategy = Compaction.Strategy.PROVIDER_MANAGED,
                sourceBoundaryMessageId = third.id,
                providerScope = Compaction.ProviderScope(provider = AiConnection.Kind.OPENAI_SUBSCRIPTION.name),
                coverage = Compaction.Coverage.ALL_PREVIOUS,
            )),
        )

        fun append(message: Conversation.Message, threadId: Conversation.Thread.Id = initialThread.id) {
            messages.values[message.id] = message
            val links = threadMessages.links.getValue(threadId)
            links += ThreadMessageLink(threadId, message.id, links.size)
        }

        private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)

        fun historyService() = ConversationApplicationService(
            conversationRepo = conversations, threadRepo = threads, messageRepo = messages, threadMessageRepo = threadMessages,
            projectService = mock(), projectAccessService = mock(), agentService = mock(),
            toolCallPairingService = ToolCallPairingService(), conversationTabLayoutService = mock(),
            conversationUnreadStateService = mock(), artifactService = Mockito.mock(ConversationArtifactApplicationService::class.java) { invocation ->
                if (invocation.method.name.startsWith("cloneReferences")) invocation.arguments[2]
                else org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)
            }, suggestedRepliesGenerationService = mock(),
            settingsProvider = mock(),
            messageBatchRepo = object : com.gromozeka.domain.repository.ConversationMessageBatchRepository {
                override suspend fun append(conversationId: Conversation.Id, expectedThreadId: Conversation.Thread.Id,
                    batch: List<Conversation.Message>, at: kotlin.time.Instant): List<Conversation.Message> {
                    val linked = threadMessages.links.getValue(expectedThreadId).map { it.messageId }.toSet()
                    val appended = batch.filterNot { it.id in linked }
                    appended.forEach { append(it, expectedThreadId) }
                    return appended
                }
            },
        )

        fun storageCounts(): List<Int> = listOf(
            conversations.values.size,
            messages.values.size,
            threads.values.size,
            threadMessages.links.values.sumOf { it.size },
        )

        private fun message(
            id: String,
            role: Conversation.Message.Role,
            content: List<Conversation.Message.ContentItem>,
        ): Conversation.Message = Conversation.Message(
            id = Conversation.Message.Id(id),
            conversationId = conversation.id,
            role = role,
            content = content,
            createdAt = Clock.System.now(),
        )
    }
}

private class FixedRuntimeProvider(
    private val failure: Throwable?,
) : AiRuntimeProvider {
    val requests = mutableListOf<AiRuntimeRequest>()
    override fun getRuntime(selection: AiRuntimeSelection, workspaceRootPath: String?): AiRuntime = object : AiRuntime {
        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            requests += request
            failure?.let { throw it }
            return AiRuntimeResponse(
                messages = listOf(
                    AiAssistantMessage(
                        content = listOf(
                            Conversation.Message.ContentItem.AssistantMessage(
                                Conversation.Message.StructuredText(fullText = "AI compacted text")
                            )
                        )
                    )
                )
            )
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()
    }
}

private class FixedConfigurationProvider : AiConfigurationProvider {
    private val selection = AiRuntimeSelection(AiModelConfiguration.Id("model-config"))
    private val resolved = run {
        val connection = AiConnection.OpenAiSubscription(
            id = AiConnection.Id("connection"),
            displayName = "Subscription",
            enabled = true,
        )
        val modelConfiguration = AiModelConfiguration(
            id = selection.modelConfigurationId,
            connectionId = connection.id,
            providerModelId = "gpt-test",
            displayName = "Test model",
        )
        ResolvedAiRuntime(
            connection = connection,
            modelConfiguration = modelConfiguration,
            modelSpec = AiModelSpec(
                id = modelConfiguration.providerModelId,
                provider = AiProvider.OPENAI,
                capabilities = setOf(AiModelCapability.TEXT_GENERATION),
                limits = AiModelSpec.Limits(
                    textGeneration = AiModelSpec.Limits.TextGeneration(contextWindowTokens = 1_000),
                ),
            ),
        )
    }

    override val snapshotFlow: StateFlow<AiCatalogSnapshot?> = MutableStateFlow(null)
    override val snapshot: AiCatalogSnapshot
        get() = error("Not used")

    override fun runtimeSelectionFor(purpose: AiRuntimeAssignment.Purpose): AiRuntimeSelection = selection

    override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime = resolved
}

private class InMemoryConversationRepository(
    vararg conversations: Conversation,
) : ConversationRepository {
    val values = conversations.associateBy(Conversation::id).toMutableMap()
    val pinCatalog = ReadOnlyTestPins()

    override suspend fun create(conversation: Conversation): Conversation = conversation.also { values[it.id] = it }
    override suspend fun createWithCopiedPins(conversation: Conversation, sourceConversationId: Conversation.Id): Conversation {
        val source = requireNotNull(values[sourceConversationId])
        require(source.projectId == conversation.projectId)
        check(conversation.id !in values)
        val copies = pinCatalog.values.filter { it.conversationId == sourceConversationId }.map { pin ->
            pin.copy(id = ConversationPin.Id("copy:${conversation.id.value}:${pin.id.value}"), conversationId = conversation.id,
                revision = 1, createdAt = conversation.createdAt, updatedAt = conversation.createdAt)
        }
        values[conversation.id] = conversation
        pinCatalog.values = pinCatalog.values + copies
        return conversation
    }
    override suspend fun findById(id: Conversation.Id): Conversation? = values[id]
    override suspend fun findByProject(projectId: Project.Id): List<Conversation> =
        values.values.filter { it.projectId == projectId }
    override suspend fun delete(id: Conversation.Id) {
        values.remove(id)
    }
    override suspend fun updateCurrentThread(id: Conversation.Id, threadId: Conversation.Thread.Id) {
        values.computeIfPresent(id) { _, conversation -> conversation.copy(currentThread = threadId) }
    }
    override suspend fun updateDisplayName(id: Conversation.Id, displayName: String) = Unit
    override suspend fun updateParticipantSettings(id: Conversation.Id, update: (Conversation) -> Conversation): Conversation? = null
    override suspend fun touch(id: Conversation.Id) = Unit
}

private class InMemoryMessageRepository(
    vararg messages: Conversation.Message,
) : MessageRepository {
    val values = messages.associateBy(Conversation.Message::id).toMutableMap()

    override suspend fun save(message: Conversation.Message): Conversation.Message = message.also {
        check(values.putIfAbsent(it.id, it) == null)
    }
    override suspend fun saveBatch(messages: List<Conversation.Message>) {
        check(messages.map { it.id }.distinct().size == messages.size && messages.none { it.id in values })
        messages.forEach { values[it.id] = it }
    }
    override suspend fun findById(id: Conversation.Message.Id): Conversation.Message? = values[id]
    override suspend fun findByIds(ids: List<Conversation.Message.Id>): List<Conversation.Message> = ids.mapNotNull(values::get)
    override suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.Message> =
        values.values.filter { it.conversationId == conversationId }
    override suspend fun findVersions(originalId: Conversation.Message.Id): List<Conversation.Message> =
        values.values.filter { originalId in it.originalIds }
}

private class InMemoryThreadRepository(
    vararg threads: Conversation.Thread,
) : ThreadRepository {
    val values = threads.associateBy(Conversation.Thread::id).toMutableMap()

    override suspend fun save(thread: Conversation.Thread): Conversation.Thread = thread.also {
        check(values.putIfAbsent(it.id, it) == null)
    }
    override suspend fun findById(id: Conversation.Thread.Id): Conversation.Thread? = values[id]
    override suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.Thread> =
        values.values.filter { it.conversationId == conversationId }
    override suspend fun delete(id: Conversation.Thread.Id) {
        values.remove(id)
    }
    override suspend fun updateTimestamp(id: Conversation.Thread.Id, updatedAt: kotlin.time.Instant) = Unit
}

private class InMemoryThreadMessageRepository(
    private val messages: InMemoryMessageRepository,
) : ThreadMessageRepository {
    val links = mutableMapOf<Conversation.Thread.Id, MutableList<ThreadMessageLink>>()

    override suspend fun add(
        threadId: Conversation.Thread.Id,
        messageId: Conversation.Message.Id,
        position: Int,
    ) {
        links.getOrPut(threadId, ::mutableListOf).add(ThreadMessageLink(threadId, messageId, position))
    }
    override suspend fun addBatch(links: List<ThreadMessageLink>) {
        links.forEach { link -> add(link.threadId, link.messageId, link.position) }
    }
    override suspend fun getByThread(threadId: Conversation.Thread.Id): List<ThreadMessageLink> =
        links[threadId].orEmpty().sortedBy(ThreadMessageLink::position)
    override suspend fun contains(threadId: Conversation.Thread.Id, messageId: Conversation.Message.Id): Boolean =
        links[threadId].orEmpty().any { it.messageId == messageId }
    override suspend fun getMaxPosition(threadId: Conversation.Thread.Id): Int? =
        links[threadId]?.maxOfOrNull(ThreadMessageLink::position)
    override suspend fun getMessagesByThread(threadId: Conversation.Thread.Id): List<Conversation.Message> =
        messages(threadId)
    override suspend fun hasAtLeastResponseReviewContinuations(
        threadId: Conversation.Thread.Id,
        turnId: String,
        requiredCount: Int,
    ) = error("Response review is outside this compaction fixture")
    val bodyReads = mutableListOf<Conversation.Message.Id>()
    override suspend fun compactionHeaders(threadId: Conversation.Thread.Id) = messages(threadId).map(CompactionMessageHeader::from)
    override suspend fun compactionBodies(threadId: Conversation.Thread.Id, ids: List<Conversation.Message.Id>): List<Conversation.Message> {
        bodyReads += ids
        return ids.map(messages(threadId).associateBy { it.id }::getValue)
    }
    override suspend fun recentVisibleMessageTextThrough(
        threadId: Conversation.Thread.Id,
        throughMessageId: Conversation.Message.Id,
        limit: Int,
        maxCharacters: Int,
    ) = error("Suggested replies are outside this compaction fixture")
    override suspend fun getContextMessagesThrough(
        threadId: Conversation.Thread.Id,
        throughMessageId: Conversation.Message.Id,
        nativeProvider: String?,
    ) = error("Suggested replies are outside this compaction fixture")
    override suspend fun invalidToolSequenceWindow(threadId: Conversation.Thread.Id) =
        error("Tool sequence repair is outside this compaction fixture")
    override suspend fun copyPrefixAndAddSuffix(
        sourceThreadId: Conversation.Thread.Id,
        targetThreadId: Conversation.Thread.Id,
        suffixStartPosition: Int,
        suffixMessageIds: List<Conversation.Message.Id>,
    ) = error("Tool sequence repair is outside this compaction fixture")
    override suspend fun getContextMessagesByThread(
        threadId: Conversation.Thread.Id,
        nativeProvider: String?,
    ): com.gromozeka.domain.repository.ThreadContextMessages {
        val history = messages(threadId)
        val selected = com.gromozeka.domain.model.selectModelHistoryMessageIds(
            history.map { com.gromozeka.domain.model.MessageContextHeader(it.id, com.gromozeka.domain.model.MessageContextMetadata.from(it)) },
            history.filter { com.gromozeka.domain.model.MessageContextMetadata.from(it).hasCompaction }, nativeProvider,
        )
        return com.gromozeka.domain.repository.ThreadContextMessages(
            messages = history.filter { it.id in selected },
            sourceMessageCount = history.size,
        )
    }
    override suspend fun deleteByThread(threadId: Conversation.Thread.Id) {
        links.remove(threadId)
    }

    fun messages(threadId: Conversation.Thread.Id): List<Conversation.Message> =
        links[threadId].orEmpty().sortedBy(ThreadMessageLink::position).map { link ->
            assertNotNull(messages.values[link.messageId])
        }
}

private class InMemoryConversationCompactionRepository(
    private val conversations: InMemoryConversationRepository,
    private val messages: InMemoryMessageRepository,
    private val threads: InMemoryThreadRepository,
    private val threadMessages: InMemoryThreadMessageRepository,
    private val forceConflict: Boolean,
) : ConversationCompactionRepository {
    override suspend fun commitIfCurrent(
        expectedThreadId: Conversation.Thread.Id,
        compactionMessage: Conversation.Message,
        newThread: Conversation.Thread,
        insertionAnchorId: Conversation.Message.Id,
        preparedCompactionId: String?,
    ): Boolean {
        require(preparedCompactionId == null) { "This fixture is for manual compaction" }
        if (forceConflict) return false
        val conversation = conversations.values[compactionMessage.conversationId] ?: return false
        if (conversation.currentThread != expectedThreadId) return false
        val links = threadMessages.getByThread(expectedThreadId)
        val anchor = links.indexOfFirst { it.messageId == insertionAnchorId }
        check(anchor >= 0)
        val ids = links.take(anchor + 1).map { it.messageId } + compactionMessage.id + links.drop(anchor + 1).map { it.messageId }
        messages.save(compactionMessage)
        threads.save(newThread.copy(lastTurnNumber = requireNotNull(threads.findById(expectedThreadId)).lastTurnNumber))
        threadMessages.addBatch(ids.mapIndexed { index, id -> ThreadMessageLink(newThread.id, id, index) })
        conversations.updateCurrentThread(conversation.id, newThread.id)
        return true
    }
}

private fun SquashType.expectedResultStrategy(): Conversation.Message.ContentItem.ContextCompactionResult.Strategy =
    when (this) {
        SquashType.CONCATENATE -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.CONCATENATE
        SquashType.COMPACT -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.COMPACT
        SquashType.DISTILL -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.DISTILL
    }
