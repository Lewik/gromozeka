package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.application.service.ConversationSearchService
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConversationSearchViewModel(
    private val conversationSearchService: ConversationSearchService,
    private val scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Pair<Conversation, Project>>>(emptyList())
    val searchResults: StateFlow<List<Pair<Conversation, Project>>> = _searchResults.asStateFlow()

    private val _showSearchResults = MutableStateFlow(false)
    val showSearchResults: StateFlow<Boolean> = _showSearchResults.asStateFlow()

    private var searchJob: Job? = null

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query

        searchJob?.cancel()
        if (query.isBlank()) {
            clearSearch()
        } else {
            searchJob = scope.launch {
                delay(300)
                performSearch()
            }
        }
    }

    fun performSearch() {
        val query = _searchQuery.value.trim()
        if (query.isEmpty()) {
            clearSearch()
            return
        }

        searchJob?.cancel()
        searchJob = scope.launch {
            _isSearching.value = true
            _showSearchResults.value = true

            try {
                val results = conversationSearchService.searchConversations(query)
                _searchResults.value = results

                log.info("Found ${results.size} conversations matching '$query'")
            } catch (e: Exception) {
                log.warn(e) { "Search error: ${e.message}" }
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
}
