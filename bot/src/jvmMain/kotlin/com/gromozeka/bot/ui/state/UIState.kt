package com.gromozeka.bot.ui.state

import com.gromozeka.domain.model.Agent
import com.gromozeka.domain.model.Conversation
import kotlinx.serialization.Serializable

@Serializable
sealed class ConversationInitiator {
    @Serializable
    object User : ConversationInitiator()
    
    @Serializable
    data class Agent(val tabId: String) : ConversationInitiator()
    
    @Serializable
    object System : ConversationInitiator()  // Resume, context, etc.
}

/**
 * Root UI state for the entire Gromozeka application
 * Contains all persistent UI state that survives application restarts
 */
@Serializable
data class UIState(
    val tabs: List<Tab> = emptyList(),
    val currentTabIndex: Int? = null,
) {
    /**
     * UI state for a single tab
     * Follows Android's official UiState pattern with ViewModel
     *
     * Tab represents UI session state - what user sees in a single tab.
     * - conversationId links to Conversation (persistent data)
     * - tabId used for MCP inter-agent communication (tell_agent)
     * - parentTabId tracks agent creation hierarchy
     */
    @Serializable
    data class Tab(
        val projectPath: String,
        val conversationId: Conversation.Id,
        val activeMessageTags: Set<String> = emptySet(),
        val userInput: String = "",
        val isWaitingForResponse: Boolean = false,
        val customName: String? = null,
        val tabId: String,
        val parentTabId: String? = null,
        val agent: Agent,
        val initiator: ConversationInitiator = ConversationInitiator.User,
        val customPrompts: List<String> = emptyList(),

        // Message editing state
        val editMode: Boolean = false,
        val selectedMessageIds: Set<Conversation.Message.Id> = emptySet(),
        val collapsedMessageIds: Set<Conversation.Message.Id> = emptySet(),
        val lastToggledMessageId: Conversation.Message.Id? = null,
        val lastToggleAction: Boolean? = null,
        val editingMessageId: Conversation.Message.Id? = null,
        val editingMessageText: String = "",
    )
}
