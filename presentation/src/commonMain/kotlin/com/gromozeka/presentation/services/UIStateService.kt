package com.gromozeka.presentation.services

import com.gromozeka.domain.service.ConversationTabLayoutService
import com.gromozeka.presentation.ui.state.UIState
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class UIStateService(
    private val scope: CoroutineScope,
    private val conversationTabLayoutService: ConversationTabLayoutService,
    private val store: UIStateStore = InMemoryUIStateStore(),
) {
    private var appViewModel: AppViewModel? = null
    private var autoSaveJob: Job? = null
    private var sharedTabsJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun initialize(appViewModel: AppViewModel) {
        this.appViewModel = appViewModel

        val savedState = store.load() ?: UIState()
        appViewModel.restoreTabs(savedState, conversationTabLayoutService.snapshot())

        sharedTabsJob?.cancel()
        sharedTabsJob = scope.launch {
            conversationTabLayoutService.observe().collect(appViewModel::applyConversationTabLayout)
        }

        autoSaveJob?.cancel()
        autoSaveJob = scope.launch {
            combine(
                appViewModel.tabs.flatMapLatest { tabs ->
                    if (tabs.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(tabs.map { it.uiState }) { tabStates -> tabStates.toList() }
                    }
                },
                appViewModel.currentTabIndex
            ) { tabs, currentTabIndex ->
                UIState(tabs = tabs, currentTabIndex = currentTabIndex)
            }.collect(store::save)
        }
    }

    fun forceSave() {
        appViewModel?.let { store.save(it.snapshotUIState()) }
    }

    fun disableAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
        sharedTabsJob?.cancel()
        sharedTabsJob = null
    }
}
