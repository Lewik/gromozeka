package com.gromozeka.presentation.ui.viewmodel

import com.gromozeka.domain.model.ConversationSearchHit
import com.gromozeka.domain.model.ConversationSearchRequest
import com.gromozeka.domain.service.ConversationSearchService
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    private val _searchResults = MutableStateFlow<List<ConversationSearchHit>>(emptyList())
    val searchResults: StateFlow<List<ConversationSearchHit>> = _searchResults.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _nextCursor = MutableStateFlow<String?>(null)
    val hasMoreResults: StateFlow<Boolean> = _nextCursor
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _showSearchResults = MutableStateFlow(false)
    val showSearchResults: StateFlow<Boolean> = _showSearchResults.asStateFlow()

    private var searchJob: Job? = null
    private var searchGeneration = 0L

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query

        searchJob?.cancel()
        val generation = ++searchGeneration
        if (query.isBlank()) {
            clearSearch()
        } else {
            _searchResults.value = emptyList()
            _nextCursor.value = null
            _isSearching.value = true
            _showSearchResults.value = true
            searchJob = scope.launch {
                delay(300)
                executeSearch(query = query.trim(), reset = true, generation = generation)
            }
        }
    }

    fun performSearch() {
        val query = _searchQuery.value.trim()
        if (query.isEmpty()) {
            clearSearch()
            return
        }

        launchSearch(query = query, reset = true)
    }

    fun loadMore() {
        if (_isSearching.value || _isLoadingMore.value || _nextCursor.value == null) return
        launchSearch(query = _searchQuery.value.trim(), reset = false)
    }

    private fun launchSearch(query: String, reset: Boolean) {
        searchJob?.cancel()
        val generation = ++searchGeneration
        searchJob = scope.launch {
            executeSearch(query = query, reset = reset, generation = generation)
        }
    }

    private suspend fun executeSearch(query: String, reset: Boolean, generation: Long) {
        if (reset) {
            _isSearching.value = true
            _nextCursor.value = null
        } else {
            _isLoadingMore.value = true
        }
        _showSearchResults.value = true

        try {
            val page = conversationSearchService.search(
                ConversationSearchRequest(
                    query = query,
                    cursor = if (reset) null else _nextCursor.value,
                )
            )
            if (searchGeneration != generation || _searchQuery.value.trim() != query) return

            _searchResults.value = if (reset) {
                page.hits
            } else {
                (_searchResults.value + page.hits).distinctBy { hit ->
                    listOf(hit.matchKind, hit.conversation.id, hit.threadId, hit.messageId)
                }
            }
            _nextCursor.value = page.nextCursor

            log.info("Found ${page.hits.size} conversation search hits for '$query'")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Search error: ${e.message}" }
            if (reset && searchGeneration == generation) _searchResults.value = emptyList()
        } finally {
            if (searchGeneration == generation) {
                _isSearching.value = false
                _isLoadingMore.value = false
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchGeneration++
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _isSearching.value = false
        _isLoadingMore.value = false
        _nextCursor.value = null
        _showSearchResults.value = false
    }
}
