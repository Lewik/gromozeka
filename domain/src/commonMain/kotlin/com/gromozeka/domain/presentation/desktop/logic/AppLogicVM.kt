package com.gromozeka.domain.presentation.desktop.logic

import com.gromozeka.domain.model.ConversationInitiator
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.presentation.desktop.component.TabComponentVM
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for main application orchestration and tab management.
 *
 * This is a **LogicVM** (not ComponentVM) - manages application-level state and orchestration
 * across multiple tabs and sessions. Does NOT define specific UI layout.
 *
 * ## Responsibilities
 * - Manage lifecycle of conversation tabs (create, close, switch)
 * - Track current tab selection and navigation
 * - Restore application state from previous session
 * - Coordinate tab operations (rename, duplicate, interrupt)
 * - Integrate with knowledge graph (remember thread, add to graph)
 *
 * ## Tab Lifecycle Flow
 * ```
 * User creates tab → createTab() → create Conversation (if needed)
 *                                → create TabComponentVM instance
 *                                → add to [tabs] list
 *                                → optionally switch to new tab
 *                                → send initial message (if provided)
 *
 * User switches tab → selectTab() → update [currentTabIndex]
 *                                 → emit [currentTab] change
 *
 * User closes tab → closeTab() → remove from [tabs]
 *                              → adjust [currentTabIndex] if needed
 *                              → cleanup tab resources
 * ```
 *
 * ## State Persistence
 * - Current tabs and active tab saved to UIState
 * - Restored on app restart via [restoreTabs]
 * - Tab order and custom names preserved
 *
 * ## Tab Identification
 * - **Tab Index** - position in [tabs] list (0-based, changes on close)
 * - **Tab ID** - stable UUID for MCP communication (survives reordering)
 * - **Conversation ID** - links to persistent Conversation entity
 *
 * @property tabs All open conversation tabs (ordered, reactive)
 * @property currentTabIndex Index of currently selected tab (null = no tab selected)
 * @property currentTab Currently active tab (null = no tab selected, derived from tabs + currentTabIndex)
 */
interface AppLogicVM {
    // State (survives recomposition)
    val tabs: StateFlow<List<TabComponentVM>>
    val currentTabIndex: StateFlow<Int?>
    val currentTab: StateFlow<TabComponentVM?>
    
    // Actions
    /**
     * Create new conversation tab.
     * This is a TRANSACTIONAL operation - creates Conversation (if needed) AND tab atomically.
     *
     * Tab creation logic:
     * 1. Resolve agent (use provided or default)
     * 2. Load existing conversation OR create new one
     * 3. Create TabComponentVM instance
     * 4. Add to tabs list
     * 5. Optionally switch to new tab (if setAsCurrent = true)
     * 6. Send initial message if provided
     *
     * @param projectPath Absolute path to project directory
     * @param agent Agent to handle this tab (null = use default agent)
     * @param conversationId Existing conversation to load (null = create new)
     * @param initialMessage First message to send after tab creation (null = no message)
     * @param setAsCurrent Whether to switch to this tab immediately (default: true)
     * @param initiator Who initiated this conversation (User, Agent, System)
     * @return Index of created tab in [tabs] list
     */
    suspend fun createTab(
        projectPath: String,
        agent: AgentDefinition? = null,
        conversationId: Conversation.Id? = null,
        initialMessage: Conversation.Message? = null,
        setAsCurrent: Boolean = true,
        initiator: ConversationInitiator
    ): Int
    
    /**
     * Close tab at specified index.
     * Removes tab from [tabs] list and adjusts [currentTabIndex] if needed.
     * NOT TRANSACTIONAL - only removes tab from UI, conversation persists.
     *
     * Current tab adjustment logic:
     * - If closing current tab → switch to previous tab (index - 1)
     * - If closing first tab and others exist → switch to new first tab
     * - If closing last tab → currentTabIndex becomes null
     *
     * @param index Tab index to close (0-based)
     */
    suspend fun closeTab(index: Int)
    
    /**
     * Switch to tab at specified index.
     * Updates [currentTabIndex] and emits [currentTab] change.
     *
     * @param index Tab index to select (null = deselect all, -1 = system tab)
     * @throws IllegalArgumentException if index out of bounds
     */
    suspend fun selectTab(index: Int?)
    
