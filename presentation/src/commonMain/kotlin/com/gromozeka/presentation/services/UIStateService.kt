package com.gromozeka.presentation.services

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
    private val store: UIStateStore = InMemoryUIStateStore(),
) {
    private var appViewModel: AppViewModel? = null
    private var autoSaveJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun initialize(appViewModel: AppViewModel) {
        this.appViewModel = appViewModel

        store.load()?.let { savedState ->
            appViewModel.restoreTabs(savedState)
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
    }
}
