package com.gromozeka.bot.ui.viewmodel

import com.gromozeka.bot.model.AgentDefinition
import com.gromozeka.bot.model.Session
import com.gromozeka.bot.platform.ScreenCaptureController
import com.gromozeka.bot.services.SessionManager
import com.gromozeka.bot.services.SettingsService
import com.gromozeka.bot.ui.state.UIState
import com.gromozeka.bot.ui.state.ConversationInitiator
import com.gromozeka.shared.domain.message.ChatMessage
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import klog.KLoggers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application-level ViewModel that manages all UI tabs
 *
 * Clear separation of concepts:
 * - Tabs: UI concept (what user sees in interface)
 * - Sessions: Business concept (Claude connections)
 * - Each tab currently contains one session, but architecture allows for other tab types
 */
open class AppViewModel(
    private val sessionManager: SessionManager,
    private val settingsService: SettingsService,
    private val scope: CoroutineScope,
    private val screenCaptureController: ScreenCaptureController,
) {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()

    // Tab management (UI layer)
    private val _tabs = MutableStateFlow<List<TabViewModel>>(emptyList())
    val tabs: StateFlow<List<TabViewModel>> = _tabs.asStateFlow()

    private val _currentTabIndex = MutableStateFlow<Int?>(null)
    val currentTabIndex: StateFlow<Int?> = _currentTabIndex.asStateFlow()

    // Computed properties
    val currentTab: StateFlow<TabViewModel?> = combine(tabs, currentTabIndex) { tabList, index ->
        index?.let { tabList.getOrNull(it) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val currentSession: StateFlow<Session?> = currentTab.flatMapLatest { tab ->
        if (tab == null) flowOf(null)
        else sessionManager.activeSessions.map { sessions -> sessions[tab.sessionId] }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Creates a new tab with a Claude session
     * @param projectPath Path to the project directory
     * @param resumeSessionId Optional Claude session ID to resume
     * @param initialMessage Optional initial message (as ChatMessage) to send after creating the session
     * @return Index of the created tab
     */
    suspend fun createTab(
        projectPath: String,
        agentDefinition: AgentDefinition = AgentDefinition.DEFAULT,
        resumeSessionId: String? = null,
        initialMessage: ChatMessage? = null,
        setAsCurrent: Boolean = true,
        initiator: ConversationInitiator = ConversationInitiator.User,
    ): Int = mutex.withLock {

        val claudeSessionId = resumeSessionId?.let { ClaudeSessionUuid(it) }

        // Create tabId first
        val tabId = java.util.UUID.randomUUID().toString()

        // Create session through SessionManager with tabId
        val session = sessionManager.createSession(
            agentDefinition = agentDefinition,
            projectPath = projectPath,
            resumeSessionId = claudeSessionId,
            tabId = tabId,
            initiator = initiator
        )

        // Extract parentTabId from ChatMessage.instructions if present
        val parentTabId = initialMessage?.instructions?.filterIsInstance<ChatMessage.Instruction.Source.Agent>()
            ?.firstOrNull()?.tabId

        // Create TabViewModel for this tab
        val initialTabUiState = UIState.Tab(
            projectPath = projectPath,
            claudeSessionId = claudeSessionId ?: ClaudeSessionUuid.DEFAULT,
            activeMessageTags = TabViewModel.getDefaultEnabledTags(),
            tabId = tabId,
            parentTabId = parentTabId,
            agentDefinition = agentDefinition,
            initiator = initiator
        )
        val tabViewModel = TabViewModel(
            session = session,
            settingsFlow = settingsService.settingsFlow,
            scope = scope,
            initialTabUiState = initialTabUiState,
            screenCaptureController = screenCaptureController
        )

        // Add to tabs list
        val updatedTabs = _tabs.value + tabViewModel
        _tabs.value = updatedTabs

        val newTabIndex = updatedTabs.size - 1
        log.info("Created tab at index $newTabIndex for project: $projectPath")

        // Switch to new tab if requested
        if (setAsCurrent) {
            _currentTabIndex.value = newTabIndex
            log.info("Switched to new tab at index $newTabIndex")
        }

        // Send initial message if provided
        if (initialMessage != null) {
            val messageContent = initialMessage.content.filterIsInstance<ChatMessage.ContentItem.UserMessage>()
                .firstOrNull()?.text ?: "Ready to work on this project"
            log.debug("Initial message preview: ${messageContent.take(100)}...")
            try {
                session.sendMessage(initialMessage)
                log.info("Initial message sent successfully")
            } catch (e: Exception) {
                log.warn(e, "Failed to send initial message: ${e.message}")
            }
        }

        return newTabIndex
    }

    /**
     * Send interrupt to current session
     * @return true if interrupt was sent, false if no current session
     */
    suspend fun sendInterruptToCurrentSession() = currentSession.value?.also { it.sendInterrupt() } != null

    /**
     * Closes a tab and stops its session
     * @param index Index of the tab to close
     */
    suspend fun closeTab(index: Int) = mutex.withLock {
        val tabList = _tabs.value
        val tab = tabList.getOrNull(index) ?: return@withLock

        // Remove from tabs list
        _tabs.value = tabList.filterIndexed { i, _ -> i != index }

        // Update current tab index if needed
        if (_currentTabIndex.value == index) {
            // Closing the active tab - determine which tab to activate next
            val newIndex = when {
                index > 1 -> {
                    // Closing tab with index > 1 -> activate left neighbor (index - 1)
                    index - 1
                }

                index == 1 && tabList.size > 2 -> {
                    // Closing first session (index 1) and there are tabs to the right -> stay at index 1
                    // (the right tab will shift to this position after removal)
                    1
                }

                else -> {
                    // Closing the last remaining session -> return to session list
                    null
                }
            }
            _currentTabIndex.value = newIndex
        } else if (_currentTabIndex.value != null && _currentTabIndex.value!! > index) {
            _currentTabIndex.value = _currentTabIndex.value!! - 1
        }

        // Stop the session
        sessionManager.stopSession(tab.sessionId)

        log.info("Closed tab at index $index")
    }

    /**
     * Selects a tab by index
     * @param index Tab index to select, or null for no selection
     */
    suspend fun selectTab(index: Int?) = mutex.withLock {
        if (index != null) {
            require(index >= 0 && index < _tabs.value.size) {
                "Tab index $index out of bounds (0..${_tabs.value.size - 1})"
            }
        }
        _currentTabIndex.value = index
        log.info("Selected tab: $index")
    }

    /**
     * Selects a tab by id
     * @param tabId The tab ID to select
     * @return Selected TabViewModel if found, null otherwise
     */
    suspend fun selectTab(tabId: String): TabViewModel? {
        val tab = findTabByTabId(tabId)
        if (tab != null) {
            val index = tabs.value.indexOf(tab)
            selectTab(index)
            return tab
        } else {
            log.warn("Tab not found for ID: $tabId")
            return null
        }
    }

    /**
     * Finds a tab by its tabId
     * @param tabId The tab ID to search for
     * @return TabViewModel if found, null otherwise
     */
    fun findTabByTabId(tabId: String): TabViewModel? {
        return tabs.value.find { it.uiState.value.tabId == tabId }
    }


    /**
     * Restores tabs from UIState
     */
    suspend fun restoreTabs(uiState: UIState) {
        log.info("Restoring ${uiState.tabs.size} tabs from UIState")

        // Build all tabs first, then assign in one atomic operation
        val restoredTabs = mutableListOf<TabViewModel>()

        uiState.tabs.forEach { tabUiState ->
            try {
                val claudeSessionId = tabUiState.claudeSessionId
                val session = sessionManager.createSession(
                    agentDefinition = tabUiState.agentDefinition,
                    projectPath = tabUiState.projectPath,
                    resumeSessionId = claudeSessionId,
                    tabId = tabUiState.tabId,
                    initiator = tabUiState.initiator
                )

                val tabViewModel = TabViewModel(
                    session = session,
                    settingsFlow = settingsService.settingsFlow,
                    scope = scope,
                    initialTabUiState = tabUiState,
                    screenCaptureController = screenCaptureController
                )

                restoredTabs.add(tabViewModel)
                log.info("Successfully restored tab for project: ${tabUiState.projectPath}")
            } catch (e: Exception) {
                log.warn("Failed to restore tab for project: ${tabUiState.projectPath}, error: ${e.message}")
                // Continue with other tabs - partial restore is better than no restore
            }
        }

        // Atomic assignment - triggers tabs.onEach only ONCE
        _tabs.value = restoredTabs.toList()
        log.info("Restore completed: ${_tabs.value.size}/${uiState.tabs.size} tabs restored")

        // Set current tab index after all tabs are restored
        if (uiState.currentTabIndex != null && uiState.currentTabIndex < _tabs.value.size) {
            selectTab(uiState.currentTabIndex)
        }
    }

    /**
     * Renames a tab with custom name
     * @param tabIndex Index of the tab to rename
     * @param newName New custom name for the tab (null to reset to default)
     */
    suspend fun renameTab(tabIndex: Int, newName: String?) = mutex.withLock {
        val tabList = _tabs.value
        val sessionViewModel = tabList.getOrNull(tabIndex) ?: return@withLock

        sessionViewModel.updateCustomName(newName?.takeIf { it.isNotBlank() })
        log.info("Renamed tab at index $tabIndex to: ${newName ?: "default"}")
    }

    /**
     * Resets tab name to default (removes custom name)
     * @param tabIndex Index of the tab to reset
     */
    suspend fun resetTabName(tabIndex: Int) = mutex.withLock {
        val tabList = _tabs.value
        val sessionViewModel = tabList.getOrNull(tabIndex) ?: return@withLock

        sessionViewModel.updateCustomName(null)
        log.info("Reset tab name at index $tabIndex to default")
    }

    /**
     * Stops all sessions and clears tabs
     */
    suspend fun cleanup() {
        mutex.withLock {
            _tabs.value = emptyList()
            _currentTabIndex.value = null
        }
        sessionManager.stopAllSessions()
    }

}