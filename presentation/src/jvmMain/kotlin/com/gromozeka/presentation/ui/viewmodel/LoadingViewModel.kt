package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.infrastructure.ai.config.mcp.McpConfigurationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.springframework.stereotype.Component

@Component
class LoadingViewModel(
    private val mcpConfigurationService: McpConfigurationService
) {
    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Initializing)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()

    sealed class LoadingState {
        object Initializing : LoadingState()
        data class LoadingMCP(val serverName: String, val current: Int, val total: Int) : LoadingState()
        object Complete : LoadingState()
        data class Error(val message: String) : LoadingState()
    }

    suspend fun initialize() {
        try {
            _loadingState.value = LoadingState.Initializing

            // Start MCP servers with progress tracking
            mcpConfigurationService.startMcpClientsWithProgress { serverName, current, total ->
                _loadingState.value = LoadingState.LoadingMCP(serverName, current, total)
            }

            _loadingState.value = LoadingState.Complete
        } catch (e: Exception) {
            _loadingState.value = LoadingState.Error(e.message ?: "Unknown error")
        }
    }
}
