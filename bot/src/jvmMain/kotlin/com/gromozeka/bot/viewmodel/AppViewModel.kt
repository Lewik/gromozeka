package com.gromozeka.bot.viewmodel

import com.gromozeka.bot.model.Session
import com.gromozeka.bot.services.SessionManager
import com.gromozeka.bot.services.SettingsService
import com.gromozeka.shared.domain.session.ClaudeSessionUuid
import com.gromozeka.shared.domain.session.SessionUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application-level ViewModel that manages all tabs
 * Replaces SessionUiManager functionality
 */
class AppViewModel(
    private val sessionManager: SessionManager,
    private val settingsService: SettingsService,
    private val scope: CoroutineScope
) {
    private val mutex = Mutex()
    
    // Tab management
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
     * Creates a new tab with a session
     * @param projectPath Path to the project directory
     * @param resumeSessionId Optional Claude session ID to resume
     * @return Index of the created tab
     */
    suspend fun createTab(projectPath: String, resumeSessionId: String? = null): Int = mutex.withLock {
        val claudeSessionId = resumeSessionId?.let { ClaudeSessionUuid(it) }
        // Create session through SessionManager
        val session = sessionManager.createSession(projectPath, claudeSessionId)

        // Create TabViewModel for this session
        val tabViewModel = TabViewModel(
            session = session,
            settingsFlow = settingsService.settingsFlow,
            scope = scope
        )
        
        // Add to tabs list
        val updatedTabs = _tabs.value + tabViewModel
        _tabs.value = updatedTabs
        
        val newTabIndex = updatedTabs.size - 1
        println("[AppViewModel] Created tab at index $newTabIndex for project: $projectPath")
        
        return newTabIndex
    }
    
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
            _currentTabIndex.value = null
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
     * Gets tab info for persistence
     */
    fun getTabsForPersistence(): List<TabInfo> {
        return _tabs.value.map { tab ->
            TabInfo(
                projectPath = tab.projectPath,
                claudeSessionId = tab.claudeSessionId.value.value
            )
        }
    }
    
    /**
     * Restores tabs from saved state
     */
    suspend fun restoreTabs(tabInfos: List<TabInfo>, currentIndex: Int?) {
        println("[AppViewModel] Restoring ${tabInfos.size} tabs")
        
        tabInfos.forEach { info ->
            // Don't pass "default" as resumeSessionId - it's not a valid Claude Session ID
            val resumeSessionId = if (info.claudeSessionId == "default") null else info.claudeSessionId
            createTab(info.projectPath, resumeSessionId)
        }
        
        if (currentIndex != null && currentIndex < _tabs.value.size) {
            selectTab(currentIndex)
        }
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
    
    /**
     * Tab information for persistence
     */
    data class TabInfo(
        val projectPath: String,
        val claudeSessionId: String
    )
}