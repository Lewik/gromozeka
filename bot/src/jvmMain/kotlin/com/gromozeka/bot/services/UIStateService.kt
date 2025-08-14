package com.gromozeka.bot.services

import com.gromozeka.bot.ui.state.UIState
import com.gromozeka.bot.ui.viewmodel.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Service responsible for saving and restoring Gromozeka UI state
 * Features debounced auto-save to prevent excessive I/O operations
 */
@Service
class UIStateService(
    private val settingsService: SettingsService,
    @Qualifier("coroutineScope") private val scope: CoroutineScope,
) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val stateFile: File by lazy {
        File(settingsService.gromozekaHome, "ui-state.json")
    }

    private lateinit var appViewModel: AppViewModel
    private var autoSaveEnabled = true
    private var tabSubscriptions = mutableMapOf<String, Job>()  // Single subscription per tab

    // Debounced save mechanism with replay to handle late subscribers
    private val saveRequests = MutableSharedFlow<Unit>(replay = 1)

    init {
        // Debounced saving: 50ms for faster saving (responsive but not excessive)
        saveRequests
            .debounce(50.milliseconds)
            .onEach { performSave() }
            .launchIn(scope)
    }

    /**
     * Initializes the service with AppViewModel and sets up auto-save
     */
    suspend fun initialize(appViewModel: AppViewModel) {
        this.appViewModel = appViewModel

        // Load saved state
        val savedState = loadState()
        if (savedState.tabs.isNotEmpty()) {
            println("[UIStateService] Restoring ${savedState.tabs.size} saved tabs")
            appViewModel.restoreTabs(savedState)
        }

        // Subscribe to tab changes for auto-save
        appViewModel.tabs.onEach { tabs ->
            requestSave()

            // Clean up old subscriptions for removed tabs
            val currentTabIds = tabs.map { it.sessionId.value }.toSet()
            tabSubscriptions.keys.toList().forEach { tabId ->
                if (tabId !in currentTabIds) {
                    tabSubscriptions[tabId]?.cancel()
                    tabSubscriptions.remove(tabId)
                }
            }

            // Subscribe to uiState changes in each tab
            tabs.forEach { tab ->
                val tabId = tab.sessionId.value
                if (tabId !in tabSubscriptions) {
                    val job = tab.uiState.onEach { _ -> 
                        requestSave() 
                    }.launchIn(scope)
                    tabSubscriptions[tabId] = job
                }
            }
        }.launchIn(scope)

        appViewModel.currentTabIndex.onEach { _ ->
            requestSave()
        }.launchIn(scope)
    }

    /**
     * Forces immediate save of current state (used during shutdown)
     */
    fun forceSave() {
        performSave()
    }

    /**
     * Disables auto-save (used during shutdown)
     */
    fun disableAutoSave() {
        autoSaveEnabled = false
    }

    /**
     * Request a debounced save operation
     */
    private fun requestSave() {
        if (autoSaveEnabled) {
            saveRequests.tryEmit(Unit)
        }
    }

    /**
     * Performs the actual save operation
     */
    private fun performSave() {
        if (!::appViewModel.isInitialized || !autoSaveEnabled) return

        // Direct serialization of UIState.Tab - no DTOs!
        val tabs = appViewModel.tabs.value.map { tab ->
            tab.uiState.value  // Direct UIState.Tab
        }

        val state = UIState(
            tabs = tabs,
            currentTabIndex = appViewModel.currentTabIndex.value
        )

        saveState(state)
    }

    private fun loadState(): UIState {
        return try {
            if (stateFile.exists()) {
                val content = stateFile.readText()
                json.decodeFromString<UIState>(content)
            } else {
                UIState()
            }
        } catch (e: Exception) {
            println("[UIStateService] Failed to load UI state: ${e.message}")
            UIState()
        }
    }

    private fun saveState(state: UIState) {
        try {
            val jsonContent = json.encodeToString(state)
            stateFile.writeText(jsonContent)
            println("[UIStateService] Saved UI state: ${state.tabs.size} tabs")
        } catch (e: Exception) {
            println("[UIStateService] Failed to save UI state: ${e.message}")
        }
    }
}