package com.gromozeka.application.service

import com.gromozeka.bot.domain.model.ConversationInitiator
import com.gromozeka.domain.model.Agent
import com.gromozeka.domain.model.Conversation

/**
 * Manages conversation tabs (active sessions).
 * 
 * Tab is a domain concept representing an active conversation session:
 * - One tab = one conversation with an agent
 * - Each tab has project context, agent, conversation history
 * - Tabs can have parent-child relationships (agent creation hierarchy)
 */
interface TabManager {
    
    /**
     * Information about a tab (active conversation session).
     */
    data class TabInfo(
        val tabId: String,
        val conversationId: Conversation.Id,
        val agentId: Agent.Id,
        val projectPath: String,
        val isWaitingForResponse: Boolean,
        val parentTabId: String?
    )
    
    /**
     * Create new tab (conversation session).
     * 
     * @param projectPath working directory for the conversation
     * @param agent agent to handle this conversation (null = default agent)
     * @param conversationId existing conversation to resume (null = create new)
     * @param initialMessage optional initial message to send
     * @param setAsCurrent whether to switch focus to the new tab
     * @param initiator who initiated this conversation (User, Agent, System)
     * @return index of the created tab
     */
    suspend fun createTab(
        projectPath: String,
        agent: Agent? = null,
        conversationId: Conversation.Id? = null,
        initialMessage: Conversation.Message? = null,
        setAsCurrent: Boolean = true,
        initiator: ConversationInitiator = ConversationInitiator.User,
    ): Int
    
    /**
     * Switch to tab by ID.
     * 
     * @param tabId tab identifier
     * @return tab information if found, null otherwise
     */
    suspend fun switchToTab(tabId: String): TabInfo?
    
    /**
     * Send message to specific tab.
     * 
     * @param tabId target tab identifier
     * @param message message text
     * @param instructions message instructions (source, response_expected, etc)
     */
    suspend fun sendMessageToTab(
        tabId: String,
        message: String,
        instructions: List<Conversation.Message.Instruction>
    )
    
    /**
     * List all active tabs.
     * 
     * @return list of tab information for all active tabs
     */
    suspend fun listTabs(): List<TabInfo>
    
    /**
     * Find tab by ID.
     * 
     * @param tabId tab identifier
     * @return tab information if found, null otherwise
     */
    suspend fun findTabById(tabId: String): TabInfo?
}
