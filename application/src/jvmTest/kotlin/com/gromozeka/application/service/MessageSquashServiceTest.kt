package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationContext
import com.gromozeka.domain.model.compactions
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult as Compaction
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
import com.gromozeka.domain.service.NoOpDeclarativeStateChangePublisher
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

class MessageSquashServiceTest {
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
                assertEquals(1, result.promptTemplate?.version)
                assertContains(result.promptTemplate?.id.orEmpty(), strategy.name.lowercase())
            }
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
                strategy = SquashType.SUMMARIZE,
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
    fun `select all after a summary summarizes the effective context without duplicate originals`() = runBlocking {
        val fixture = Fixture()
        val service = fixture.service()
        val first = service.compactRuntimeHistory(
            fixture.conversation.id, listOf(fixture.first.id, fixture.third.id), SquashType.SUMMARIZE,
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
    fun `single old source can be summarized or distilled without unlocking a full checkpoint`() = runBlocking {
        listOf(SquashType.SUMMARIZE, SquashType.DISTILL).forEach { strategy ->
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
            fixture.conversation.id, original.map { it.id }, SquashType.SUMMARIZE,
        )
        val text = fixture.runtimeProvider.requests.single().messages.single().content.single().toString()
        assertContains(text, "raw output")
        assertContains(text, "Keep this decision")
        assertFalse(text.contains("encrypted_content"))
        assertEquals(1, ConversationContext(fixture.threadMessages.messages(updated.currentThread)).messages().size)
    }

    @Test
    fun `editing selective source invalidates nested summaries and restores the other sources`() = runBlocking {
        val fixture = Fixture()
        val first = fixture.service().compactRuntimeHistory(
            fixture.conversation.id, listOf(fixture.first.id, fixture.third.id), SquashType.SUMMARIZE,
        )
        val summaryId = fixture.threadMessages.messages(first.currentThread).last().id
        val second = fixture.service().compactRuntimeHistory(fixture.conversation.id, listOf(summaryId), SquashType.DISTILL)
        val before = fixture.threadMessages.messages(second.currentThread)
        val updated = fixture.historyService().editRuntimeHistory(
            fixture.conversation.id, fixture.third.id, listOf(Conversation.Message.ContentItem.UserMessage("Changed decision")),
        )!!
        val after = fixture.threadMessages.messages(updated.currentThread)
        assertEquals(3, after.size)
        assertTrue(after.all { it.compactions().isEmpty() })
        assertEquals(listOf(fixture.first, fixture.toolResult), after.take(2))
        assertEquals(listOf(fixture.third.id), after.last().originalIds)
        assertEquals(before, fixture.threadMessages.messages(second.currentThread))
    }

    @Test
    fun `deleting selective source invalidates summary while deleting summary restores all sources`() = runBlocking {
        listOf(false, true).forEach { deleteSummary ->
            val fixture = Fixture()
            val compacted = fixture.service().compactRuntimeHistory(
                fixture.conversation.id, listOf(fixture.first.id, fixture.third.id), SquashType.SUMMARIZE,
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
            fixture.conversation.id, listOf(fixture.first.id, fixture.third.id), SquashType.SUMMARIZE,
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
                fixture.service().compactRuntimeHistory(fixture.conversation.id, listOf(fixture.third.id), SquashType.SUMMARIZE)
            }
            assertEquals(counts, fixture.storageCounts())
            assertTrue(fixture.runtimeProvider.requests.isEmpty())
        }
    }

    @Test
    fun `opaque checkpoint without active originals fails without reading unrelated archives`() = runBlocking {
        val fixture = Fixture()
        val checkpoint = fixture.checkpoint(opaque = true)
        fixture.messages.values[checkpoint.id] = checkpoint
        fixture.threadMessages.links[fixture.initialThread.id] = mutableListOf(ThreadMessageLink(fixture.initialThread.id, checkpoint.id, 0))
        val counts = fixture.storageCounts()
        assertFailsWith<IllegalStateException> {
            fixture.service().compactRuntimeHistory(fixture.conversation.id, listOf(checkpoint.id), SquashType.SUMMARIZE)
        }
        assertEquals(counts, fixture.storageCounts())
        assertTrue(fixture.runtimeProvider.requests.isEmpty())
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
        private val configurationProvider = FixedConfigurationProvider()

        fun service(): MessageSquashService {
            val pairingService = ToolCallPairingService()
            val committer = ContextCompactionCommitService(
                conversationRepository = conversations,
                conversationCompactionRepository = compactions,
                threadMessageRepository = threadMessages,
                toolCallPairingService = pairingService,
                stateChanges = NoOpDeclarativeStateChangePublisher,
            )
            return MessageSquashService(
                aiRuntimeProvider = runtimeProvider,
                aiConfigurationProvider = configurationProvider,
                conversationRepository = conversations,
                threadMessageRepository = threadMessages,
                toolCallPairingService = pairingService,
                compactionCommitter = committer,
            )
        }

        fun checkpoint(opaque: Boolean = false) = message(
            "boundary", Conversation.Message.Role.ASSISTANT, listOf(Compaction(
                payload = if (opaque) Compaction.Payload.OpaqueProviderState(JsonObject(mapOf("encrypted_content" to JsonPrimitive("opaque"))))
                    else Compaction.Payload.ReadableSummary("Earlier context"),
                origin = Compaction.Origin.PROVIDER_AUTO,
                strategy = Compaction.Strategy.PROVIDER_MANAGED,
                sourceMessageIds = listOf(first.id, toolResult.id, third.id),
                providerScope = Compaction.ProviderScope(provider = AiConnection.Kind.OPENAI_SUBSCRIPTION.name),
                coverage = Compaction.Coverage.ALL_PREVIOUS,
            )),
        )

        fun append(message: Conversation.Message) {
            messages.values[message.id] = message
            val links = threadMessages.links.getValue(initialThread.id)
            links += ThreadMessageLink(initialThread.id, message.id, links.size)
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

    override suspend fun create(conversation: Conversation): Conversation = conversation.also { values[it.id] = it }
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
    override suspend fun getMaxPosition(threadId: Conversation.Thread.Id): Int? =
        links[threadId]?.maxOfOrNull(ThreadMessageLink::position)
    override suspend fun getMessagesByThread(threadId: Conversation.Thread.Id): List<Conversation.Message> =
        messages(threadId)
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
        newLinks: List<ThreadMessageLink>,
    ): Boolean {
        if (forceConflict) return false
        val conversation = conversations.values[compactionMessage.conversationId] ?: return false
        if (conversation.currentThread != expectedThreadId) return false
        messages.save(compactionMessage)
        threads.save(newThread)
        threadMessages.addBatch(newLinks)
        conversations.updateCurrentThread(conversation.id, newThread.id)
        return true
    }
}

private fun SquashType.expectedResultStrategy(): Conversation.Message.ContentItem.ContextCompactionResult.Strategy =
    when (this) {
        SquashType.CONCATENATE -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.CONCATENATE
        SquashType.SUMMARIZE -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.SUMMARIZE
        SquashType.DISTILL -> Conversation.Message.ContentItem.ContextCompactionResult.Strategy.DISTILL
    }
