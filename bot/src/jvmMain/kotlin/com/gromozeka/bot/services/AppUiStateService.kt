package com.gromozeka.bot.services

import com.gromozeka.bot.viewmodel.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.io.File

/**
 * Service responsible for saving and restoring UI state
 * Replaces ApplicationPersistentStateService
 */
@Service
class AppUiStateService(
    private val settingsService: SettingsService,
    @Qualifier("coroutineScope") private val scope: CoroutineScope,
) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val stateFile: File by lazy {
        File(settingsService.gromozekaHome, "ui-state.json")
    }

    private lateinit var appViewModel: AppViewModel
    private var autoSaveEnabled = true
    private var claudeSessionIdJobs = mutableMapOf<String, Job>()  // Track subscriptions per tab

    /**
     * Initializes the service with AppViewModel and sets up auto-save
     */
    suspend fun initialize(appViewModel: AppViewModel) {
        this.appViewModel = appViewModel

        // Load saved state
        val savedState = loadState()
        if (savedState.tabs.isNotEmpty()) {
            println("[AppUiStateService] Restoring ${savedState.tabs.size} saved tabs")
            appViewModel.restoreTabs(
                savedState.tabs.map { tab ->
                    AppViewModel.TabInfo(
                        projectPath = tab.projectPath,
                        claudeSessionId = tab.claudeSessionId
                    )
                },
                savedState.currentTabIndex
            )
        }

        // Subscribe to tab changes for auto-save
        appViewModel.tabs.onEach { tabs ->
            saveCurrentState()

            // Clean up old subscriptions for removed tabs
            val currentTabIds = tabs.map { it.sessionId.value }.toSet()
            claudeSessionIdJobs.keys.toList().forEach { tabId ->
                if (tabId !in currentTabIds) {
                    claudeSessionIdJobs[tabId]?.cancel()
                    claudeSessionIdJobs.remove(tabId)
                }
            }

            // Subscribe to claudeSessionId changes in each tab (only if not already subscribed)
            tabs.forEach { tab ->
                val tabId = tab.sessionId.value
                if (tabId !in claudeSessionIdJobs) {
                    val job = tab.claudeSessionId.onEach { _ ->
                        saveCurrentState()
                    }.launchIn(scope)
                    claudeSessionIdJobs[tabId] = job
                }
            }
        }.launchIn(scope)

        appViewModel.currentTabIndex.onEach { _ ->
            saveCurrentState()
        }.launchIn(scope)
    }

    /**
     * Disables auto-save (used during shutdown)
     */
    fun disableAutoSave() {
        autoSaveEnabled = false
    }

    /**
     * Saves current UI state to file
     */
    fun saveCurrentState() {
        if (!::appViewModel.isInitialized || !autoSaveEnabled) return

        val tabs = appViewModel.getTabsForPersistence().map { info ->
            UiState.Tab(
                projectPath = info.projectPath,
                claudeSessionId = info.claudeSessionId
            )
        }

        val state = UiState(
            tabs = tabs,
            currentTabIndex = appViewModel.currentTabIndex.value
        )

        saveState(state)
    }

    private fun loadState(): UiState {
        return try {
            if (stateFile.exists()) {
                val content = stateFile.readText()
                json.decodeFromString<UiState>(content)
            } else {
                UiState()
            }
        } catch (e: Exception) {
            println("[AppUiStateService] Failed to load UI state: ${e.message}")
            UiState()
        }
    }

    private fun saveState(state: UiState) {
        try {
            val jsonContent = json.encodeToString(state)
            stateFile.writeText(jsonContent)
            println("[AppUiStateService] Saved UI state: ${state.tabs.size} tabs")
        } catch (e: Exception) {
            println("[AppUiStateService] Failed to save UI state: ${e.message}")
        }
    }

    /**
     * UI State model for persistence
     */
    @Serializable
    data class UiState(
        val tabs: List<Tab> = emptyList(),
        val currentTabIndex: Int? = null,
    ) {
        @Serializable
        data class Tab(
            val projectPath: String,
            val claudeSessionId: String,
        )
    }
}