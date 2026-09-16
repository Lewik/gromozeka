package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.services.NoOpAttachmentAcquisitionController
import com.gromozeka.presentation.services.NoOpTurnCompletionNotificationSink
import com.gromozeka.presentation.services.TurnCompletionNotificationService
import com.gromozeka.presentation.services.translation.data.Translation
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelConversationSyncTest {
    @Test
    fun externalRenameUpdatesAnOpenTabWithoutUiObservers() = runTest {
        val fixture = Fixture(backgroundScope)
        val conversation = fixture.add("one", "project-a")
        fixture.open(conversation)
        runCurrent()

        fixture.renameExternally(conversation, "Renamed by an agent")
        runCurrent()

        assertEquals("Renamed by an agent", fixture.app.conversations.value[conversation.id]?.displayName)
    }

    @Test
    fun observesEveryOpenProjectEvenWhileASystemTabIsSelected() = runTest {
        val fixture = Fixture(backgroundScope)
        val first = fixture.add("one", "project-a")
        val second = fixture.add("two", "project-b")
        fixture.open(first)
        fixture.open(second)
        fixture.app.selectTab(-2)
        runCurrent()

        fixture.renameExternally(first, "First renamed")
        fixture.renameExternally(second, "Second renamed")
        runCurrent()

        assertEquals("First renamed", fixture.app.conversations.value[first.id]?.displayName)
        assertEquals("Second renamed", fixture.app.conversations.value[second.id]?.displayName)
    }

    @Test
    fun sharesProjectObservationUntilItsLastTabClosesAndStopsOnCleanup() = runTest {
        val fixture = Fixture(backgroundScope)
        val first = fixture.add("one", "project-a")
        val second = fixture.add("two", "project-a")
        fixture.open(first)
        runCurrent()
        fixture.open(second)
        runCurrent()
        assertEquals(1, fixture.activeObservers[first.projectId])
        assertEquals(1, fixture.startedObservers[first.projectId])

        fixture.app.closeTab(0)
        runCurrent()
        assertEquals(1, fixture.activeObservers[first.projectId])
        assertEquals(1, fixture.startedObservers[first.projectId])

        fixture.app.closeTab(0)
        runCurrent()
        assertEquals(0, fixture.activeObservers[first.projectId])

        fixture.open(first)
        runCurrent()
        fixture.app.cleanup()
        runCurrent()
        assertEquals(0, fixture.activeObservers[first.projectId])
    }

    @Test
    fun restoredTabsReceiveExternalRenames() = runTest {
        val fixture = Fixture(backgroundScope)
        val conversation = fixture.add("restored", "project-a")
        fixture.app.applyConversationTabLayout(ConversationTabLayout(conversationIds = listOf(conversation.id)))
        runCurrent()

        fixture.renameExternally(conversation, "Restored and renamed")
        runCurrent()

        assertEquals("Restored and renamed", fixture.app.conversations.value[conversation.id]?.displayName)
    }

    @Test
    fun localRenameImmediatelyAppliesTheServerResultAndTrimsInput() = runTest {
        val fixture = Fixture(backgroundScope)
        val conversation = fixture.add("one", "project-a")
        fixture.open(conversation)
        runCurrent()

        val result = fixture.app.renameConversation(conversation.id, "  New title  ")

        assertEquals("New title", result.displayName)
        assertEquals(result, fixture.app.conversations.value[conversation.id])
    }

    private class Fixture(scope: CoroutineScope) {
        private val records = mutableMapOf<String, Conversation>()
        private val projects = mutableMapOf<Project.Id, MutableStateFlow<List<Conversation>>>()
        val activeObservers = mutableMapOf<Project.Id, Int>()
        val startedObservers = mutableMapOf<Project.Id, Int>()
        private val settings = TestSettingsService()
        private val conversationService: ConversationDomainService = stub { name, args ->
            when {
                name.startsWith("findById") -> records[args.first().toString()]
                name.startsWith("observeByProject") -> {
                    val projectId = Project.Id(args.first().toString())
                    flow {
                        activeObservers[projectId] = activeObservers.getOrDefault(projectId, 0) + 1
                        startedObservers[projectId] = startedObservers.getOrDefault(projectId, 0) + 1
                        try {
                            emitAll(projects.getValue(projectId))
                        } finally {
                            activeObservers[projectId] = activeObservers.getValue(projectId) - 1
                        }
                    }
                }
                name.startsWith("updateDisplayName") -> {
                    val id = args.first().toString()
                    records.getValue(id).copy(displayName = args[1] as String).also { records[id] = it }
                }
                name.startsWith("loadCurrentMessages") -> emptyList<Conversation.Message>()
                else -> error(name)
            }
        }
        val app = AppViewModel(
            currentUserAuthor = Conversation.Message.Author.User(User.Id("user"), "User"),
            agentService = stub { name, _ ->
                when {
                    name.startsWith("observeByProject") -> emptyFlow<Nothing>()
                    name.startsWith("findByProject") -> emptyList<AgentDefinition>()
                    else -> error(name)
                }
            },
            conversationRuntimeService = stub { name, _ ->
                if (name.startsWith("observe")) emptyFlow<Nothing>() else error(name)
            },
            conversationService = conversationService,
            conversationHistoryService = stub { name, _ ->
                when {
                    name.startsWith("loadPage") -> com.gromozeka.domain.model.ConversationHistoryPage(Conversation.Thread.Id("thread-1"), emptyList())
                    else -> error("Unexpected history method: $name")
                }
            },
            settingsService = settings,
            scope = scope,
            attachmentAcquisitionController = NoOpAttachmentAcquisitionController,
            artifactTransferService = stub(),
            defaultAgentProvider = stub(),
            tokenStatsService = stub { name, _ -> if (name.startsWith("getTokenStats")) null else error(name) },
            conversationTabLayoutService = stub { name, _ ->
                if (name.startsWith("open") || name.startsWith("close")) ConversationTabLayout() else error(name)
            },
            conversationUnreadStateService = stub { name, _ ->
                if (name.startsWith("observe")) emptyFlow<Nothing>() else error(name)
            },
            messageInputClientPlatform = MessageInputContext.ClientPlatform.DESKTOP,
            turnCompletionNotificationService = TurnCompletionNotificationService(settings, NoOpTurnCompletionNotificationSink),
            currentTranslation = { Translation.builtIn.getValue("en") },
        )

        fun add(id: String, project: String): Conversation = Conversation(
            id = Conversation.Id(id),
            projectId = Project.Id(project),
            displayName = "Original $id",
            participants = setOf(Conversation.Participant.User(User.Id("user"))),
            currentThread = Conversation.Thread.Id("thread-$id"),
            createdAt = Instant.fromEpochSeconds(0),
            updatedAt = Instant.fromEpochSeconds(0),
        ).also {
            records[id] = it
            projects.getOrPut(it.projectId) { MutableStateFlow(emptyList()) }.value =
                records.values.filter { record -> record.projectId == it.projectId }
        }

        suspend fun open(conversation: Conversation) = app.createTab(
            conversation.projectId, null, conversation.id, null, true, ConversationInitiator.User,
        )

        fun renameExternally(conversation: Conversation, name: String) {
            records[conversation.id.value] = conversation.copy(displayName = name)
            projects.getValue(conversation.projectId).value =
                records.values.filter { it.projectId == conversation.projectId }
        }
    }

    private class TestSettingsService : SettingsService {
        private val state = MutableStateFlow(Settings())
        override val settingsFlow: StateFlow<Settings> = state
        override val settings get() = state.value
        override val userProfile get() = settings.userProfile
        override val userDeviceSettings get() = settings.userDeviceSettings
        override val mode = AppMode.PRODUCTION
        override val homeDirectory = "/tmp/gromozeka-conversation-sync-test"
        override fun saveSettings(settings: Settings) { state.value = settings }
        override fun saveSettings(block: Settings.() -> Settings) { saveSettings(settings.block()) }
        override fun reloadSettings() = Unit
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        inline fun <reified T : Any> stub(
            noinline handler: (String, Array<out Any?>) -> Any? = { name, _ -> error(name) },
        ): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.singleOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "${T::class.simpleName} stub"
                else -> handler(method.name, args.orEmpty())
            }
        } as T
    }
}
