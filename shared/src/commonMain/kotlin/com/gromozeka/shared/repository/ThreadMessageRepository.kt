package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Conversation

/**
 * Link between thread and message with position ordering.
 *
 * Threads and messages have many-to-many relationship:
 * - Same message can appear in multiple threads (when threads fork or copy history)
 * - Each thread contains ordered sequence of messages
 *
 * Position determines message order within thread (0-indexed, sequential).
 *
 * @property threadId thread containing the message
 * @property messageId message in the thread
 * @property position message position within thread (0 = first message, 1 = second, etc.)
 */
data class ThreadMessageLink(
    val threadId: Conversation.Thread.Id,
    val messageId: Conversation.Message.Id,
    val position: Int
)

/**
 * Repository for managing thread-message associations.
 *
 * Handles many-to-many relationship between threads and messages.
 * Messages are ordered within threads via position field.
 *
 * Thread-message links are immutable once created. Editing messages creates
 * new threads with updated link sequences.
 *
 * This repository focuses on link management - message content is handled by
 * MessageRepository, thread metadata by ThreadRepository.
 */
interface ThreadMessageRepository {
    /**
     * Adds single thread-message link with specified position.
     *
     * Position should be sequential within thread (0, 1, 2, ...).
     * Duplicate links (same thread + message + position) may be rejected or
     * ignored depending on implementation.
     *
     * This is a transactional operation.
     *
     * @param threadId thread to link message to
     * @param messageId message to link to thread
     * @param position message position within thread (0-indexed)
     */
    suspend fun add(threadId: Conversation.Thread.Id, messageId: Conversation.Message.Id, position: Int)

    /**
     * Adds multiple thread-message links in batch.
     *
     * Optimized for copying message sequences when creating new threads
     * (e.g., during edit/delete/squash operations).
     *
     * All links in batch should belong to same thread for optimal performance,
     * though implementations may support multi-thread batches.
     *
     * This is a TRANSACTIONAL operation - either all links succeed or all fail.
     *
     * @param links list of thread-message links to create
     */
    suspend fun addBatch(links: List<ThreadMessageLink>)

    /**
     * Retrieves all thread-message links for a thread.
     *
     * Returns links ordered by position (ascending).
     *
     * @param threadId thread to query
     * @return list of links (empty list if thread has no messages)
     */
    suspend fun getByThread(threadId: Conversation.Thread.Id): List<ThreadMessageLink>

    /**
     * Retrieves maximum position in thread.
     *
     * Used to determine next position when appending messages.
     *
     * @param threadId thread to query
     * @return maximum position, or null if thread has no messages
     */
    suspend fun getMaxPosition(threadId: Conversation.Thread.Id): Int?

    /**
     * Retrieves messages in thread with correct ordering.
     *
     * Returns fully loaded Message objects ordered by position (ascending).
     * This is a JOIN operation combining thread_messages and messages tables.
     *
     * Preferred method for loading thread content - handles ordering and
     * message loading in single efficient query.
     *
     * @param threadId thread to query
     * @return ordered list of messages (empty list if thread has no messages)
     */
    suspend fun getMessagesByThread(threadId: Conversation.Thread.Id): List<Conversation.Message>

    /**
     * Deletes all links for a thread.
     *
     * Called when thread is deleted. Messages themselves are not deleted
     * (they may be referenced by other threads or squash operations).
     *
     * This is a CASCADE delete operation for thread cleanup.
     *
     * @param threadId thread to remove all links for
     */
    suspend fun deleteByThread(threadId: Conversation.Thread.Id)
}
