package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.application.service.ConversationEngineService
import com.gromozeka.application.service.DefaultAgentProvider
import com.gromozeka.application.service.MessageSquashService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.repository.TabManager
import com.gromozeka.domain.repository.TokenUsageStatisticsRepository
import com.gromozeka.domain.tool.codebase.IndexDomainToGraphRequest
import com.gromozeka.domain.tool.codebase.IndexDomainToGraphTool
import com.gromozeka.infrastructure.ai.platform.ScreenCaptureController
import com.gromozeka.presentation.services.SettingsService
import com.gromozeka.presentation.services.SoundNotificationService
import com.gromozeka.presentation.ui.state.UIState
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

open class AppViewModel(
    private val conversationEngineService: ConversationEngineService,
    private val conversationService: ConversationDomainService,
    private val messageSquashService: MessageSquashService,
    private val soundNotificationService: SoundNotificationService,
    private val agentDomainService: com.gromozeka.domain.service.AgentDomainService,
    private val settingsService: SettingsService,
    private val scope: CoroutineScope,
    private val screenCaptureController: ScreenCaptureController,
    private val defaultAgentProvider: DefaultAgentProvider,
    private val tokenUsageStatisticsRepository: TokenUsageStatisticsRepository,
    private val indexDomainToGraphTool: IndexDomainToGraphTool,
) : TabManager {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()

    private val _tabs = MutableStateFlow<List<TabViewModel>>(emptyList())
    val tabs: StateFlow<List<TabViewModel>> = _tabs.asStateFlow()

    private val _currentTabIndex = MutableStateFlow<Int?>(null)
    val currentTabIndex: StateFlow<Int?> = _currentTabIndex.asStateFlow()

    val currentTab: StateFlow<TabViewModel?> = combine(tabs, currentTabIndex) { tabList, index ->
        index?.let { tabList.getOrNull(it) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun createTab(
        projectPath: String,
        agent: AgentDefinition?,
        conversationId: Conversation.Id?,
        initialMessage: Conversation.Message?,
        setAsCurrent: Boolean,
        initiator: ConversationInitiator,
    ): Int = mutex.withLock {

        val tabId = Tab.Id(uuid7())

        val tabAgent = agent ?: defaultAgentProvider.getDefault()

        val conversation = if (conversationId != null) {
            conversationService.findById(conversationId) ?: error("Conversation not found: $conversationId")
        } else {
            conversationService.create(
                projectPath = projectPath,
                agentDefinitionId = tabAgent.id
            )
        }

        val parentTabId =
            initialMessage?.instructions?.filterIsInstance<Conversation.Message.Instruction.Source.Agent>()
                ?.firstOrNull()?.tabId

        val initialTabUiState = UIState.Tab(
            projectPath = projectPath,
            conversationId = conversation.id,
            activeMessageTags = TabViewModel.getDefaultEnabledTags(),
            tabId = tabId.value,
            parentTabId = parentTabId,
            agent = tabAgent,
            initiator = initiator
        )

        val tabViewModel = TabViewModel(
            conversationId = conversation.id,
            projectPath = projectPath,
            conversationEngineService = conversationEngineService,
            conversationService = conversationService,
            messageSquashService = messageSquashService,
            soundNotificationService = soundNotificationService,
            agentDomainService = agentDomainService,
            settingsFlow = settingsService.settingsFlow,
            scope = scope,
            initialTabUiState = initialTabUiState,
            screenCaptureController = screenCaptureController,
            tokenUsageStatisticsRepository = tokenUsageStatisticsRepository,
        )

        val updatedTabs = _tabs.value + tabViewModel
        _tabs.value = updatedTabs

        val newTabIndex = updatedTabs.size - 1
        log.info("Created tab at index $newTabIndex for project: $projectPath")

        if (setAsCurrent) {
            _currentTabIndex.value = newTabIndex
            log.info("Switched to new tab at index $newTabIndex")
        }

        if (initialMessage != null) {
            val messageContent = initialMessage.content.filterIsInstance<Conversation.Message.ContentItem.UserMessage>()
                .firstOrNull()?.text ?: "Ready to work on this project"
            log.debug("Initial message preview: ${messageContent.take(100)}...")
            try {
                tabViewModel.sendMessageToSession(messageContent, initialMessage.instructions)
                log.info("Initial message sent successfully")
            } catch (e: Exception) {
                log.warn(e, "Failed to send initial message: ${e.message}")
            }
        }

        return newTabIndex
    }

    suspend fun sendInterruptToCurrentSession() {
        currentTab.value?.interrupt()
    }

    suspend fun closeTab(index: Int) = mutex.withLock {
        val tabList = _tabs.value
        val tab = tabList.getOrNull(index) ?: return@withLock

        _tabs.value = tabList.filterIndexed { i, _ -> i != index }

        if (_currentTabIndex.value == index) {
            val newIndex = when {
                index > 1 -> {
                    index - 1
                }

                index == 1 && tabList.size > 2 -> {
                    1
                }

                else -> {
                    null
                }
            }
            _currentTabIndex.value = newIndex
        } else if (_currentTabIndex.value != null && _currentTabIndex.value!! > index) {
            _currentTabIndex.value = _currentTabIndex.value!! - 1
        }

        log.info("Closed tab at index $index")
    }

    suspend fun selectTab(index: Int?) = mutex.withLock {
        if (index != null && index != -1) {  // Allow -1 for Agents tab (system tab)
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
            projectPath = this.projectPath,
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

    suspend fun restoreTabs(uiState: UIState) {
        log.info("Restoring ${uiState.tabs.size} tabs from UIState")

        val restoredTabs = mutableListOf<TabViewModel>()

        uiState.tabs.forEach { tabUiState ->
            try {
                val conversation = conversationService.findById(tabUiState.conversationId)
                    ?: run {
                        log.warn("Conversation not found for tab, creating new conversation")
                        val defaultAgent = defaultAgentProvider.getDefault()
                        
                        conversationService.create(
                            projectPath = tabUiState.projectPath,
                            agentDefinitionId = defaultAgent.id
                        )
                    }

                val tabViewModel = TabViewModel(
                    conversationId = conversation.id,
                    projectPath = tabUiState.projectPath,
                    conversationEngineService = conversationEngineService,
                    conversationService = conversationService,
                    messageSquashService = messageSquashService,
                    soundNotificationService = soundNotificationService,
                    agentDomainService = agentDomainService,
                    settingsFlow = settingsService.settingsFlow,
                    scope = scope,
                    initialTabUiState = tabUiState,
                    screenCaptureController = screenCaptureController,
                    tokenUsageStatisticsRepository = tokenUsageStatisticsRepository,
                )

                restoredTabs.add(tabViewModel)
                log.info("Successfully restored tab for project: ${tabUiState.projectPath}")
            } catch (e: Exception) {
                log.warn("Failed to restore tab for project: ${tabUiState.projectPath}, error: ${e.message}")
            }
        }

        _tabs.value = restoredTabs.toList()
        log.info("Restore completed: ${_tabs.value.size}/${uiState.tabs.size} tabs restored")

        if (uiState.currentTabIndex != null && uiState.currentTabIndex < _tabs.value.size) {
            selectTab(uiState.currentTabIndex)
        }
    }

    suspend fun renameTab(tabIndex: Int, newName: String?) = mutex.withLock {
        val tabList = _tabs.value
        val sessionViewModel = tabList.getOrNull(tabIndex) ?: return@withLock

        sessionViewModel.updateCustomName(newName?.takeIf { it.isNotBlank() })
        log.info("Renamed tab at index $tabIndex to: ${newName ?: "default"}")
    }

    suspend fun resetTabName(tabIndex: Int) = mutex.withLock {
        val tabList = _tabs.value
        val sessionViewModel = tabList.getOrNull(tabIndex) ?: return@withLock

        sessionViewModel.updateCustomName(null)
        log.info("Reset tab name at index $tabIndex to default")
    }

    suspend fun rememberCurrentThread() {
        val current = currentTab.value ?: return

        try {
            conversationEngineService.rememberCurrentThread(current.conversationId)
            log.info { "Remembered current thread for conversation: ${current.conversationId}" }
        } catch (e: Exception) {
            log.error(e) { "Failed to remember current thread: ${e.message}" }
        }
    }

    suspend fun addToGraphCurrentThread() {
        val current = currentTab.value ?: return

        try {
            conversationEngineService.addToGraphCurrentThread(current.conversationId)
            log.info { "Added current thread to knowledge graph for conversation: ${current.conversationId}" }
        } catch (e: Exception) {
            log.error(e) { "Failed to add current thread to graph: ${e.message}" }
        }
    }

    suspend fun indexDomainToGraph() {
        val current = currentTab.value ?: return

        try {
            val result = indexDomainToGraphTool.execute(
                IndexDomainToGraphRequest(project_path = current.projectPath),
                context = null
            )
            log.info { "Indexed domain to graph: $result" }
        } catch (e: Exception) {
            log.error(e) { "Failed to index domain to graph: ${e.message}" }
            throw e
        }
    }

    suspend fun cleanup() {
        mutex.withLock {
            _tabs.value = emptyList()
            _currentTabIndex.value = null
        }
    }
}
