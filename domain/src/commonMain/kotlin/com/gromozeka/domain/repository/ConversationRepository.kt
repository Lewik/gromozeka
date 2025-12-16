package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project

/**
 * Repository for managing conversation entities.
 *
 * Conversation is the top-level aggregate containing threads, messages, and metadata.
 * Each conversation belongs to a project and tracks AI provider/model configuration.
 *
 * @see Conversation for domain model
 */
interface ConversationRepository {

    /**
     * Creates new conversation.
     *
     * Conversation ID and currentThread must be set before calling.
     * Use uuid7() for time-based ordering of IDs.
     * This is a transactional operation.
     *
     * @param conversation conversation to create with all required fields
     * @return created conversation (unchanged, for fluent API)
     * @throws IllegalArgumentException if conversation.id or conversation.currentThread is blank
     * @throws IllegalStateException if project doesn't exist
     */
    suspend fun create(conversation: Conversation): Conversation

    /**
     * Finds conversation by unique identifier.
     *
     * @param id conversation identifier
     * @return conversation if found, null if doesn't exist
     */
    suspend fun findById(id: Conversation.Id): Conversation?

    /**
     * Finds all conversations for specified project, ordered by update time (newest first).
     *
     * Returns empty list if project has no conversations or project doesn't exist.
     *
     * @param projectId project to query
     * @return conversations in descending updatedAt order
     */
    suspend fun findByProject(projectId: Project.Id): List<Conversation>

    /**
     * Deletes conversation permanently.
     *
     * Cascades to threads and messages (all conversation data deleted).
     * This is a TRANSACTIONAL operation - atomic deletion of entire conversation tree.
     *
     * @param id conversation to delete
     */
    suspend fun delete(id: Conversation.Id)

    /**
     * Updates conversation's current thread pointer.
     *
     * Current thread changed when user switches between threads or creates new thread.
     * This operation is NOT transactional - caller must handle transaction boundaries.
     *
     * Side effect: Updates conversation.updatedAt to current timestamp.
     *
     * @param id conversation to update
     * @param threadId new current thread ID
     * @throws IllegalStateException if conversation or thread doesn't exist
     */
    suspend fun updateCurrentThread(id: Conversation.Id, threadId: Conversation.Thread.Id)

    /**
     * Updates conversation's display name.
     *
     * This operation is NOT transactional - caller must handle transaction boundaries.
     *
     * Side effect: Updates conversation.updatedAt to current timestamp.
     *
     * @param id conversation to update
     * @param displayName new display name (can be empty)
     * @throws IllegalStateException if conversation doesn't exist
     */
    suspend fun updateDisplayName(id: Conversation.Id, displayName: String)
    
    /**
     * Updates conversation's agent definition.
     *
     * This operation is NOT transactional - caller must handle transaction boundaries.
     *
     * Side effect: Updates conversation.updatedAt to current timestamp.
     *
     * @param id conversation to update
     * @param agentDefinitionId new agent definition ID
     * @throws IllegalStateException if conversation doesn't exist
     */
    suspend fun updateAgentDefinition(id: Conversation.Id, agentDefinitionId: com.gromozeka.domain.model.AgentDefinition.Id)
}
