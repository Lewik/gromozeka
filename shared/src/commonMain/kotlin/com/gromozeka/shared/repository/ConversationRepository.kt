package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Conversation
import com.gromozeka.shared.domain.Project

/**
 * Repository for managing conversations.
 *
 * Handles persistence of conversation metadata: display name, AI provider/model,
 * current thread reference, and timestamps.
 *
 * Conversations are the top-level entity for grouping related threads and messages.
 * Each conversation belongs to a project and uses a specific AI model.
 *
 * Conversation deletion typically cascades to threads and messages (implementation-specific).
 */
interface ConversationRepository {
    /**
     * Creates new conversation.
     *
     * Conversation ID and currentThread ID must be set before calling.
     * Initial thread should be created separately via ThreadRepository before
     * calling this method.
     *
     * This is a transactional operation.
     *
     * @param conversation conversation to create (with all required fields)
     * @return created conversation (same as input)
     */
    suspend fun create(conversation: Conversation): Conversation

    /**
     * Finds conversation by unique identifier.
     *
     * @param id conversation identifier
     * @return conversation if found, null if conversation doesn't exist
     */
    suspend fun findById(id: Conversation.Id): Conversation?

    /**
     * Finds all conversations in a project.
     *
     * Returns conversations ordered by update time (most recently updated first).
     * Includes both active and archived conversations.
     *
     * @param projectId project to query
     * @return list of conversations (empty list if project has no conversations)
     */
    suspend fun findByProject(projectId: Project.Id): List<Conversation>

    /**
     * Deletes conversation and all associated data.
     *
     * This is a CASCADE delete operation - removes conversation, threads,
     * thread-message links, and may remove orphaned messages (implementation-specific).
     *
     * @param id conversation identifier
     */
    suspend fun delete(id: Conversation.Id)

    /**
     * Updates conversation's current thread reference.
     *
     * Called when edit/delete/squash operations create new threads.
     * This operation is NOT transactional - caller must handle transaction boundaries.
     *
     * @param id conversation identifier
     * @param threadId new current thread ID (must exist in ThreadRepository)
     */
    suspend fun updateCurrentThread(id: Conversation.Id, threadId: Conversation.Thread.Id)

    /**
     * Updates conversation's display name.
     *
     * This operation is NOT transactional - caller must handle transaction boundaries.
     *
     * @param id conversation identifier
     * @param displayName new display name (can be empty string for auto-generated name)
     */
    suspend fun updateDisplayName(id: Conversation.Id, displayName: String)
}