    /**
     * Switch to tab by stable tab ID.
     * Finds tab with matching ID and selects it.
     *
     * @param tabId Stable tab identifier (survives reordering)
     * @return Selected TabComponentVM or null if not found
     */
    suspend fun selectTab(tabId: TabId): TabComponentVM?
    
    /**
     * Find tab by stable ID without switching to it.
     *
     * @param tabId Tab identifier to search
     * @return TabComponentVM if found, null otherwise
     */
    fun findTabByTabId(tabId: TabId): TabComponentVM?
    
    /**
     * Send interrupt signal to currently active tab.
     * Cancels ongoing AI response streaming if any.
     * Safe to call when no tab selected or no streaming in progress.
     */
    suspend fun sendInterruptToCurrentSession()
    
    /**
     * Restore tabs from saved UIState.
     * Recreates TabComponentVM instances from persisted state.
     * This is a TRANSACTIONAL operation - restores all tabs atomically.
     *
     * Restoration logic:
     * 1. Load each tab's conversation from repository
     * 2. Create new conversation if original not found (fallback)
     * 3. Recreate TabComponentVM with saved UI state
     * 4. Restore currentTabIndex selection
     *
     * Failed tab restorations are logged but don't block overall restore.
     *
     * @param uiState Saved UI state from previous session
     */
    suspend fun restoreTabs(uiState: UIState)
    
    /**
     * Rename tab with custom display name.
     * NOT TRANSACTIONAL - caller must handle transaction boundaries.
     *
     * @param tabIndex Tab index to rename
     * @param newName Custom display name (null or blank = clear custom name)
     */
    suspend fun renameTab(tabIndex: Int, newName: String?)
    
    /**
     * Reset tab name to default (project path based).
     * Equivalent to renameTab(tabIndex, null).
     * NOT TRANSACTIONAL - caller must handle transaction boundaries.
     *
     * @param tabIndex Tab index to reset
     */
    suspend fun resetTabName(tabIndex: Int)
    
    /**
     * Remember current thread in vector memory.
     * Stores conversation messages in vector database for RAG retrieval.
     * This is a TRANSACTIONAL operation - adds all thread messages atomically.
     *
     * Safe to call when no tab selected - operation is no-op.
     */
    suspend fun rememberCurrentThread()
    
    /**
     * Add current thread to knowledge graph.
     * Extracts entities and relationships from conversation via LLM.
     * This is a TRANSACTIONAL operation - creates graph nodes/edges atomically.
     *
     * Safe to call when no tab selected - operation is no-op.
     */
    suspend fun addToGraphCurrentThread()
    
    /**
     * Cleanup all tabs and reset state.
     * Used during app shutdown or full reset.
     * Clears [tabs] list and resets [currentTabIndex] to null.
     */
    suspend fun cleanup()
    
    /**
     * Stable tab identifier used for MCP inter-agent communication.
     * Survives tab reordering and app restarts.
     */
    @JvmInline
    value class TabId(val value: String)
    
    /**
     * Serializable UI state for persistence.
     * Contains minimal data needed to restore application state.
     *
     * @property tabs List of tab states to restore
     * @property currentTabIndex Index of selected tab (null = no selection)
     */
    data class UIState(
        val tabs: List<TabState>,
        val currentTabIndex: Int?
    ) {
        /**
         * UI state for a single tab.
         * Enough data to recreate TabComponentVM after app restart.
         *
         * @property projectPath Absolute path to project directory
         * @property conversationId ID of conversation to load
         * @property activeMessageTags Set of active message tag IDs (e.g., "thinking_ultrathink")
         * @property userInput Unsent text in input field
         * @property customName Custom tab display name (null = use default)
         * @property tabId Stable tab identifier for MCP
         * @property parentTabId ID of tab that spawned this tab (null = user created)
         * @property agent Agent handling this conversation
         * @property initiator Who created this tab
         * @property editMode Whether message editing mode is active
         * @property selectedMessageIds IDs of messages selected for bulk operations
         * @property collapsedMessageIds IDs of messages with collapsed thinking blocks
         */
        data class TabState(
            val projectPath: String,
            val conversationId: Conversation.Id,
            val activeMessageTags: Set<String>,
            val userInput: String,
            val customName: String?,
            val tabId: String,
            val parentTabId: String?,
            val agent: AgentDefinition,
            val initiator: ConversationInitiator,
            val editMode: Boolean,
            val selectedMessageIds: Set<Conversation.Message.Id>,
            val collapsedMessageIds: Set<Conversation.Message.Id>
        )
    }
}
