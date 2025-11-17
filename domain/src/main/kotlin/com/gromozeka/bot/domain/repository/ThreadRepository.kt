package com.gromozeka.bot.domain.repository

import com.gromozeka.bot.domain.model.Conversation
import kotlinx.datetime.Instant

/**
 * Repository for managing conversation threads.
 *
 * Thread represents append-only message sequence within conversation.
 * Explicit operations (delete/edit/squash) create new threads derived from original.
 *
 * @see Conversation.Thread for domain model
 */
interface ThreadRepository {

    /**
     * Saves thread to persistent storage.
     *
     * Creates new thread if ID doesn't exist, updates existing otherwise.
     * Thread ID must be set before calling (use uuid7() for time-based ordering).
     * This is a transactional operation.
     *
     * @param thread thread to save with all fields populated
     * @return saved thread (unchanged, for fluent API)
     * @throws IllegalArgumentException if thread.id is blank
     * @throws IllegalStateException if conversation doesn't exist
     */
    suspend fun save(thread: Conversation.Thread): Conversation.Thread

    /**
     * Finds thread by unique identifier.
     *
     * @param id thread identifier
     * @return thread if found, null if doesn't exist
     */
    suspend fun findById(id: Conversation.Thread.Id): Conversation.Thread?

    /**
     * Finds all threads in conversation, ordered by creation time (newest first).
     *
     * Returns empty list if conversation has no threads or conversation doesn't exist.
     *
     * @param conversationId conversation to query
     * @return threads in descending createdAt order
     */
    suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.Thread>

    /**
     * Deletes thread permanently.
     *
     * Associated messages should be deleted separately via ThreadMessageRepository.
     * Database constraints may handle cascade deletion automatically.
     *
     * @param id thread to delete
     */
    suspend fun delete(id: Conversation.Thread.Id)

    /**
     * Updates thread's last modification timestamp.
     *
     * Called when new messages added to thread.
     * This operation is NOT transactional - caller must handle transaction boundaries.
     *
     * @param id thread to update
     * @param updatedAt new timestamp
     * @throws IllegalStateException if thread doesn't exist
     */
    suspend fun updateTimestamp(id: Conversation.Thread.Id, updatedAt: Instant)

    /**
     * Increments thread's turn counter and returns new value.
     *
     * Turn number tracks conversation progress (user + assistant = 1 turn).
     * This is a TRANSACTIONAL operation - read-modify-write must be atomic.
     *
     * @param id thread to update
     * @return new turn number after increment
     * @throws IllegalStateException if thread doesn't exist
     */
    suspend fun incrementTurnNumber(id: Conversation.Thread.Id): Int
}
