package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.TabManager
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationRuntimeService
import com.gromozeka.domain.service.ConversationTabLayoutService
import com.gromozeka.domain.service.ConversationTokenStatsService
import com.gromozeka.domain.service.DefaultAgentProvider
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.MessageSquashGenerationService
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.services.ScreenCaptureController
import com.gromozeka.presentation.services.SoundNotificationPlayer
import com.gromozeka.presentation.ui.state.UIState
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

open class AppViewModel(
    private val conversationRuntimeService: ConversationRuntimeService,
    private val conversationService: ConversationDomainService,
    private val messageSquashGenerationService: MessageSquashGenerationService,
    private val soundNotificationService: SoundNotificationPlayer,
    private val settingsService: SettingsService,
    private val scope: CoroutineScope,
    private val screenCaptureController: ScreenCaptureController,
    private val defaultAgentProvider: DefaultAgentProvider,
    private val agentService: AgentDomainService,
    private val tokenStatsService: ConversationTokenStatsService,
    private val conversationTabLayoutService: ConversationTabLayoutService,
) : TabManager {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()

    private val _tabs = MutableStateFlow<List<TabViewModel>>(emptyList())
    val tabs: StateFlow<List<TabViewModel>> = _tabs.asStateFlow()

    private val _conversations = MutableStateFlow<Map<Conversation.Id, Conversation>>(emptyMap())
    val conversations: StateFlow<Map<Conversation.Id, Conversation>> = _conversations.asStateFlow()

    private val _currentTabIndex = MutableStateFlow<Int?>(null)
    val currentTabIndex: StateFlow<Int?> = _currentTabIndex.asStateFlow()

    val currentTab: StateFlow<TabViewModel?> = combine(tabs, currentTabIndex) { tabList, index ->
        index?.let { tabList.getOrNull(it) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun createTab(
        projectId: Project.Id,
        agent: AgentDefinition?,
        conversationId: Conversation.Id?,
        initialMessage: Conversation.Message?,
        setAsCurrent: Boolean,
        initiator: ConversationInitiator,
    ): Int = mutex.withLock {
        if (conversationId != null) {
            val existingIndex = _tabs.value.indexOfFirst { it.conversationId == conversationId }
            if (existingIndex >= 0) {
                conversationTabLayoutService.open(conversationId)
                if (setAsCurrent) {
                    _currentTabIndex.value = existingIndex
                }
                initialMessage?.let { message ->
                    _tabs.value[existingIndex].sendInitialMessage(message)
                }
                return existingIndex
            }
        }

        val tabId = Tab.Id(uuid7())

        val conversation = if (conversationId != null) {
            val existing = conversationService.findById(conversationId)
                ?: error("Conversation not found: $conversationId")
            require(existing.projectId == projectId) {
                "Conversation ${conversationId.value} belongs to project ${existing.projectId.value}, not ${projectId.value}"
            }
            existing
        } else {
            val newConversationAgent = agent ?: defaultAgentProvider.getDefault()
            conversationService.create(
                projectId = projectId,
                agentDefinitionId = newConversationAgent.id,
            )
        }
        val tabAgent = agentService.findById(conversation.agentDefinitionId)
            ?: error(
                "Agent not found for conversation ${conversation.id.value}: " +
                    conversation.agentDefinitionId.value
            )
        if (agent != null) {
            require(agent.id == tabAgent.id) {
                "Conversation ${conversation.id.value} uses agent ${tabAgent.id.value}, not ${agent.id.value}"
            }
        }
        mergeConversationSnapshots(listOf(conversation))

        val parentTabId =
            initialMessage?.instructions?.filterIsInstance<Conversation.Message.Instruction.Source.Agent>()
                ?.firstOrNull()?.tabId

        val initialTabUiState = newTabUiState(
            conversation = conversation,
            agent = tabAgent,
            tabId = tabId.value,
            parentTabId = parentTabId,
            initiator = initiator,
        )
        val tabViewModel = createTabViewModel(conversation, initialTabUiState)

        conversationTabLayoutService.open(conversation.id)

        val updatedTabs = _tabs.value + tabViewModel
        _tabs.value = updatedTabs

        val newTabIndex = updatedTabs.size - 1
        log.info("Created tab at index $newTabIndex for project=${projectId.value}")

        if (setAsCurrent) {
            _currentTabIndex.value = newTabIndex
            log.info("Switched to new tab at index $newTabIndex")
        }

        if (initialMessage != null) {
            tabViewModel.sendInitialMessage(initialMessage)
        }

        return newTabIndex
    }

    suspend fun sendInterruptToCurrentSession() {
        currentTab.value?.interrupt()
    }

    suspend fun closeTab(index: Int) {
        val conversationId = mutex.withLock {
            _tabs.value.getOrNull(index)?.conversationId
        } ?: return

        conversationTabLayoutService.close(conversationId)
        mutex.withLock {
            removeLocalTab(conversationId)
        }
    }

    suspend fun selectTab(index: Int?) = mutex.withLock {
        if (index != null && index !in systemTabIndexes) {
            require(index >= 0 && index < _tabs.value.size) {
                "Tab index $index out of bounds (0..${_tabs.value.size - 1})"
            }
        }
        _currentTabIndex.value = index
        log.info("Selected tab: $index")
    }

    suspend fun selectTab(tabId: Tab.Id): TabViewModel? {
        val tab = findTabByTabId(tabId)
        if (tab != null) {
            val index = tabs.value.indexOf(tab)
            selectTab(index)
            return tab
        } else {
            log.warn("Tab not found for ID: ${tabId.value}")
            return null
        }
    }

    fun findTabByTabId(tabId: Tab.Id): TabViewModel? {
        return tabs.value.find { it.uiState.value.tabId == tabId.value }
    }

    // TabManager implementation

    private suspend fun TabViewModel.toTabInfo(): TabManager.TabInfo {
        val uiState = this.uiState.first()
        return TabManager.TabInfo(
            tabId = Tab.Id(uiState.tabId),
            conversationId = this.conversationId,
            agentId = uiState.agent.id,
            projectId = this.projectId,
            isWaitingForResponse = uiState.isWaitingForResponse,
            parentTabId = uiState.parentTabId?.let { Tab.Id(it) }
        )
    }

    override suspend fun switchToTab(tabId: Tab.Id): TabManager.TabInfo? {
        val tab = selectTab(tabId)
        return tab?.toTabInfo()
    }

    override suspend fun sendMessageToTab(
        tabId: Tab.Id,
        message: String,
        instructions: List<Conversation.Message.Instruction>,
    ) {
        val tab = findTabByTabId(tabId)
            ?: throw IllegalArgumentException("Tab not found: ${tabId.value}")
        tab.sendMessageToSession(message, instructions)
    }

    override suspend fun listTabs(): List<TabManager.TabInfo> {
        return tabs.first().map { it.toTabInfo() }
    }

    override suspend fun findTabById(tabId: Tab.Id): TabManager.TabInfo? {
        return findTabByTabId(tabId)?.toTabInfo()
    }

    suspend fun restoreTabs(uiState: UIState, layout: ConversationTabLayout) {
        _currentTabIndex.value = uiState.currentTabIndex?.takeIf { it in systemTabIndexes }
        val preferredConversationId = uiState.currentTabIndex
            ?.let(uiState.tabs::getOrNull)
            ?.conversationId
        applyConversationTabLayout(layout, uiState.tabs.associateBy(UIState.Tab::conversationId), preferredConversationId)
    }

    suspend fun applyConversationTabLayout(layout: ConversationTabLayout) {
        applyConversationTabLayout(layout, emptyMap(), null)
    }

    fun snapshotUIState(): UIState =
        UIState(
            tabs = _tabs.value.map { it.uiState.value },
            currentTabIndex = _currentTabIndex.value
        )

    private suspend fun applyConversationTabLayout(
        layout: ConversationTabLayout,
        savedTabs: Map<Conversation.Id, UIState.Tab>,
        preferredConversationId: Conversation.Id?,
    ) = mutex.withLock {
        val currentSystemTab = _currentTabIndex.value?.takeIf { it in systemTabIndexes }
        val currentConversationId = _currentTabIndex.value
            ?.let(_tabs.value::getOrNull)
            ?.conversationId
            ?: preferredConversationId
        val existingTabs = _tabs.value.associateBy(TabViewModel::conversationId)
        val synchronizedTabs = layout.conversationIds.mapNotNull { conversationId ->
            existingTabs[conversationId] ?: restoreTab(conversationId, savedTabs[conversationId])
        }

        _tabs.value = synchronizedTabs
        _currentTabIndex.value = currentSystemTab ?: currentConversationId?.let { conversationId ->
            synchronizedTabs.indexOfFirst { it.conversationId == conversationId }.takeIf { it >= 0 }
        }
        log.info("Applied shared conversation tab layout revision=${layout.revision} tabs=${synchronizedTabs.size}")
    }

    private suspend fun restoreTab(
        conversationId: Conversation.Id,
        savedTab: UIState.Tab?,
    ): TabViewModel? = runCatching {
        val conversation = conversationService.findById(conversationId)
            ?: error("Conversation not found: ${conversationId.value}")
        val agent = agentService.findById(conversation.agentDefinitionId)
            ?: error("Agent not found for conversation ${conversation.id.value}: ${conversation.agentDefinitionId.value}")
        mergeConversationSnapshots(listOf(conversation))
        val uiState = savedTab?.copy(
            projectId = conversation.projectId,
            conversationId = conversation.id,
            agent = agent,
        ) ?: newTabUiState(
            conversation = conversation,
            agent = agent,
            tabId = uuid7(),
            parentTabId = null,
            initiator = ConversationInitiator.User,
        )
        createTabViewModel(conversation, uiState)
    }.onFailure { error ->
        log.warn("Failed to restore shared tab for conversation ${conversationId.value}: ${error.message}")
    }.getOrNull()

    private fun newTabUiState(
        conversation: Conversation,
        agent: AgentDefinition,
        tabId: String,
        parentTabId: String?,
        initiator: ConversationInitiator,
    ): UIState.Tab = UIState.Tab(
        projectId = conversation.projectId,
        conversationId = conversation.id,
        activeMessageInstructionIds = settingsService.settingsFlow.value.userProfile.messageInstructionGroups
            .map { group -> group.controls[group.selectedByDefault].data.id }
            .toSet(),
        tabId = tabId,
        parentTabId = parentTabId,
        agent = agent,
        initiator = initiator,
    )

    private fun createTabViewModel(
        conversation: Conversation,
        initialTabUiState: UIState.Tab,
    ): TabViewModel = TabViewModel(
        conversationId = conversation.id,
        projectId = conversation.projectId,
        conversationRuntimeService = conversationRuntimeService,
        conversationService = conversationService,
        messageSquashGenerationService = messageSquashGenerationService,
        soundNotificationService = soundNotificationService,
        settingsService = settingsService,
        scope = scope,
        initialTabUiState = initialTabUiState,
        screenCaptureController = screenCaptureController,
        tokenStatsService = tokenStatsService,
    )

    private suspend fun TabViewModel.sendInitialMessage(message: Conversation.Message) {
        val messageContent = message.content.filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
            .firstOrNull()?.text ?: "Ready to work on this project"
        log.debug("Initial message preview: ${messageContent.take(100)}...")
        try {
            sendMessageToSession(messageContent, message.instructions)
            log.info("Initial message sent successfully")
        } catch (error: Exception) {
            log.warn(error, "Failed to send initial message: ${error.message}")
        }
    }

    private fun removeLocalTab(conversationId: Conversation.Id) {
        val tabList = _tabs.value
        val index = tabList.indexOfFirst { it.conversationId == conversationId }
        if (index < 0) return
        val currentIndex = _currentTabIndex.value
        _tabs.value = tabList.filterIndexed { tabIndex, _ -> tabIndex != index }
        _currentTabIndex.value = when {
            currentIndex == null || currentIndex in systemTabIndexes -> currentIndex
            currentIndex == index -> (index - 1).takeIf { it >= 0 }
            currentIndex > index -> currentIndex - 1
            else -> currentIndex
        }
        log.info("Closed tab for conversation ${conversationId.value}")
    }

    fun mergeConversationSnapshots(conversations: Iterable<Conversation>) {
        val updates = conversations.associateBy(Conversation::id)
        if (updates.isEmpty()) return

        _conversations.update { current -> current + updates }
    }

    suspend fun renameConversation(
        conversationId: Conversation.Id,
        displayName: String,
    ): Conversation {
        val updatedConversation = conversationService.updateDisplayName(
            conversationId = conversationId,
            displayName = displayName.trim(),
        ) ?: error("Conversation not found: ${conversationId.value}")

        mergeConversationSnapshots(listOf(updatedConversation))
        log.info("Renamed conversation ${conversationId.value} to: ${updatedConversation.displayName}")
        return updatedConversation
    }

    suspend fun rememberCurrentThread() {
        val current = currentTab.value ?: return

        try {
            conversationRuntimeService.rememberCurrentThread(current.conversationId)
            current.notifyMemoryActionItemsMayHaveChanged()
            log.info { "Remembered current thread for conversation: ${current.conversationId}" }
        } catch (e: Exception) {
            log.error(e) { "Failed to remember current thread: ${e.message}" }
        }
    }

    suspend fun consolidateCurrentMemory() {
        val current = currentTab.value ?: return

        try {
            conversationRuntimeService.consolidateCurrentMemory(current.conversationId)
            current.notifyMemoryActionItemsMayHaveChanged()
            log.info { "Consolidated memory for conversation: ${current.conversationId}" }
        } catch (e: Exception) {
            log.error(e) { "Failed to consolidate memory: ${e.message}" }
            throw e
        }
    }

    suspend fun repairCurrentMemory() {
        val current = currentTab.value ?: return

        try {
            conversationRuntimeService.repairCurrentMemory(current.conversationId)
            current.notifyMemoryActionItemsMayHaveChanged()
            log.info { "Repaired memory for conversation: ${current.conversationId}" }
        } catch (e: Exception) {
            log.error(e) { "Failed to repair memory: ${e.message}" }
            throw e
        }
    }

    suspend fun maintainMemoryEntities() {
        val current = currentTab.value ?: return

        try {
            conversationRuntimeService.maintainMemoryEntities(current.conversationId)
            current.notifyMemoryActionItemsMayHaveChanged()
            log.info { "Maintained memory entities for conversation: ${current.conversationId}" }
        } catch (e: Exception) {
            log.error(e) { "Failed to maintain memory entities: ${e.message}" }
            throw e
        }
    }

    suspend fun applyCurrentMemoryRetention() {
        val current = currentTab.value ?: return

        try {
            conversationRuntimeService.applyCurrentMemoryRetention(current.conversationId)
            current.notifyMemoryActionItemsMayHaveChanged()
            log.info { "Applied memory retention for conversation: ${current.conversationId}" }
        } catch (e: Exception) {
            log.error(e) { "Failed to apply memory retention: ${e.message}" }
            throw e
        }
    }

    suspend fun cleanup() {
        mutex.withLock {
            _tabs.value = emptyList()
            _conversations.value = emptyMap()
            _currentTabIndex.value = null
        }
    }

    private companion object {
        val systemTabIndexes = setOf(-1, -2, -3)
    }
}
