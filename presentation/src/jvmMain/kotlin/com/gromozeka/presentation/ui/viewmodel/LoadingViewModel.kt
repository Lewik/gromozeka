package com.gromozeka.presentation.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LoadingViewModel {
    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Initializing)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()

    sealed class LoadingState {
        object Initializing : LoadingState()
        data class LoadingMCP(val serverName: String, val current: Int, val total: Int) : LoadingState()
        object Complete : LoadingState()
        data class Error(val message: String) : LoadingState()
    }

    suspend fun initialize() {
        _loadingState.value = LoadingState.Complete
    }
}
