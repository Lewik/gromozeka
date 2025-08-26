package com.gromozeka.bot.ui.viewmodel

import com.gromozeka.bot.services.ContextExtractionService
import com.gromozeka.bot.services.ContextFileService
import com.gromozeka.bot.services.ContextItem
import com.gromozeka.shared.domain.message.ChatMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.time.Clock

class ContextsPanelViewModel(
    private val contextFileService: ContextFileService,
    private val contextExtractionService: ContextExtractionService,
    private val appViewModel: AppViewModel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    @Serializable
    data class UIState(
        val contexts: List<ContextItemUI> = emptyList(),
        val filteredContexts: List<ContextItemUI> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val searchQuery: String = "",
        val selectedProject: String? = null,
        val showOnlyRecent: Boolean = false,
        val sortBy: SortOption = SortOption.DATE_DESC,
        val operationInProgress: Set<String> = emptySet(),
    )

    enum class SortOption(val displayName: String) {
        DATE_DESC("Newest first"),
        DATE_ASC("Oldest first"),
        NAME_ASC("Name A-Z"),
        NAME_DESC("Name Z-A"),
        PROJECT("By project")
    }

    @Serializable
    data class ContextItemUI(
        val id: String,
        val name: String,
        val projectPath: String,
        val projectName: String,
        val filesCount: Int,
        val linksCount: Int,
        val contentPreview: String,
        val extractedAt: String,
        val filePath: String,
        val tags: List<String> = emptyList(),
        val isRecentlyUsed: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UIState())
    val uiState: StateFlow<UIState> = _uiState.asStateFlow()

    init {
        scope.launch {
            loadContexts()
        }
    }

    suspend fun loadContexts() {
        _uiState.update { it.copy(isLoading = true, error = null) }

        try {
            val contexts = contextFileService.listAllContexts()
                .map { contextItem -> contextItem.toUIRepresentation() }
                .sortedByDescending { it.extractedAt }

            _uiState.update {
                it.copy(
                    contexts = contexts,
                    filteredContexts = applyFilters(contexts, it.searchQuery, it.selectedProject, it.showOnlyRecent),
                    isLoading = false
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = "Failed to load contexts: ${e.message}"
                )
            }
        }
    }

    suspend fun extractContextsFromCurrentTab() {
        val currentTab = appViewModel.currentTab.value ?: return
        val tabId = currentTab.uiState.value.tabId

        _uiState.update { it.copy(isLoading = true) }

        try {
            contextExtractionService.extractContextsFromTab(tabId)
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = "Failed to extract contexts: ${e.message}"
                )
            }
        }
    }

    suspend fun startContextInNewTab(context: ContextItemUI) {
        markOperationInProgress(context.id)

        try {
            val contextContent = contextFileService.loadContextContent(context.filePath)
            val currentTab = appViewModel.currentTab.value
            val parentTabId = currentTab?.uiState?.value?.tabId

            // Create ChatMessage with context content and proper sender
            val chatMessage = ChatMessage(
                role = ChatMessage.Role.USER,
                content = listOf(ChatMessage.ContentItem.UserMessage(contextContent)),
                instructions = emptyList(),
                sender = parentTabId?.let { ChatMessage.Sender.Tab(it) } ?: ChatMessage.Sender.User,
                uuid = UUID.randomUUID().toString(),
                timestamp = Clock.System.now(),
                llmSpecificMetadata = null
            )
            
            appViewModel.createTab(
                projectPath = context.projectPath,
                initialMessage = chatMessage
            )

        } catch (e: Exception) {
            _uiState.update {
                it.copy(error = "Failed to start context: ${e.message}")
            }
        } finally {
            markOperationComplete(context.id)
        }
    }

    suspend fun openContextInIDE(context: ContextItemUI) {
        try {
            val command = listOf("idea", context.filePath)
            ProcessBuilder(command).start()
        } catch (e: Exception) {
            _uiState.update {
                it.copy(error = "Failed to open in IDE: ${e.message}")
            }
        }
    }

    suspend fun deleteContext(context: ContextItemUI): Boolean {
        markOperationInProgress(context.id)

        return try {
            contextFileService.deleteContext(context.filePath).getOrThrow()

            _uiState.update { state ->
                val newContexts = state.contexts.filter { it.id != context.id }
                state.copy(
                    contexts = newContexts,
                    filteredContexts = applyFilters(
                        newContexts,
                        state.searchQuery,
                        state.selectedProject,
                        state.showOnlyRecent
                    )
                )
            }

            true
        } catch (e: Exception) {
            _uiState.update {
                it.copy(error = "Failed to delete context: ${e.message}")
            }
            false
        } finally {
            markOperationComplete(context.id)
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filteredContexts = applyFilters(state.contexts, query, state.selectedProject, state.showOnlyRecent)
            )
        }
    }

    fun updateProjectFilter(projectPath: String?) {
        _uiState.update { state ->
            state.copy(
                selectedProject = projectPath,
                filteredContexts = applyFilters(state.contexts, state.searchQuery, projectPath, state.showOnlyRecent)
            )
        }
    }

    fun toggleRecentFilter() {
        _uiState.update { state ->
            val newShowOnlyRecent = !state.showOnlyRecent
            state.copy(
                showOnlyRecent = newShowOnlyRecent,
                filteredContexts = applyFilters(
                    state.contexts,
                    state.searchQuery,
                    state.selectedProject,
                    newShowOnlyRecent
                )
            )
        }
    }

    fun updateSortOption(sortOption: SortOption) {
        _uiState.update { state ->
            val sortedContexts = state.contexts.sortedWith(getSortComparator(sortOption))
            state.copy(
                sortBy = sortOption,
                contexts = sortedContexts,
                filteredContexts = applyFilters(
                    sortedContexts,
                    state.searchQuery,
                    state.selectedProject,
                    state.showOnlyRecent
                )
            )
        }
    }

    private fun applyFilters(
        contexts: List<ContextItemUI>,
        searchQuery: String,
        selectedProject: String?,
        showOnlyRecent: Boolean,
    ): List<ContextItemUI> {
        return contexts
            .filter { context ->
                if (searchQuery.isNotBlank()) {
                    context.name.contains(searchQuery, ignoreCase = true) ||
                            context.contentPreview.contains(searchQuery, ignoreCase = true) ||
                            context.projectName.contains(searchQuery, ignoreCase = true)
                } else true
            }
            .filter { context ->
                selectedProject?.let { context.projectPath == it } ?: true
            }
            .filter { context ->
                if (showOnlyRecent) context.isRecentlyUsed else true
            }
    }

    private fun getSortComparator(sortOption: SortOption): Comparator<ContextItemUI> {
        return when (sortOption) {
            SortOption.DATE_DESC -> compareByDescending { it.extractedAt }
            SortOption.DATE_ASC -> compareBy { it.extractedAt }
            SortOption.NAME_ASC -> compareBy { it.name }
            SortOption.NAME_DESC -> compareByDescending { it.name }
            SortOption.PROJECT -> compareBy<ContextItemUI> { it.projectName }.thenBy { it.name }
        }
    }

    private fun markOperationInProgress(contextId: String) {
        _uiState.update {
            it.copy(operationInProgress = it.operationInProgress + contextId)
        }
    }

    private fun markOperationComplete(contextId: String) {
        _uiState.update {
            it.copy(operationInProgress = it.operationInProgress - contextId)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onCleared() {
        scope.cancel()
    }
}

private fun ContextItem.toUIRepresentation(): ContextsPanelViewModel.ContextItemUI {
    return ContextsPanelViewModel.ContextItemUI(
        id = extractedAt ?: UUID.randomUUID().toString(),
        name = name,
        projectPath = projectPath,
        projectName = File(projectPath).name,
        filesCount = files.size,
        linksCount = links.size,
        contentPreview = content.take(100) + if (content.length > 100) "..." else "",
        extractedAt = extractedAt ?: "Unknown",
        filePath = filePath,
        isRecentlyUsed = run {
            try {
                val weekAgo = Instant.now().minus(Duration.ofDays(7))
                Instant.parse(extractedAt ?: "1970-01-01T00:00:00Z").isAfter(weekAgo)
            } catch (e: Exception) {
                false
            }
        }
    )
}