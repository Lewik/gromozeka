package com.gromozeka.presentation.ui.state

import com.gromozeka.domain.model.ConversationInitiator
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceContextReference
import kotlinx.serialization.Serializable

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
        val projectId: Project.Id,
        val workspaceId: Workspace.Id,
        val conversationId: Conversation.Id,
        val activeMessageInstructionIds: Set<String> = emptySet(),
        val userInput: String = "",
        val workspaceContextReferences: List<WorkspaceContextReference> = emptyList(),
        val isWaitingForResponse: Boolean = false,
        val tabId: String,
        val parentTabId: String? = null,
        val agent: AgentDefinition,
        val initiator: ConversationInitiator = ConversationInitiator.User,
        val customPrompts: List<String> = emptyList(),

        // Message editing state
        val editMode: Boolean = false,
        val selectedMessageIds: Set<Conversation.Message.Id> = emptySet(),
        val collapsedContentItems: Map<Conversation.Message.Id, Set<Int>> = emptyMap(), // messageId -> set of collapsed content item indices
        val lastToggledMessageId: Conversation.Message.Id? = null,
        val lastToggleAction: Boolean? = null,
        val editingMessageId: Conversation.Message.Id? = null,
        val editingMessageText: String = "",
    )
}
