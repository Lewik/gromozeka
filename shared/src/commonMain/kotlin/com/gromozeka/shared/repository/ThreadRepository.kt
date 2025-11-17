package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Conversation
import kotlin.time.Instant

/**
 * Repository for managing conversation threads.
 *
 * Handles persistence of thread metadata including creation time, update time,
 * and turn number tracking. Threads represent linear message histories within
 * conversations, and are immutable by default (editing creates new threads).
 *
 * Thread operations are generally transactional where noted. Implementations
 * should ensure thread metadata consistency with message operations.
 */
interface ThreadRepository {
    /**
     * Saves new thread or updates existing thread.
     *
     * Creates thread if ID doesn't exist, updates if ID exists.
     * Thread ID must be set before calling (use uuid7() for time-based ordering).
     *
     * This is a transactional operation.
     *
     * @param thread thread to save (with all required fields)
     * @return saved thread (same as input for create, updated version for update)
     */
    suspend fun save(thread: Conversation.Thread): Conversation.Thread

    /**
     * Finds thread by unique identifier.
     *
     * @param id thread identifier (time-based UUID for sortability)
     * @return thread if found, null if thread doesn't exist
     */
    suspend fun findById(id: Conversation.Thread.Id): Conversation.Thread?

    /**
     * Finds all threads belonging to a conversation.
     *
     * Returns threads ordered by creation time (newest first).
     * Includes both active thread and historical threads from edit/delete/squash operations.
     *
     * @param conversationId conversation to query
     * @return list of threads (empty list if conversation has no threads or doesn't exist)
     */
    suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.Thread>

    /**
     * Deletes thread and all associated thread-message links.
     *
     * This is a CASCADE delete operation - removes thread metadata and
     * thread-message associations. Messages themselves are not deleted
     * (they may be referenced by other threads or squash operations).
     *
     * @param id thread identifier
     */
    suspend fun delete(id: Conversation.Thread.Id)

    /**
     * Updates thread's last modification timestamp.
     *
     * Called when new messages are added to thread.
     * This operation is NOT transactional - caller must handle transaction boundaries.
     *
     * @param id thread identifier
     * @param updatedAt new timestamp (typically current time)
     */
    suspend fun updateTimestamp(id: Conversation.Thread.Id, updatedAt: Instant)

    /**
     * Increments turn number and returns new value.
     *
     * Turn number tracks AI response count within a thread.
     * Incremented atomically on each AI completion.
     *
     * This is a TRANSACTIONAL operation - increment and return are atomic.
     *
     * @param id thread identifier
     * @return new turn number after increment
     */
    suspend fun incrementTurnNumber(id: Conversation.Thread.Id): Int
}
