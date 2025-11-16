package com.gromozeka.bot.domain.service

import com.gromozeka.bot.domain.model.Conversation

/**
 * Link between thread and message with position.
 *
 * Represents ordered membership of message in thread.
 * Position determines message order within thread (0-based index).
 *
 * @property threadId thread containing the message
 * @property messageId message in the thread
 * @property position zero-based position in thread (0 = first message)
 */
data class ThreadMessageLink(
    val threadId: Conversation.Thread.Id,
    val messageId: Conversation.Message.Id,
    val position: Int
)

/**
 * DataService for managing thread-message relationships.
 *
 * Threads are ordered sequences of messages. This service manages the many-to-many
 * relationship between threads and messages with positional ordering.
 *
 * Key concepts:
 * - Messages exist independently (managed by MessageDataService)
 * - Threads reference messages via links with positions
 * - Same message can appear in multiple threads (different positions)
 * - Position determines display order (0 = first, 1 = second, etc)
 *
 * @see Conversation.Thread for thread model
 * @see Conversation.Message for message model
 */
interface ThreadMessageDataService {

    /**
     * Adds message to thread at specified position.
     *
     * Position must be sequential (max_position + 1 for append).
     * This is a transactional operation.
     *
     * @param threadId thread to add message to
     * @param messageId message to add
     * @param position zero-based position (0 = first message)
     * @throws IllegalArgumentException if position is not sequential
     * @throws IllegalStateException if thread or message doesn't exist
     */
    suspend fun add(threadId: Conversation.Thread.Id, messageId: Conversation.Message.Id, position: Int)

    /**
     * Adds multiple messages to thread in batch (optimization for copy operations).
     *
     * Used when creating derived threads (fork, squash) - copies messages efficiently.
     * All positions must be sequential within batch.
     * This is a TRANSACTIONAL operation - all links created atomically or none.
     *
     * @param links thread-message links to create
     * @throws IllegalArgumentException if positions are not sequential
     * @throws IllegalStateException if any thread or message doesn't exist
     */
    suspend fun addBatch(links: List<ThreadMessageLink>)

    /**
     * Gets all links for thread, ordered by position (ascending).
     *
     * Returns empty list if thread has no messages or thread doesn't exist.
     *
     * @param threadId thread to query
     * @return links in position order
     */
    suspend fun getByThread(threadId: Conversation.Thread.Id): List<ThreadMessageLink>

    /**
     * Gets maximum position in thread (for append operations).
     *
     * Returns null if thread is empty (first message should have position 0).
     * Next append should use (maxPosition + 1).
     *
     * @param threadId thread to query
     * @return maximum position or null if thread is empty
     */
    suspend fun getMaxPosition(threadId: Conversation.Thread.Id): Int?

    /**
     * Loads thread messages with JOIN optimization.
     *
     * Returns fully populated Message objects in position order.
     * More efficient than getByThread() + findByIds() - single query with JOIN.
     *
     * Returns empty list if thread has no messages or thread doesn't exist.
     *
     * @param threadId thread to load messages from
     * @return messages in position order
     */
    suspend fun getMessagesByThread(threadId: Conversation.Thread.Id): List<Conversation.Message>

    /**
     * Deletes all links for thread (CASCADE on thread deletion).
     *
     * Called automatically when thread is deleted.
     * Does NOT delete messages - only removes references.
     * This is a transactional operation.
     *
     * @param threadId thread to delete links from
     */
    suspend fun deleteByThread(threadId: Conversation.Thread.Id)
}
