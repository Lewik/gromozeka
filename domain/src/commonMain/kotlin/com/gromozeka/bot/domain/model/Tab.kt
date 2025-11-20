package com.gromozeka.bot.domain.model

import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.Serializable

/**
 * Active conversation session with UI representation.
 * 
 * Tab is domain abstraction for managing "communication screen" between AI and user.
 * In AI projects, UI control is part of business logic - AI needs to understand and
 * control the outgoing information channel, similar to how humans use gestures in conversation.
 * 
 * Architecture:
 * - Not every Conversation has Tab (headless conversations exist)
 * - Tab can be destroyed while Conversation persists
 * - Multiple Tabs can reference same Conversation (multi-client: Mac + iPhone)
 * - Platform-specific implementations: TabMacOS, TabIOS, TabWeb
 * 
 * Immutability:
 * - Tab is immutable - use copy methods to create modified versions
 * - Implementations must provide copy methods for all properties
 * 
 * @see TabManager for tab lifecycle operations
 * @see Conversation for business logic state
 */
interface Tab {
    /**
     * Unique tab identifier for UI routing and MCP communication.
     * 
     * Used by:
     * - MCP tools (create_tab, tell_agent, switch_tab)
     * - Inter-agent communication routing
     * - UI focus management
     */
    val id: Id
    
    /**
     * Reference to conversation this tab displays.
     * 
     * Multiple tabs can reference same conversation (multi-client support).
     * Conversation persists when tab is closed.
     */
    val conversationId: Conversation.Id
    
    /**
     * Parent tab that created this tab (agent hierarchy tracking).
     * 
     * Null for user-created tabs.
     * Used for multi-agent collaboration tracking.
     */
    val parentTabId: Id?
    
    /**
     * Current focus state of this tab.
     * 
     * Determines if tab is actively displayed to user.
     * AI can control focus via TabManager.switchTo()
     */
    val focusState: FocusState
    
    /**
     * Create modified tab with new focus state.
     * 
     * Immutability pattern - returns new instance, original unchanged.
     * 
     * @param focusState new focus state
     * @return new Tab instance with updated focus state
     */
    fun withFocusState(focusState: FocusState): Tab
    
    /**
     * Tab identifier for UI routing and MCP communication.
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)
    
    /**
     * Tab focus state.
     * 
     * Controls whether tab is actively displayed to user.
     * AI can switch focus via TabManager to control information flow.
     */
    enum class FocusState {
        /**
         * Tab is actively displayed and receives user input.
         */
        FOCUSED,
        
        /**
         * Tab exists but is not currently visible.
         * 
         * Can receive messages from agents in background.
         */
        BACKGROUND
    }
}
