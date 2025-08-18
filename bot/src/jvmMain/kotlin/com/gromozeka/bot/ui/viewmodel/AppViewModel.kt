package com.gromozeka.bot.ui.viewmodel

import com.gromozeka.bot.model.Session
import com.gromozeka.bot.platform.ScreenCaptureController
import com.gromozeka.bot.services.SessionManager
import com.gromozeka.bot.services.SettingsService
import com.gromozeka.bot.ui.state.UIState
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
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
class AppViewModel(
    private val sessionManager: SessionManager,
    private val settingsService: SettingsService,
    private val scope: CoroutineScope,
    private val screenCaptureController: ScreenCaptureController,
) {
    private val mutex = Mutex()

    // Tab management (UI layer)
    private val _tabs = MutableStateFlow<List<SessionViewModel>>(emptyList())
    val tabs: StateFlow<List<SessionViewModel>> = _tabs.asStateFlow()

    private val _currentTabIndex = MutableStateFlow<Int?>(null)
    val currentTabIndex: StateFlow<Int?> = _currentTabIndex.asStateFlow()

    // Computed properties
    val currentTab: StateFlow<SessionViewModel?> = combine(tabs, currentTabIndex) { tabList, index ->
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
     * @param initialMessage Optional initial message to send after creating the session
     * @return Index of the created tab
     */
    suspend fun createTab(
        projectPath: String, 
        resumeSessionId: String? = null,
        initialMessage: String? = null
    ): Int = mutex.withLock {
        
        val claudeSessionId = resumeSessionId?.let { ClaudeSessionUuid(it) }
        // Create session through SessionManager
        val session = sessionManager.createSession(projectPath, claudeSessionId)

        // Create SessionViewModel for this tab
        val initialTabUiState = UIState.Tab(
            projectPath = projectPath,
            claudeSessionId = claudeSessionId ?: ClaudeSessionUuid.DEFAULT,
            activeMessageTags = SessionViewModel.getDefaultEnabledTags()
        )
        val sessionViewModel = SessionViewModel(
            session = session,
            settingsFlow = settingsService.settingsFlow,
            scope = scope,
            initialTabUiState = initialTabUiState,
            screenCaptureController = screenCaptureController
        )

        // Add to tabs list
        val updatedTabs = _tabs.value + sessionViewModel
        _tabs.value = updatedTabs

        val newTabIndex = updatedTabs.size - 1
        println("[AppViewModel] Created tab at index $newTabIndex for project: $projectPath")

        // Send initial message if provided
        if (initialMessage != null && initialMessage.isNotBlank()) {
            println("[AppViewModel.createTab] Initial message preview: ${initialMessage.take(100)}...")
            try {
                session.sendMessage(initialMessage)
                println("[AppViewModel.createTab] Initial message sent successfully")
            } catch (e: Exception) {
                println("[AppViewModel.createTab] Failed to send initial message: ${e.message}")
                e.printStackTrace()
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

        println("[AppViewModel] Closed tab at index $index")
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
        println("[AppViewModel] Selected tab: $index")
    }


    /**
     * Restores tabs from UIState
     */
    suspend fun restoreTabs(uiState: UIState) {
        println("[AppViewModel] Restoring ${uiState.tabs.size} tabs from UIState")

        // Build all tabs first, then assign in one atomic operation
        val restoredTabs = mutableListOf<SessionViewModel>()
        
        uiState.tabs.forEach { tabUiState ->
            try {
                val claudeSessionId = tabUiState.claudeSessionId
                val session = sessionManager.createSession(tabUiState.projectPath, claudeSessionId)

                val sessionViewModel = SessionViewModel(
                    session = session,
                    settingsFlow = settingsService.settingsFlow,
                    scope = scope,
                    initialTabUiState = tabUiState,
                    screenCaptureController = screenCaptureController
                )

                restoredTabs.add(sessionViewModel)
                println("[AppViewModel] Successfully restored tab for project: ${tabUiState.projectPath}")
            } catch (e: Exception) {
                println("[AppViewModel] Failed to restore tab for project: ${tabUiState.projectPath}, error: ${e.message}")
                // Continue with other tabs - partial restore is better than no restore
            }
        }

        // Atomic assignment - triggers tabs.onEach only ONCE
        _tabs.value = restoredTabs.toList()
        println("[AppViewModel] Restore completed: ${_tabs.value.size}/${uiState.tabs.size} tabs restored")

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
        println("[AppViewModel] Renamed tab at index $tabIndex to: ${newName ?: "default"}")
    }

    /**
     * Resets tab name to default (removes custom name)
     * @param tabIndex Index of the tab to reset
     */
    suspend fun resetTabName(tabIndex: Int) = mutex.withLock {
        val tabList = _tabs.value
        val sessionViewModel = tabList.getOrNull(tabIndex) ?: return@withLock
        
        sessionViewModel.updateCustomName(null)
        println("[AppViewModel] Reset tab name at index $tabIndex to default")
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