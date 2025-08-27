package com.gromozeka.bot.ui.viewmodel

import com.gromozeka.bot.model.ChatSessionMetadata
import com.gromozeka.bot.services.SessionSearchService
import klog.KLoggers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionSearchViewModel(
    private val sessionSearchService: SessionSearchService,
    private val scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<ChatSessionMetadata>>(emptyList())
    val searchResults: StateFlow<List<ChatSessionMetadata>> = _searchResults.asStateFlow()


    private var searchJob: Job? = null

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun performSearch() {
        val query = _searchQuery.value.trim()
        if (query.isEmpty()) {
            clearSearch()
            return
        }

        // Cancel previous search if running
        searchJob?.cancel()

        searchJob = scope.launch {
            _isSearching.value = true

            try {
                val results = sessionSearchService.searchSessions(query)
                _searchResults.value = results

                log.info("Found ${results.size} sessions matching '$query'")
            } catch (e: Exception) {
                log.warn(e, "Search error: ${e.message}")
                _searchResults.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _isSearching.value = false
        _showSearchResults.value = false
    }

    /**
     * Returns true when search results should be shown (query is not empty or has results)
     */
    private val _showSearchResults = MutableStateFlow(false)
    val showSearchResults: StateFlow<Boolean> = _showSearchResults.asStateFlow()

    init {
        // Update show results when query changes
        scope.launch {
            searchQuery.collect { query ->
                _showSearchResults.value = query.isNotEmpty()
            }
        }
    }
}