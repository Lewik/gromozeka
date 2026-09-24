package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.client.ArtifactTransferService
import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationHistoryPage
import com.gromozeka.domain.model.ConversationHistoryPageRequest
import com.gromozeka.domain.model.ConversationHistoryMessage
import com.gromozeka.domain.model.ConversationHistoryCursor
import com.gromozeka.domain.model.ConversationMessageSelection
import com.gromozeka.domain.model.SquashType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.gromozeka.domain.model.MessageInputContext
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationHistoryService
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.services.NoOpAttachmentAcquisitionController
import com.gromozeka.presentation.services.NoOpTurnCompletionNotificationSink
import com.gromozeka.presentation.services.TurnCompletionNotificationService
import com.gromozeka.presentation.ui.state.UIState
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TabViewModelRuntimeReplayTest {
    @Test
    fun `historical message replay does not reactivate an idle runtime`() = runTest {
        val conversationId = Conversation.Id("conversation-1")
        val runtimeEvents = MutableSharedFlow<ConversationRuntimeEvent>(extraBufferCapacity = 4)
        var historyLoads = 0
        val viewModel = viewModel(backgroundScope, runtimeEvents) {
            historyLoads++
            ConversationHistoryPage(Conversation.Thread.Id("thread-1"), emptyList())
        }

        runCurrent()
        runtimeEvents.emit(
            ConversationRuntimeEvent.SnapshotUpdated(
                conversationId = conversationId,
                snapshot = ConversationRuntimeSnapshot(
                    revision = 1,
                    conversationId = conversationId,
                    state = null,
                    pendingTasks = emptyList(),
                ),
            )
        )
        runtimeEvents.emit(
            ConversationRuntimeEvent.MessageEmitted(
                conversationId = conversationId,
                taskId = null,
                message = Conversation.Message(
                    id = Conversation.Message.Id("message-1"),
                    conversationId = conversationId,
                    role = Conversation.Message.Role.ASSISTANT,
                    content = listOf(
                        Conversation.Message.ContentItem.AssistantMessage(
                            Conversation.Message.StructuredText("Historical response")
                        )
                    ),
                    createdAt = Instant.parse("2026-09-06T12:00:00Z"),
                ),
                cursorSequence = 1,
            )
        )
        runtimeEvents.emit(
            ConversationRuntimeEvent.ExecutionCompleted(
                conversationId = conversationId,
                shouldNotifyUser = false,
                cursorSequence = 2,
            )
        )
        runtimeEvents.emit(ConversationRuntimeEvent.ReplayCompleted(conversationId, 2))
        runCurrent()

        assertEquals(1, historyLoads, "Replaying a completion must not download the entire history again")
        assertFalse(viewModel.isWaitingForResponse.value)
        assertFalse(viewModel.uiState.value.isWaitingForResponse)
    }

    @Test
    fun `incoming messages remain visible while a page waits and after replacement`() = runTest {
        val events = MutableSharedFlow<ConversationRuntimeEvent>(extraBufferCapacity = 8)
        val conversationId = Conversation.Id("conversation-1")
        val threadId = Conversation.Thread.Id("thread-1")
        fun message(position: Int) = Conversation.Message(
            id = Conversation.Message.Id("message-$position"), conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Message $position")),
            createdAt = Instant.parse("2026-09-16T00:00:00Z"),
        )
        fun page(range: IntRange, sequence: Long) = ConversationHistoryPage(
            threadId, range.map { ConversationHistoryMessage(it, message(it)) },
            older = range.first.takeIf { it > 0 }?.let { ConversationHistoryCursor(threadId, it) },
            eventSequence = sequence,
        )
        var response = page(10..19, 100)
        var gate: CompletableDeferred<Unit>? = null
        val viewModel = viewModel(backgroundScope, events) {
            val result = response
            gate?.await()
            result
        }
        runCurrent()
        events.emit(ConversationRuntimeEvent.ReplayCompleted(conversationId, 100))
        runCurrent()
        response = page(0..9, 100)
        gate = CompletableDeferred()
        viewModel.loadOlderHistory()
        runCurrent()
        events.emit(ConversationRuntimeEvent.MessageEmitted(
            conversationId, taskId = null, message = message(20), cursorSequence = 101,
            historyThreadId = threadId, historyPosition = 20,
        ))
        runCurrent()
        assertTrue(viewModel.historyLoading.value)
        assertEquals(message(20), viewModel.allMessages.value.last())
        gate.complete(Unit)
        runCurrent()
        assertEquals((0..20).map { message(it).id }, viewModel.allMessages.value.map { it.id })
        response = page(10..20, 101)
        gate = CompletableDeferred()
        viewModel.loadLatestHistory()
        runCurrent()
        events.emit(ConversationRuntimeEvent.MessageEmitted(
            conversationId, taskId = null, message = message(21), cursorSequence = 102,
            historyThreadId = threadId, historyPosition = 21,
        ))
        runCurrent()
        assertEquals(message(21), viewModel.allMessages.value.last())
        gate.complete(Unit)
        runCurrent()
        assertEquals((10..21).map { message(it).id }, viewModel.allMessages.value.map { it.id })
    }

    @Test
    fun `a tool result outside the loaded window still completes its visible call`() = runTest {
        val conversationId = Conversation.Id("conversation-1")
        val threadId = Conversation.Thread.Id("thread-1")
        val callId = Conversation.Message.ContentItem.ToolCall.Id("call")
        val call = Conversation.Message(
            id = Conversation.Message.Id("call-message"), conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(Conversation.Message.ContentItem.ToolCall(callId,
                Conversation.Message.ContentItem.ToolCall.Data("tool", JsonObject(emptyMap())))),
            createdAt = Instant.parse("2026-09-16T00:00:00Z"),
        )
        val events = MutableSharedFlow<ConversationRuntimeEvent>(extraBufferCapacity = 8)
        val viewModel = viewModel(backgroundScope, events) {
            ConversationHistoryPage(threadId, listOf(ConversationHistoryMessage(0, call)),
                newer = ConversationHistoryCursor(threadId, 0), eventSequence = 1)
        }
        backgroundScope.launch { viewModel.toolResultsMap.collect {} }
        runCurrent()
        val result = Conversation.Message.ContentItem.ToolResult(callId, "tool",
            listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("Completed outside the page")), isError = true)
        events.emit(ConversationRuntimeEvent.MessageEmitted(
            conversationId, taskId = null, message = call.copy(id = Conversation.Message.Id("result"), content = listOf(result)),
            cursorSequence = 2, historyThreadId = threadId, historyPosition = 20,
        ))
        runCurrent()
        assertEquals(listOf(call), viewModel.allMessages.value)
        assertEquals(result, viewModel.toolResultsMap.value[callId.value])
        assertEquals(ConversationHistoryCursor(threadId, 0), viewModel.newerHistory.value)
    }

    @Test
    fun `jumping to latest does not restore the previous scroll anchor`() = runTest {
        val conversationId = Conversation.Id("conversation-1")
        val threadId = Conversation.Thread.Id("thread-1")
        fun message(id: String) = Conversation.Message(
            id = Conversation.Message.Id(id), conversationId = conversationId, role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(id)), createdAt = Instant.parse("2026-09-16T00:00:00Z"),
        )
        val old = message("old")
        var response = ConversationHistoryPage(threadId, listOf(ConversationHistoryMessage(0, old)))
        val viewModel = viewModel(backgroundScope, MutableSharedFlow()) { response }
        runCurrent()
        viewModel.rememberHistoryAnchor(old.id)
        response = ConversationHistoryPage(threadId, listOf(ConversationHistoryMessage(100, message("latest"))))
        viewModel.loadLatestHistory()
        runCurrent()
        assertNull(viewModel.messageFocusRequest.value)
    }

    @Test
    fun `replay gap reloads one page while ordinary replay completion does not`() = runTest {
        val events = MutableSharedFlow<ConversationRuntimeEvent>(extraBufferCapacity = 8)
        var loads = 0
        val viewModel = viewModel(backgroundScope, events) {
            loads++
            ConversationHistoryPage(Conversation.Thread.Id("thread-1"), emptyList(), eventSequence = loads.toLong())
        }
        runCurrent()
        events.emit(ConversationRuntimeEvent.ReplayCompleted(Conversation.Id("conversation-1"), 1))
        runCurrent()
        assertEquals(1, loads)
        events.emit(ConversationRuntimeEvent.ReplayCompleted(Conversation.Id("conversation-1"), 2, historyReset = true))
        runCurrent()
        assertEquals(2, loads)
        assertFalse(viewModel.historyLoading.value)
    }

    @Test
    fun `editing a projected page still fetches the full original text`() = runTest {
        val full = Conversation.Message(
            id = Conversation.Message.Id("source"), conversationId = Conversation.Id("conversation-1"),
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Complete original text, not a preview")),
            createdAt = Instant.parse("2026-09-16T00:00:00Z"),
        )
        val preview = full.copy(content = listOf(Conversation.Message.ContentItem.UserMessage("Complete")))
        val viewModel = viewModel(backgroundScope, MutableSharedFlow(), loadMessage = { full }, latestUserMessage = { full }) {
            ConversationHistoryPage(Conversation.Thread.Id("thread-1"), listOf(ConversationHistoryMessage(0, preview, hasMoreContent = true)))
        }
        runCurrent()
        viewModel.startEditMessage(full.id)
        runCurrent()
        assertEquals(full.id, viewModel.uiState.value.editingMessageId)
        assertEquals(full.editableText(), viewModel.uiState.value.editingMessageText)
        viewModel.cancelEditMessage()
        viewModel.startEditLatestUserMessage()
        runCurrent()
        assertEquals(full.editableText(), viewModel.uiState.value.editingMessageText)
    }

    @Test
    fun `loaded full checkpoint blocks both edit entry points but selective summary does not`() = runTest {
        for (coverage in Conversation.Message.ContentItem.ContextCompactionResult.Coverage.entries) {
            val source = Conversation.Message(
                id = Conversation.Message.Id("source"), conversationId = Conversation.Id("conversation-1"),
                role = Conversation.Message.Role.USER,
                content = listOf(Conversation.Message.ContentItem.UserMessage("Original text")),
                createdAt = Instant.parse("2026-09-16T00:00:00Z"),
            )
            val summary = source.copy(id = Conversation.Message.Id("summary"), role = Conversation.Message.Role.ASSISTANT,
                content = listOf(Conversation.Message.ContentItem.ContextCompactionResult(
                    payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary("Summary"),
                    origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                    sourceMessageIds = listOf(source.id), coverage = coverage,
                )))
            var detailLoads = 0
            val viewModel = viewModel(backgroundScope, MutableSharedFlow(), loadMessage = { detailLoads++; source }, latestUserMessage = { source }) {
                ConversationHistoryPage(Conversation.Thread.Id("thread-1"), listOf(ConversationHistoryMessage(0, source), ConversationHistoryMessage(1, summary)))
            }
            runCurrent()
            val locked = coverage == Conversation.Message.ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS
            viewModel.startEditMessage(source.id)
            runCurrent()
            assertEquals(if (locked) null else source.id, viewModel.uiState.value.editingMessageId)
            assertEquals(if (locked) 0 else 1, detailLoads)
            viewModel.cancelEditMessage()
            viewModel.startEditLatestUserMessage()
            runCurrent()
            assertEquals(if (locked) null else source.id, viewModel.uiState.value.editingMessageId)
        }
    }

    private fun viewModel(
        scope: CoroutineScope,
        runtimeEvents: MutableSharedFlow<ConversationRuntimeEvent>,
        loadMessage: suspend (Conversation.Message.Id) -> Conversation.Message = { error("Unused") },
        latestUserMessage: suspend () -> Conversation.Message? = { error("Unused") },
        loadPage: suspend (ConversationHistoryPageRequest) -> ConversationHistoryPage,
    ): TabViewModel {
        val conversationId = Conversation.Id("conversation-1")
        val projectId = Project.Id("project-1")
        val settingsService = TestSettingsService()
        return TabViewModel(
            conversationId = conversationId,
            projectId = projectId,
            currentUserAuthor = Conversation.Message.Author.User(User.Id("user-1"), "User"),
            agentService = stub { methodName ->
                when {
                    methodName.startsWith("observeByProject") -> emptyFlow<Nothing>()
                    else -> unsupported(methodName)
                }
            },
            conversationRuntimeService = stub { methodName ->
                when {
                    methodName.startsWith("observeConversation") -> runtimeEvents
                    methodName.startsWith("observeActiveGeneration") -> emptyFlow<ActiveGenerationSnapshot?>()
                    else -> unsupported(methodName)
                }
            },
            conversationService = stub { methodName ->
                when {
                    methodName.startsWith("observeByProject") -> emptyFlow<Nothing>()
                    else -> unsupported(methodName)
                }
            },
            conversationHistoryService = object : ConversationHistoryService {
                override suspend fun loadPage(conversationId: Conversation.Id, request: ConversationHistoryPageRequest) = loadPage(request)
                override suspend fun loadMessage(conversationId: Conversation.Id, messageId: Conversation.Message.Id): Conversation.Message = loadMessage(messageId)
                override suspend fun selectMessageIds(conversationId: Conversation.Id, selection: ConversationMessageSelection): List<Conversation.Message.Id> = error("Unused")
                override suspend fun latestUserMessage(conversationId: Conversation.Id): Conversation.Message? = latestUserMessage()
                override suspend fun editMessage(conversationId: Conversation.Id, messageId: Conversation.Message.Id, newContent: List<Conversation.Message.ContentItem>): Conversation? = error("Unused")
                override suspend fun deleteMessages(conversationId: Conversation.Id, messageIds: List<Conversation.Message.Id>): Conversation? = error("Unused")
                override suspend fun compactMessages(conversationId: Conversation.Id, messageIds: List<Conversation.Message.Id>, strategy: SquashType): Conversation = error("Unused")
            },
            settingsService = settingsService,
            scope = scope,
            initialTabUiState = UIState.Tab(
                projectId = projectId,
                conversationId = conversationId,
                tabId = "tab-1",
            ),
            attachmentAcquisitionController = NoOpAttachmentAcquisitionController,
            artifactTransferService = stub(),
            tokenStatsService = stub { methodName ->
                when {
                    methodName.startsWith("getTokenStats") -> null
                    else -> unsupported(methodName)
                }
            },
            messageInputClientPlatform = MessageInputContext.ClientPlatform.DESKTOP,
            turnCompletionNotificationService = TurnCompletionNotificationService(
                settingsService,
                NoOpTurnCompletionNotificationSink,
            ),
        )
    }

    private class TestSettingsService : SettingsService {
        private val mutableSettings = MutableStateFlow(Settings())

        override val settingsFlow: StateFlow<Settings> = mutableSettings
        override val settings: Settings get() = mutableSettings.value
        override val userProfile get() = settings.userProfile
        override val userDeviceSettings get() = settings.userDeviceSettings
        override val mode = AppMode.PRODUCTION
        override val homeDirectory = "/tmp/gromozeka-test"

        override fun saveSettings(settings: Settings) {
            mutableSettings.value = settings
        }

        override fun saveSettings(block: Settings.() -> Settings) {
            saveSettings(settings.block())
        }

        override fun reloadSettings() = Unit
    }

    private inline fun <reified T : Any> stub(
        noinline handler: (String) -> Any? = ::unsupported,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.singleOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "${T::class.simpleName} stub"
            else -> handler(method.name)
        }
    } as T

    private fun unsupported(methodName: String): Nothing =
        error("Unexpected test service call: $methodName")
}
