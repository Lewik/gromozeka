package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents who initiated a conversation.
 * 
 * Tracks conversation origin for proper context and hierarchy:
 * - User: Human user started the conversation
 * - Agent: Another agent created this conversation (multi-agent collaboration)
 * - System: System-initiated (resume, context restoration, etc.)
 */
@Serializable
sealed class ConversationInitiator {
    @Serializable
    object User : ConversationInitiator()
    
    @Serializable
    data class Agent(val tabId: Tab.Id) : ConversationInitiator()
    
    @Serializable
    object System : ConversationInitiator()
}
