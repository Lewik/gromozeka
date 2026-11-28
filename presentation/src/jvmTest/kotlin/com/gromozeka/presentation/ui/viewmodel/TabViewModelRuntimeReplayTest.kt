package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.client.ArtifactTransferService
import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.AgentDefinition
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
import com.gromozeka.domain.model.MessageDelivery
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationHistoryService
import com.gromozeka.domain.service.ConversationRuntimeEvent
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TabViewModelRuntimeReplayTest {
    @Test
    fun `queued runtime message cannot be destructively edited into the user composer`() = runTest {
        val conversationId = Conversation.Id("conversation-1")
        val runtimeEvents = MutableSharedFlow<ConversationRuntimeEvent>(extraBufferCapacity = 2)
        val runtimeMessage = Conversation.Message(
            id = Conversation.Message.Id("runtime-message"),
            conversationId = conversationId,
            role = Conversation.Message.Role.RUNTIME,
            author = Conversation.Message.Author.Runtime(),
            content = listOf(Conversation.Message.ContentItem.RuntimeJson(
                kotlinx.serialization.json.JsonObject(mapOf(
                    "kind" to kotlinx.serialization.json.JsonPrimitive("panel_state"),
                )),
            )),
            createdAt = Instant.fromEpochMilliseconds(1),
        )
        val pending = ConversationRuntimeTask(
            id = ConversationRuntimeTask.Id("runtime-task"),
            conversationId = conversationId,
            payload = ConversationRuntimeTask.Payload.PostMessage(runtimeMessage),
            placement = com.gromozeka.domain.service.QueuedMessagePlacement.END_OF_TURN,
            idempotencyKey = "runtime-task",
            requirements = com.gromozeka.domain.service.ConversationRuntimeTaskRequirements(
                setOf(com.gromozeka.domain.service.ConversationRuntimeCapability.CONVERSATION_TURN),
                com.gromozeka.domain.service.ConversationRuntimeTaskTarget.Server,
            ),
            createdAt = Instant.fromEpochMilliseconds(1),
        )
        val model = viewModel(
            backgroundScope,
            runtimeEvents,
            messageDeliveryMode = MutableStateFlow(MessageDelivery.QUEUE),
        ) {
            ConversationHistoryPage(Conversation.Thread.Id("thread-1"), emptyList())
        }
        runCurrent()
        runtimeEvents.emit(ConversationRuntimeEvent.SnapshotUpdated(
            conversationId,
            ConversationRuntimeSnapshot(1, conversationId, null, pendingTasks = listOf(pending)),
        ))
        runCurrent()

        assertEquals(listOf(runtimeMessage.id.value), model.pendingMessages.value.map { it.id })
        assertFalse(model.pendingMessages.value.single().editable)
        model.editPendingMessage(runtimeMessage.id.value)
        runCurrent()

        assertEquals(listOf(runtimeMessage.id.value), model.pendingMessages.value.map { it.id })
        assertEquals("", model.uiState.value.userInput)
    }

    @Test
    fun `queued messages outside the composer contract cannot be destructively edited`() = runTest {
        val conversationId = Conversation.Id("conversation-1")
        val runtimeEvents = MutableSharedFlow<ConversationRuntimeEvent>(extraBufferCapacity = 2)
        val author = Conversation.Message.Author.User(User.Id("user-1"), "User")
        val systemMessage = Conversation.Message(
            id = Conversation.Message.Id("system-message"),
            conversationId = conversationId,
            role = Conversation.Message.Role.SYSTEM,
            author = author,
            content = listOf(Conversation.Message.ContentItem.System(
                Conversation.Message.ContentItem.System.SystemLevel.INFO,
                "Harness notification",
            )),
            createdAt = Instant.fromEpochMilliseconds(1),
        )
        val unsupportedUserMessage = Conversation.Message(
            id = Conversation.Message.Id("unsupported-user-message"),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            author = author,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Agent handoff")),
            instructions = listOf(Conversation.Message.Instruction.ResponseExpected("source-tab")),
            createdAt = Instant.fromEpochMilliseconds(1),
        )
        val messages = listOf(systemMessage, unsupportedUserMessage)
        val model = viewModel(
            backgroundScope,
            runtimeEvents,
            messageDeliveryMode = MutableStateFlow(MessageDelivery.QUEUE),
        ) {
            ConversationHistoryPage(Conversation.Thread.Id("thread-1"), emptyList())
        }
        runCurrent()
        runtimeEvents.emit(ConversationRuntimeEvent.SnapshotUpdated(
            conversationId,
            ConversationRuntimeSnapshot(
                revision = 1,
                conversationId = conversationId,
                state = null,
                pendingTasks = messages.map(::pendingTask),
            ),
        ))
        runCurrent()

        assertTrue(model.pendingMessages.value.none { it.editable })
        messages.forEach { model.editPendingMessage(it.id.value) }
        runCurrent()

        assertEquals(messages.map { it.id.value }, model.pendingMessages.value.map { it.id })
        assertEquals("", model.uiState.value.userInput)
    }

    @Test
    fun `queued composer remains editable after the current user is renamed`() = runTest {
        val conversationId = Conversation.Id("conversation-1")
        val runtimeEvents = MutableSharedFlow<ConversationRuntimeEvent>(extraBufferCapacity = 1)
        val readonly = MessageInstructionGroup.defaults().single()
            .controls.single { it.data.id == "mode_readonly" }.data
        val userMessage = Conversation.Message(
            id = Conversation.Message.Id("renamed-user-message"),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            author = Conversation.Message.Author.User(
                userId = User.Id("user-1"),
                displayName = "Renamed User",
                identityKey = "updated-identity",
            ),
            content = listOf(Conversation.Message.ContentItem.UserMessage("Still editable")),
            instructions = listOf(readonly),
            createdAt = Instant.fromEpochMilliseconds(1),
        )
        val model = viewModel(
            backgroundScope,
            runtimeEvents,
            messageDeliveryMode = MutableStateFlow(MessageDelivery.QUEUE),
        ) {
            ConversationHistoryPage(Conversation.Thread.Id("thread-1"), emptyList())
        }
        runCurrent()

        runtimeEvents.emit(ConversationRuntimeEvent.SnapshotUpdated(
            conversationId,
            ConversationRuntimeSnapshot(1, conversationId, null, pendingTasks = listOf(pendingTask(userMessage))),
        ))
        runCurrent()

        assertTrue(model.pendingMessages.value.single().editable)
    }

    @Test
    fun `queued composer editability follows delivery without a new runtime snapshot`() = runTest {
        val conversationId = Conversation.Id("conversation-1")
        val runtimeEvents = MutableSharedFlow<ConversationRuntimeEvent>(extraBufferCapacity = 2)
        val inputContext = MessageInputContext(
            modality = MessageInputContext.Modality.TEXT,
            source = MessageInputContext.Source.CHAT_INPUT,
            clientPlatform = MessageInputContext.ClientPlatform.DESKTOP,
        )
        val readonly = com.gromozeka.domain.model.MessageInstructionGroup.defaults().single()
            .controls.single { it.data.id == "mode_readonly" }.data
        val userMessage = Conversation.Message(
            id = Conversation.Message.Id("ordinary-user-message"),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            author = Conversation.Message.Author.User(User.Id("user-1"), "User"),
            content = listOf(Conversation.Message.ContentItem.UserMessage("Editable message")),
            instructions = listOf(
                Conversation.Message.Instruction.MessageInputRuntimeContext(inputContext),
                readonly,
            ),
            createdAt = Instant.fromEpochMilliseconds(1),
        )
        val deliveryMode = MutableStateFlow(MessageDelivery.STEER)
        val model = viewModel(
            backgroundScope,
            runtimeEvents,
            messageDeliveryMode = deliveryMode,
        ) {
            ConversationHistoryPage(Conversation.Thread.Id("thread-1"), emptyList())
        }
        runCurrent()
        runtimeEvents.emit(ConversationRuntimeEvent.SnapshotUpdated(
            conversationId,
            ConversationRuntimeSnapshot(1, conversationId, null, pendingTasks = listOf(pendingTask(userMessage))),
        ))
        runCurrent()

        assertFalse(model.pendingMessages.value.single().editable)
        deliveryMode.value = MessageDelivery.QUEUE
        runCurrent()

        assertTrue(model.pendingMessages.value.single().editable)
        model.editPendingMessage(userMessage.id.value)
        runCurrent()

        assertTrue(model.pendingMessages.value.isEmpty())
        assertEquals("Editable message", model.uiState.value.userInput)
        assertEquals(inputContext, model.uiState.value.composerMessageInputContext)
        assertEquals(setOf("mode_readonly"), model.uiState.value.activeMessageInstructionIds)
    }

    @Test
    fun `queued composer editability follows agent routing without a new runtime snapshot`() = runTest {
        val conversationId = Conversation.Id("conversation-1")
        val projectId = Project.Id("project-1")
        val threadId = Conversation.Thread.Id("thread-1")
        val now = Instant.fromEpochMilliseconds(1)
        val user = Conversation.Message.Author.User(User.Id("user-1"), "User")
        val agent = AgentDefinition(
            id = AgentDefinition.Id("agent-1"),
            name = "Agent",
            prompts = emptyList(),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model-1")),
            type = AgentDefinition.Type.Global,
            createdAt = now,
            updatedAt = now,
        )
        val agents = MutableStateFlow(listOf(agent))
        val conversations = MutableStateFlow(listOf(
            Conversation(
                id = conversationId,
                projectId = projectId,
                participants = setOf(
                    Conversation.Participant.User(user.userId),
                    Conversation.Participant.Agent(agent.id),
                ),
                currentThread = threadId,
                createdAt = now,
                updatedAt = now,
            ),
        ))
        val message = Conversation.Message(
            id = Conversation.Message.Id("agent-message"),
            conversationId = conversationId,
            role = Conversation.Message.Role.USER,
            author = user,
            content = listOf(Conversation.Message.ContentItem.UserMessage("@Agent Editable message")),
            instructions = listOf(
                com.gromozeka.domain.model.MessageInstructionGroup.defaults().single()
                    .controls.single { it.data.id == "mode_readonly" }.data,
            ),
            createdAt = now,
        )
        val task = pendingTask(message).copy(
            payload = ConversationRuntimeTask.Payload.AgentInvocation(message, agent.id),
            requirements = com.gromozeka.domain.service.ConversationRuntimeTaskRequirements(
                com.gromozeka.domain.service.ConversationRuntimeCapability.entries.toSet(),
                com.gromozeka.domain.service.ConversationRuntimeTaskTarget.Server,
            ),
        )
        val runtimeEvents = MutableSharedFlow<ConversationRuntimeEvent>(extraBufferCapacity = 2)
        val model = viewModel(
            backgroundScope,
            runtimeEvents,
            messageDeliveryMode = MutableStateFlow(MessageDelivery.QUEUE),
            agentDefinitions = agents,
            conversations = conversations,
        ) {
            ConversationHistoryPage(threadId, emptyList())
        }
        runCurrent()
        runtimeEvents.emit(ConversationRuntimeEvent.SnapshotUpdated(
            conversationId,
            ConversationRuntimeSnapshot(1, conversationId, null, pendingTasks = listOf(task)),
        ))
        runCurrent()

        assertTrue(model.pendingMessages.value.single().editable)
        agents.value = listOf(agent.copy(name = "Renamed"))
        runCurrent()
        assertFalse(model.pendingMessages.value.single().editable)
        model.editPendingMessage(message.id.value)
        runCurrent()
        assertEquals(listOf(message.id.value), model.pendingMessages.value.map { it.id })
        assertEquals("", model.uiState.value.userInput)

        agents.value = listOf(agent)
        runCurrent()
        assertTrue(model.pendingMessages.value.single().editable)
    }

    @Test
    fun `elapsed origin ignores scrolling synthetic messages and other users but follows live own messages`() = runTest {
        val events = MutableSharedFlow<ConversationRuntimeEvent>(extraBufferCapacity = 8)
        val id = Conversation.Id("conversation-1")
        val thread = Conversation.Thread.Id("thread-1")
        val initial = Instant.fromEpochSeconds(100)
        fun message(position: Int, owner: String = "user-1", synthetic: Boolean = false) = Conversation.Message(
            Conversation.Message.Id("message-$position"), id, role = Conversation.Message.Role.USER,
            author = Conversation.Message.Author.User(User.Id(owner), owner),
            content = listOf(Conversation.Message.ContentItem.UserMessage("Message $position")),
            providerMetadata = if (synthetic) kotlinx.serialization.json.buildJsonObject { put("synthetic", kotlinx.serialization.json.JsonPrimitive(true)) } else JsonObject(emptyMap()),
            createdAt = Instant.fromEpochSeconds(100 + position.toLong()))
        var response = ConversationHistoryPage(thread, listOf(ConversationHistoryMessage(0, message(0))),
            newer = ConversationHistoryCursor(thread, 0), eventSequence = 1,
            lastUserActivity = com.gromozeka.domain.model.ConversationUserActivity(10, initial))
        var gate: CompletableDeferred<Unit>? = null
        val vm = viewModel(backgroundScope, events) {
            val result = response
            gate?.await()
            result
        }
        runCurrent()
        assertEquals(initial, vm.lastUserMessageAt.value)
        events.emit(ConversationRuntimeEvent.ReplayCompleted(id, 1)); runCurrent()
        gate = CompletableDeferred()
        vm.loadLatestHistory(); runCurrent()
        events.emit(ConversationRuntimeEvent.MessageEmitted(id, null, message(20), cursorSequence = 2,
            historyThreadId = thread, historyPosition = 20)); runCurrent()
        assertEquals(message(20).createdAt, vm.lastUserMessageAt.value)
        assertEquals(listOf(message(0)), vm.allMessages.value, "Nonvisible own message still updates the elapsed origin")
        gate.complete(Unit); runCurrent()
        assertEquals(message(20).createdAt, vm.lastUserMessageAt.value, "Old page cannot overwrite a newer live event")
        gate = null
        for ((position, msg) in listOf(21 to message(21, synthetic = true), 22 to message(22, owner = "other"))) {
            events.emit(ConversationRuntimeEvent.MessageEmitted(id, null, msg, cursorSequence = position.toLong(),
                historyThreadId = thread, historyPosition = position)); runCurrent()
            assertEquals(message(20).createdAt, vm.lastUserMessageAt.value)
        }
        events.emit(ConversationRuntimeEvent.SnapshotUpdated(id, ConversationRuntimeSnapshot(1, id, null, pendingTasks = emptyList())))
        runCurrent()
        assertEquals(message(20).createdAt, vm.lastUserMessageAt.value)
        response = response.copy(threadId = Conversation.Thread.Id("new-branch"), messages = emptyList(), newer = null, lastUserActivity = null)
        vm.loadLatestHistory(); runCurrent()
        assertNull(vm.lastUserMessageAt.value, "A different branch must not retain the old branch clock")
    }

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
        viewModel.loadOlderHistory(message(10).id)
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

    @Test
    fun `stale displayed boundaries cannot load an extra page in either direction`() = runTest {
        for (older in listOf(true, false)) {
            val thread = Conversation.Thread.Id("thread-1")
            fun message(position: Int) = Conversation.Message(
                Conversation.Message.Id("message-$position"), Conversation.Id("conversation-1"),
                role = Conversation.Message.Role.USER,
                content = listOf(Conversation.Message.ContentItem.UserMessage("Message $position")),
                createdAt = Instant.parse("2026-09-16T00:00:00Z"),
            )
            fun page(range: IntRange) = ConversationHistoryPage(
                thread, range.map { ConversationHistoryMessage(it, message(it)) },
                older = range.first.takeIf { it > 0 }?.let { ConversationHistoryCursor(thread, it) },
                newer = ConversationHistoryCursor(thread, range.last),
            )
            val requests = mutableListOf<ConversationHistoryPageRequest>()
            var response = page(100..149)
            val model = viewModel(backgroundScope, MutableSharedFlow()) { request -> requests += request; response }
            backgroundScope.launch { model.historyContent.collect {} }
            runCurrent()
            val displayed = model.historyContent.value
            fun load(content: ConversationHistoryContent) {
                if (older) model.loadOlderHistory(requireNotNull(content.firstLoadedMessageId))
                else model.loadNewerHistory(requireNotNull(content.lastLoadedMessageId))
            }
            response = page(if (older) 50..99 else 150..199)
            // Multiple callbacks may already be queued before Compose sees loading=true.
            load(displayed)
            load(displayed)
            runCurrent()
            assertEquals(100, model.allMessages.value.size)
            assertFalse(model.historyLoading.value)
            assertEquals(2, requests.size)

            response = page(if (older) 0..49 else 200..249)
            // The request/cursor is complete, but the renderer still has its previous snapshot.
            repeat(3) { load(displayed) }
            runCurrent()
            assertEquals(2, requests.size, "An old viewport must not advance the new cursor")
            assertEquals(100, model.allMessages.value.size)

            // Once the updated window is displayed, its next boundary remains loadable.
            load(model.historyContent.value)
            runCurrent()
            assertEquals(3, requests.size)
            assertEquals(150, model.allMessages.value.size)
            assertEquals(if (older) 50 else 199, (requests.last().before ?: requests.last().after)!!.position)
        }
    }

    @Test
    fun `overlapping history pages are deduplicated by message id`() = runTest {
        val thread = Conversation.Thread.Id("thread-1")
        fun message(position: Int) = Conversation.Message(
            Conversation.Message.Id("message-$position"), Conversation.Id("conversation-1"),
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Message $position")),
            createdAt = Instant.parse("2026-09-16T00:00:00Z"),
        )
        fun page(range: IntRange) = ConversationHistoryPage(
            thread,
            range.map { ConversationHistoryMessage(it, message(it)) },
            older = range.first.takeIf { it > 0 }?.let { ConversationHistoryCursor(thread, it) },
            newer = range.last.takeIf { it < 100 }?.let { ConversationHistoryCursor(thread, it) },
        )
        var response = page(50..100)
        val model = viewModel(backgroundScope, MutableSharedFlow()) { response }
        backgroundScope.launch { model.historyContent.collect {} }
        runCurrent()

        response = page(0..50)
        model.loadOlderHistory(requireNotNull(model.historyContent.value.firstLoadedMessageId))
        runCurrent()

        assertEquals((0..100).map { message(it).id }, model.allMessages.value.map { it.id })
        assertEquals(101, model.allMessages.value.map { it.id }.distinct().size)
    }

    @Test
    fun `visible history carries raw boundaries including filtered tool results`() = runTest {
        val thread = Conversation.Thread.Id("thread-1")
        fun message(position: Int, hidden: Boolean) = Conversation.Message(
            Conversation.Message.Id("message-$position"), Conversation.Id("conversation-1"),
            role = Conversation.Message.Role.USER,
            content = if (hidden) listOf(Conversation.Message.ContentItem.ToolResult(
                Conversation.Message.ContentItem.ToolCall.Id("call-$position"), "tool",
                listOf(Conversation.Message.ContentItem.ToolResult.Data.Text("Result")), isError = false,
            )) else listOf(Conversation.Message.ContentItem.UserMessage("Visible")),
            createdAt = Instant.parse("2026-09-16T00:00:00Z"),
        )
        var response = ConversationHistoryPage(thread,
            (100..102).map { ConversationHistoryMessage(it, message(it, it != 101)) },
            older = ConversationHistoryCursor(thread, 100), newer = ConversationHistoryCursor(thread, 102))
        val requests = mutableListOf<ConversationHistoryPageRequest>()
        val model = viewModel(backgroundScope, MutableSharedFlow()) { request -> requests += request; response }
        backgroundScope.launch { model.historyContent.collect {} }
        runCurrent()
        val displayed = model.historyContent.value
        assertEquals(listOf(message(101, false).id), displayed.messages.map { it.id })
        assertEquals(message(100, true).id, displayed.firstLoadedMessageId)
        assertEquals(message(102, true).id, displayed.lastLoadedMessageId)

        response = ConversationHistoryPage(thread,
            (98..99).map { ConversationHistoryMessage(it, message(it, true)) },
            older = ConversationHistoryCursor(thread, 98))
        model.loadOlderHistory(requireNotNull(displayed.firstLoadedMessageId))
        runCurrent()
        assertEquals(2, requests.size)
        assertEquals(100, requests.last().before!!.position)
        assertEquals(displayed.messages, model.historyContent.value.messages)
        assertEquals(message(98, true).id, model.historyContent.value.firstLoadedMessageId)
        model.loadOlderHistory(requireNotNull(displayed.firstLoadedMessageId))
        runCurrent()
        assertEquals(2, requests.size, "Identical visible content must not keep an obsolete raw boundary")
    }

    @Test
    fun `runtime observation shells remain visible timeline entries`() = runTest {
        val thread = Conversation.Thread.Id("thread-1")
        fun visible(id: String) = Conversation.Message(
            Conversation.Message.Id(id), Conversation.Id("conversation-1"),
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage(id)),
            createdAt = Instant.parse("2026-09-16T00:00:00Z"),
        )
        fun observation(id: String) = visible(id).copy(
            role = Conversation.Message.Role.RUNTIME,
            author = Conversation.Message.Author.Runtime(),
            content = listOf(Conversation.Message.ContentItem.RuntimeJson(kotlinx.serialization.json.JsonNull)),
            instructions = emptyList(),
        )
        val first = visible("visible-first")
        val middle = observation("runtime-middle")
        val last = visible("visible-last")
        val rawTail = observation("runtime-tail")
        val response = ConversationHistoryPage(
            thread,
            listOf(first, middle, last, rawTail).mapIndexed { position, message ->
                ConversationHistoryMessage(position + 100, message)
            },
            older = ConversationHistoryCursor(thread, 100),
            newer = ConversationHistoryCursor(thread, 103),
        )
        val model = viewModel(backgroundScope, MutableSharedFlow()) { response }
        backgroundScope.launch { model.historyContent.collect {} }
        runCurrent()

        val displayed = model.historyContent.value
        assertEquals(listOf(first.id, middle.id, last.id, rawTail.id), displayed.messages.map { it.id })
        assertEquals(first.id, displayed.firstLoadedMessageId)
        assertEquals(rawTail.id, displayed.lastLoadedMessageId)

        model.toggleMessageSelection(first.id)
        model.toggleMessageSelectionRange(last.id, isShiftPressed = true)
        assertEquals(setOf(first.id, middle.id, last.id), model.uiState.value.selectedMessageIds)
    }

    @Test
    fun `live runtime observation advances history before the following visible message`() = runTest {
        val conversationId = Conversation.Id("conversation-1")
        val thread = Conversation.Thread.Id("thread-1")
        fun visible(id: String, role: Conversation.Message.Role = Conversation.Message.Role.USER) = Conversation.Message(
            Conversation.Message.Id(id), conversationId,
            role = role,
            content = if (role == Conversation.Message.Role.USER) {
                listOf(Conversation.Message.ContentItem.UserMessage(id))
            } else {
                listOf(Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText(id)))
            },
            createdAt = Instant.parse("2026-09-16T00:00:00Z"),
        )
        val initial = visible("initial")
        val observationShell = visible("runtime-observation").copy(
            role = Conversation.Message.Role.RUNTIME,
            author = Conversation.Message.Author.Runtime(),
            content = listOf(Conversation.Message.ContentItem.RuntimeJson(kotlinx.serialization.json.JsonNull)),
            instructions = emptyList(),
        )
        val assistant = visible("assistant", Conversation.Message.Role.ASSISTANT)
        val events = MutableSharedFlow<ConversationRuntimeEvent>(extraBufferCapacity = 4)
        val model = viewModel(backgroundScope, events) {
            ConversationHistoryPage(
                threadId = thread,
                messages = listOf(ConversationHistoryMessage(10, initial)),
                eventSequence = 1,
            )
        }
        backgroundScope.launch { model.historyContent.collect {} }
        runCurrent()
        events.emit(ConversationRuntimeEvent.ReplayCompleted(conversationId, 1))
        runCurrent()

        events.emit(ConversationRuntimeEvent.MessageEmitted(
            conversationId = conversationId,
            taskId = null,
            message = observationShell,
            cursorSequence = 2,
            historyThreadId = thread,
            historyPosition = 11,
        ))
        runCurrent()
        assertEquals(1, model.historyActivityRevision.value)
        events.emit(ConversationRuntimeEvent.MessageEmitted(
            conversationId = conversationId,
            taskId = null,
            message = assistant,
            cursorSequence = 3,
            historyThreadId = thread,
            historyPosition = 12,
        ))
        runCurrent()

        assertEquals(2, model.historyActivityRevision.value)
        assertEquals(listOf(initial.id, observationShell.id, assistant.id), model.allMessages.value.map { it.id })
        assertEquals(listOf(initial.id, observationShell.id, assistant.id), model.historyContent.value.messages.map { it.id })
        assertNull(model.newerHistory.value)
    }

    @Test
    fun `optimistic tail cannot hide a changed newer page boundary`() = runTest {
        val thread = Conversation.Thread.Id("thread-1")
        fun message(position: Int) = Conversation.Message(
            Conversation.Message.Id("message-$position"), Conversation.Id("conversation-1"),
            role = Conversation.Message.Role.USER,
            content = listOf(Conversation.Message.ContentItem.UserMessage("Message $position")),
            createdAt = Instant.parse("2026-09-16T00:00:00Z"),
        )
        fun page(range: IntRange) = ConversationHistoryPage(thread,
            range.map { ConversationHistoryMessage(it, message(it)) },
            newer = ConversationHistoryCursor(thread, range.last))
        val events = MutableSharedFlow<ConversationRuntimeEvent>(extraBufferCapacity = 4)
        val requests = mutableListOf<ConversationHistoryPageRequest>()
        var response = page(100..149)
        val model = viewModel(backgroundScope, events) { request -> requests += request; response }
        backgroundScope.launch { model.historyContent.collect {} }
        runCurrent()
        events.emit(ConversationRuntimeEvent.MessageEmitted(
            Conversation.Id("conversation-1"), taskId = null, message = message(999), cursorSequence = 1))
        runCurrent()
        val displayed = model.historyContent.value
        assertEquals(message(999).id, displayed.messages.last().id)
        assertEquals(message(149).id, displayed.lastLoadedMessageId)
        response = page(150..199)
        model.loadNewerHistory(requireNotNull(displayed.lastLoadedMessageId))
        runCurrent()
        assertEquals(message(999).id, model.historyContent.value.messages.last().id)
        assertEquals(message(199).id, model.historyContent.value.lastLoadedMessageId)
        model.loadNewerHistory(requireNotNull(displayed.lastLoadedMessageId))
        runCurrent()
        assertEquals(2, requests.size)
        assertEquals(101, model.allMessages.value.size)
    }

    private fun pendingTask(message: Conversation.Message): ConversationRuntimeTask = ConversationRuntimeTask(
        id = ConversationRuntimeTask.Id("task-${message.id.value}"),
        conversationId = message.conversationId,
        payload = ConversationRuntimeTask.Payload.PostMessage(message),
        placement = com.gromozeka.domain.service.QueuedMessagePlacement.END_OF_TURN,
        idempotencyKey = "task-${message.id.value}",
        requirements = com.gromozeka.domain.service.ConversationRuntimeTaskRequirements(
            setOf(com.gromozeka.domain.service.ConversationRuntimeCapability.CONVERSATION_TURN),
            com.gromozeka.domain.service.ConversationRuntimeTaskTarget.Server,
        ),
        createdAt = message.createdAt,
    )

    private fun viewModel(
        scope: CoroutineScope,
        runtimeEvents: MutableSharedFlow<ConversationRuntimeEvent>,
        loadMessage: suspend (Conversation.Message.Id) -> Conversation.Message = { error("Unused") },
        latestUserMessage: suspend () -> Conversation.Message? = { error("Unused") },
        messageDeliveryMode: StateFlow<MessageDelivery> = MutableStateFlow(MessageDelivery.STEER),
        agentDefinitions: Flow<List<AgentDefinition>> = emptyFlow(),
        conversations: Flow<List<Conversation>> = emptyFlow(),
        loadPage: suspend (ConversationHistoryPageRequest) -> ConversationHistoryPage,
    ): TabViewModel {
        val conversationId = Conversation.Id("conversation-1")
        val projectId = Project.Id("project-1")
        val settingsService = TestSettingsService()
        return TabViewModel(
            conversationId = conversationId,
            projectId = projectId,
            conversationTitle = { "Test conversation" },
            currentUserAuthor = Conversation.Message.Author.User(User.Id("user-1"), "User"),
            agentService = stub { methodName ->
                when {
                    methodName.startsWith("observeByProject") -> agentDefinitions
                    else -> unsupported(methodName)
                }
            },
            conversationRuntimeService = stub { methodName ->
                when {
                    methodName.startsWith("observeConversation") -> runtimeEvents
                    methodName.startsWith("observeActiveGeneration") -> emptyFlow<ActiveGenerationSnapshot?>()
                    methodName.startsWith("cancelQueuedMessage") -> true
                    else -> unsupported(methodName)
                }
            },
            conversationService = stub { methodName ->
                when {
                    methodName.startsWith("observeByProject") -> conversations
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
                override suspend fun compactMessages(conversationId: Conversation.Id, messageIds: List<Conversation.Message.Id>,
                    fullSelection: Boolean, strategy: SquashType): Conversation = error("Unused")
            },
            settingsService = settingsService,
            parentScope = scope,
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
            messageDeliveryMode = messageDeliveryMode,
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
