package com.gromozeka.domain.repository

import com.gromozeka.domain.model.ConversationInitiator
import com.gromozeka.domain.model.Tab
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation

/**
 * Manages tab lifecycle and operations.
 * 
 * Tab represents active conversation session with UI representation.
 * This is domain-level abstraction - implementations are platform-specific
 * (TabMacOS, TabIOS, TabWeb).
 * 
 * Multi-client support:
 * - Multiple tabs can reference same Conversation.Id
 * - Tab.Id is unique per UI instance (Mac app has different Tab.Id than iPhone)
 * - Conversation.Id is shared across all clients
 * 
 * Lifecycle:
 * - Tab can be created and destroyed independently of Conversation
 * - Closing tab does not close Conversation
 * - Headless conversations exist without any tabs
 * 
 * Platform implementations:
 * - :presentation/macos → TabMacOS : Tab
 * - :presentation/ios → TabIOS : Tab
 * - :presentation/web → TabWeb : Tab
 * 
 * @see Tab for domain abstraction
 * @see ConversationDomainService for conversation operations
 */
interface TabManager {
    
    /**
     * Create new tab (active conversation session).
     * 
     * **Tool exposure:** `create_agent` (MCP tool)
     * 
     * Creates platform-specific Tab implementation and optionally:
     * - Creates new Conversation if conversationId is null
     * - Resumes existing Conversation if conversationId provided
     * - Sends initial message if provided
     * - Switches focus if setAsCurrent is true
     * 
     * Agent hierarchy:
     * - User creates tab → initiator = ConversationInitiator.User
     * - Agent creates tab → initiator = ConversationInitiator.Agent(tabId)
     * - System creates tab → initiator = ConversationInitiator.System
     * 
     * @param projectPath working directory for conversation
     * @param agent agent to handle conversation (null = default agent)
     * @param conversationId existing conversation to resume (null = create new)
     * @param initialMessage optional initial message to send
     * @param setAsCurrent whether to switch focus to new tab
     * @param initiator who initiated this conversation (User, Agent, System)
     * @return index of created tab in platform-specific tab list
     */
    suspend fun createTab(
        projectPath: String,
        agent: AgentDefinition? = null,
        conversationId: Conversation.Id? = null,
        initialMessage: Conversation.Message? = null,
        setAsCurrent: Boolean = true,
        initiator: ConversationInitiator = ConversationInitiator.User,
    ): Int
    
    /**
     * Switch focus to tab by ID.
     * 
     * **Tool exposure:** `switch_tab` (MCP tool)
     * 
     * Changes focusState to FOCUSED for target tab, BACKGROUND for others.
     * Used by AI to control information flow - similar to human pointing at screen.
     * 
     * @param tabId tab to switch to
     * @return TabInfo if found, null if tab doesn't exist
     */
    suspend fun switchToTab(tabId: Tab.Id): TabInfo?
    
    /**
     * Send message to specific tab.
     * 
     * **Tool exposure:** `tell_agent` (MCP tool)
     * 
     * Appends message to tab's conversation with instructions for routing:
     * - Source.Agent(senderTabId) → identifies sender
     * - ResponseExpected(targetTabId) → indicates response routing
     * 
     * Used for inter-agent communication via MCP tools.
     * 
     * @param tabId target tab identifier
     * @param message message text
     * @param instructions message instructions (source, response routing, etc)
     */
    suspend fun sendMessageToTab(
        tabId: Tab.Id,
        message: String,
        instructions: List<Conversation.Message.Instruction>
    )
    
    /**
     * List all active tabs.
     * 
     * **Tool exposure:** `list_tabs` (MCP tool)
     * 
     * Returns platform-agnostic information about all tabs.
     * 
     * @return list of tab information for all active tabs
     */
    suspend fun listTabs(): List<TabInfo>
    
    /**
     * Find tab by ID.
     * 
     * Used for validation and routing in MCP tools.
     * 
     * @param tabId tab identifier
     * @return tab information if found, null otherwise
     */
    suspend fun findTabById(tabId: Tab.Id): TabInfo?
    
    /**
     * Platform-agnostic tab information.
     * 
     * Subset of Tab properties exposed to domain layer.
     * Full Tab interface includes platform-specific state.
     */
    data class TabInfo(
        val tabId: Tab.Id,
        val conversationId: Conversation.Id,
        val agentId: AgentDefinition.Id,
        val projectPath: String,
        val isWaitingForResponse: Boolean,
        val parentTabId: Tab.Id?
    )
}
