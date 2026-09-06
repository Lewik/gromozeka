package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.client.ArtifactTransferService
import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.Conversation
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
        val projectId = Project.Id("project-1")
        val runtimeEvents = MutableSharedFlow<ConversationRuntimeEvent>(extraBufferCapacity = 4)
        val settingsService = TestSettingsService()
        val viewModel = TabViewModel(
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
                    methodName.startsWith("loadCurrentMessages") -> emptyList<Conversation.Message>()
                    else -> unsupported(methodName)
                }
            },
            conversationHistoryService = stub(),
            settingsService = settingsService,
            scope = backgroundScope,
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

        assertFalse(viewModel.isWaitingForResponse.value)
        assertFalse(viewModel.uiState.value.isWaitingForResponse)
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
