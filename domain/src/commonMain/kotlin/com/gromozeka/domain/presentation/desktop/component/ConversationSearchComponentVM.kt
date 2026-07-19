package com.gromozeka.domain.presentation.desktop.component

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for conversation search component.
 *
 * ## UI Layout (Search Dropdown)
 * ```
 * ┌─────────────────────────────────────────────────┐
 * │ 🔍 [Search conversations...____________  ] [X] │  ← Search input
 * └─────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────┐  ← Dropdown appears when
 * │ Searching...                              ⏳    │     showSearchResults = true
 * └─────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────┐
 * │ RESULTS (3)                                     │
 * ├─────────────────────────────────────────────────┤
 * │ 📁 Project: gromozeka-dev                       │
 * │    💬 "Implement vector search"                 │
 * │       Last: 2 hours ago                         │
 * ├─────────────────────────────────────────────────┤
 * │ 📁 Project: my-app                              │
 * │    💬 "Fix authentication bug"                  │
 * │       Last: yesterday                           │
 * ├─────────────────────────────────────────────────┤
 * │ 📁 Project: docs-site                           │
 * │    💬 "Update installation guide"               │
 * │       Last: 3 days ago                          │
 * └─────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────┐
 * │ No results found for "test query"               │  ← Empty state
 * └─────────────────────────────────────────────────┘
 * ```
 *
 * ## Behavior
 *
 * ### Search Flow
 * 1. User types in search field → [updateSearchQuery]
 * 2. **Debounced delay** (300ms) before executing search
 * 3. If query blank → clear results, hide dropdown
 * 4. If query not blank → [performSearch] automatically
 * 5. Set [isSearching] = true, show loading indicator
 * 6. Execute search via ConversationSearchService
 * 7. Update [searchResults] with matches
 * 8. Set [showSearchResults] = true, display dropdown
 * 9. Set [isSearching] = false
 *
 * ### Search Cancellation
 * - Typing new character cancels previous pending search
 * - Only latest query executes (prevents race conditions)
 * - Clearing input ([clearSearch]) cancels all pending searches
 *
 * ### Result Selection
 * - Click result → emit event to parent (not part of this VM)
 * - Parent handles navigation (e.g., AppLogicVM creates/switches tab)
 *
 * ### Dropdown Visibility
 * - [showSearchResults] controls dropdown visibility
 * - Shown when: query not blank AND (searching OR results exist)
 * - Hidden when: query blank OR [clearSearch] called
 *
 * ## Search Algorithm
 * Searches across:
 * - Conversation display names
 * - Project names and descriptions
 *
 * Results grouped by project, sorted by last update time (newest first).
 *
 * ## Performance
 * - Debounced search (300ms) reduces unnecessary queries
 * - Maximum 50 results returned (configurable in service)
 * - Search executes in background coroutine (non-blocking UI)
 *
 * @property searchQuery Current search text (reactive)
 * @property isSearching Whether search is currently executing (show loading spinner)
 * @property searchResults List of matching (Conversation, Project) pairs (sorted by recency)
 * @property showSearchResults Whether to display results dropdown (false = hidden)
 */
interface ConversationSearchComponentVM {
    // State (survives recomposition)
    val searchQuery: StateFlow<String>
    val isSearching: StateFlow<Boolean>
    val searchResults: StateFlow<List<SearchResult>>
    val showSearchResults: StateFlow<Boolean>
    
    // Actions
    /**
     * Update search query text.
     * Triggers debounced search after 300ms delay.
     * Cancels any pending search from previous input.
     *
     * Search behavior:
     * - If query blank → [clearSearch]
     * - If query not blank → wait 300ms → [performSearch]
     *
     * @param query search text (case-insensitive)
     */
    fun updateSearchQuery(query: String)
    
    /**
     * Execute search immediately without debounce delay.
     * Useful for manual search trigger (e.g., Enter key press).
     *
     * If query blank → [clearSearch] instead.
     * Sets [isSearching] = true during execution.
     */
    fun performSearch()
    
    /**
     * Clear search query and results.
     * Resets all state:
     * - [searchQuery] = ""
     * - [searchResults] = empty
     * - [isSearching] = false
     * - [showSearchResults] = false
     *
     * Cancels any pending search.
     */
    fun clearSearch()
    
    /**
     * Single search result entry.
     *
     * @property conversation matched conversation
     * @property project project containing this conversation
     */
    data class SearchResult(
        val conversation: Conversation,
        val project: Project
    )
}
