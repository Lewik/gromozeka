package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.client.ArtifactTransferService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.*
import com.gromozeka.presentation.services.NoOpAttachmentAcquisitionController
import com.gromozeka.presentation.services.NoOpTurnCompletionNotificationSink
import com.gromozeka.presentation.services.TurnCompletionNotificationService
import com.gromozeka.presentation.ui.state.UIState
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TabViewModelSubmissionTest {
    @Test
    fun `plain message needs no agent or conversation lookup`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.model.updateUserInput("First message")
        fixture.submit()
        runCurrent()

        assertEquals(0, fixture.agentLoads)
        assertEquals(0, fixture.conversationLoads)
        assertEquals(listOf("First message"), fixture.sentTexts)
        assertEquals("", fixture.model.userInput)
    }

    @Test
    fun `draft is claimed before a slow mention lookup and later edits are not sent`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeAgentLoad = { gate.await() }
        fixture.model.updateUserInput("@Agent first message")
        fixture.submit()

        assertEquals("", fixture.model.userInput, "The click must clear the draft before any network response")
        assertTrue(fixture.sent.isEmpty())
        fixture.model.updateUserInput("Next draft")
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("@Agent first message"), fixture.sentTexts)
        assertEquals(fixture.agent.id, fixture.sent.single().second)
        assertEquals("Next draft", fixture.model.userInput)
        assertEquals(1, fixture.agentLoads, "Typing must not restart mention lookup")
        assertEquals(1, fixture.conversationLoads)
    }

    @Test
    fun `a repeated send while resolving a mention cannot consume a future draft`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeAgentLoad = { gate.await() }
        fixture.model.updateUserInput("@Agent first message")
        fixture.submit()
        fixture.submit()
        fixture.model.updateUserInput("Not submitted")
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("@Agent first message"), fixture.sentTexts)
        assertEquals("Not submitted", fixture.model.userInput)
        assertEquals(1, fixture.agentLoads)
    }

    @Test
    fun `queued acknowledgement leaves the next draft intact`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.model.updateUserInput("First message")
        fixture.submit()
        runCurrent()
        assertTrue(fixture.model.isWaitingForResponse.value)
        val gate = CompletableDeferred<Boolean>()
        fixture.acceptMessage = { gate.await() }
        fixture.model.updateUserInput("Queued message")
        fixture.submit()
        runCurrent()
        assertEquals("", fixture.model.userInput)
        fixture.model.updateUserInput("Next draft")
        gate.complete(true)
        runCurrent()

        assertEquals("Next draft", fixture.model.userInput)
        assertEquals(listOf("First message", "Queued message"), fixture.sentTexts)
        assertEquals("Queued message", fixture.model.pendingMessages.value.single().text)
    }

    @Test
    fun `queue rejection restores the submitted text without losing the next draft`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.model.updateUserInput("First message")
        fixture.submit()
        runCurrent()
        val gate = CompletableDeferred<Boolean>()
        fixture.acceptMessage = { gate.await() }
        fixture.model.updateUserInput("Rejected message")
        fixture.submit()
        runCurrent()
        fixture.model.updateUserInput("Next draft")
        gate.complete(false)
        runCurrent()

        assertEquals("Rejected message\n\nNext draft", fixture.model.userInput)
        assertNotNull(fixture.model.messageSubmissionError.value)
        assertTrue(fixture.model.pendingMessages.value.isEmpty())
    }

    @Test
    fun `mention lookup failure restores only the claimed draft`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeAgentLoad = { gate.await(); error("Lookup unavailable") }
        fixture.model.updateUserInput("@Agent first message")
        fixture.submit()
        fixture.model.updateUserInput("Next draft")
        gate.complete(Unit)
        runCurrent()

        assertEquals("@Agent first message\n\nNext draft", fixture.model.userInput)
        assertNotNull(fixture.model.messageSubmissionError.value)
        assertTrue(fixture.sent.isEmpty())
    }

    @Test
    fun `an agent disconnected during lookup is rejected rather than posted normally`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        fixture.conversation = fixture.conversation.copy(participants = setOf(Conversation.Participant.User(fixture.user.userId)))
        fixture.model.updateUserInput("@Agent first message")
        fixture.submit()
        runCurrent()

        assertEquals("@Agent first message", fixture.model.userInput)
        assertNotNull(fixture.model.messageSubmissionError.value)
        assertTrue(fixture.sent.isEmpty())
    }

    @Test
    fun `a later send cannot overtake a slow mention lookup`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        fixture.beforeAgentLoad = { gate.await() }
        fixture.model.updateUserInput("@Agent first message")
        fixture.submit()
        fixture.model.updateUserInput("Second message")
        fixture.submit()
        runCurrent()
        assertEquals("", fixture.model.userInput)
        assertTrue(fixture.model.isSubmittingMessage.value)
        assertTrue(fixture.sent.isEmpty())
        fixture.model.updateUserInput("Next draft")
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("@Agent first message", "Second message"), fixture.sentTexts)
        assertEquals("Next draft", fixture.model.userInput)
        assertEquals(false, fixture.model.isSubmittingMessage.value)
    }

    @Test
    fun `idle transport failure restores the draft and clears submission progress`() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        fixture.acceptMessage = { gate.await(); error("Transport unavailable") }
        fixture.model.updateUserInput("First message")
        fixture.submit()
        runCurrent()
        assertTrue(fixture.model.isSubmittingMessage.value)
        fixture.model.updateUserInput("Next draft")
        gate.complete(Unit)
        runCurrent()

        assertEquals("First message\n\nNext draft", fixture.model.userInput)
        assertNotNull(fixture.model.messageSubmissionError.value)
        assertTrue(fixture.model.allMessages.value.isEmpty())
        assertEquals(false, fixture.model.isWaitingForResponse.value)
        assertEquals(false, fixture.model.isSubmittingMessage.value)
    }

    private class Fixture(scope: CoroutineScope) {
        private val conversationId = Conversation.Id("conversation-1")
        private val projectId = Project.Id("project-1")
        val user = Conversation.Message.Author.User(User.Id("user-1"), "User")
        private val timestamp = Instant.parse("2026-11-15T00:00:00Z")
        val agent = AgentDefinition(
            id = AgentDefinition.Id("agent-1"), name = "Agent", prompts = emptyList(),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model-1")),
            type = AgentDefinition.Type.Global, createdAt = timestamp, updatedAt = timestamp,
        )
        var conversation = Conversation(
            id = conversationId, projectId = projectId,
            participants = setOf(Conversation.Participant.User(user.userId), Conversation.Participant.Agent(agent.id)),
            currentThread = Conversation.Thread.Id("thread-1"), createdAt = timestamp, updatedAt = timestamp,
        )
        var beforeAgentLoad: suspend () -> Unit = {}
        var acceptMessage: suspend () -> Boolean = { true }
        var agentLoads = 0
        var conversationLoads = 0
        val sent = mutableListOf<Pair<Conversation.Message, AgentDefinition.Id?>>()
        val sentTexts get() = sent.map { (message, _) -> message.content.filterIsInstance<Conversation.Message.ContentItem.UserMessage>().single().text }
        private val settings = TestSettingsService()
        val model = TabViewModel(
            conversationId = conversationId, projectId = projectId, currentUserAuthor = user,
            conversationTitle = { "Test conversation" },
            agentService = object : AgentDomainService by stub() {
                override fun observeByProject(projectId: Project.Id) = emptyFlow<List<AgentDefinition>>()
                override suspend fun findByProject(projectId: Project.Id): List<AgentDefinition> {
                    agentLoads++
                    beforeAgentLoad()
                    return listOf(agent)
                }
            },
            conversationService = object : ConversationDomainService by stub() {
                override fun observeByProject(projectId: Project.Id) = emptyFlow<List<Conversation>>()
                override suspend fun findById(id: Conversation.Id): Conversation {
                    conversationLoads++
                    return conversation
                }
            },
            conversationRuntimeService = object : ConversationRuntimeService by stub() {
                override fun observeConversation(conversationId: Conversation.Id, afterEventSequence: Long?) = emptyFlow<ConversationRuntimeEvent>()
                override fun observeActiveGeneration(conversationId: Conversation.Id) = emptyFlow<ActiveGenerationSnapshot?>()
                override suspend fun submitMessage(
                    conversationId: Conversation.Id,
                    message: Conversation.Message,
                    agentDefinitionId: AgentDefinition.Id?,
                    delivery: MessageDelivery,
                ): Boolean {
                    sent += message to agentDefinitionId
                    return acceptMessage()
                }
            },
            conversationHistoryService = object : ConversationHistoryService by stub() {
                override suspend fun loadPage(conversationId: Conversation.Id, request: ConversationHistoryPageRequest) =
                    ConversationHistoryPage(conversation.currentThread, emptyList())
            },
            settingsService = settings, scope = scope,
            initialTabUiState = UIState.Tab(projectId = projectId, conversationId = conversationId, tabId = "tab-1"),
            attachmentAcquisitionController = NoOpAttachmentAcquisitionController,
            artifactTransferService = stub<ArtifactTransferService>(),
            tokenStatsService = object : ConversationTokenStatsService by stub() {
                override suspend fun getTokenStats(conversationId: Conversation.Id): TokenUsageStatistics.ThreadTotals? = null
            },
            messageInputClientPlatform = MessageInputContext.ClientPlatform.DESKTOP,
            turnCompletionNotificationService = TurnCompletionNotificationService(settings, NoOpTurnCompletionNotificationSink),
        )

        fun submit() = model.submitUserInputToSession()
    }

    private class TestSettingsService : SettingsService {
        override val settingsFlow = MutableStateFlow(Settings())
        override val settings get() = settingsFlow.value
        override val userProfile get() = settings.userProfile
        override val userDeviceSettings get() = settings.userDeviceSettings
        override val mode = AppMode.PRODUCTION
        override val homeDirectory = "/tmp/gromozeka-submission-test"
        override fun saveSettings(settings: Settings) { settingsFlow.value = settings }
        override fun saveSettings(block: Settings.() -> Settings) { saveSettings(settings.block()) }
        override fun reloadSettings() = Unit
    }

    companion object {
        private inline fun <reified T : Any> stub(): T = Proxy.newProxyInstance(
            T::class.java.classLoader, arrayOf(T::class.java),
        ) { _, method, _ -> error("Unexpected test service call: ${method.name}") } as T
    }
}
