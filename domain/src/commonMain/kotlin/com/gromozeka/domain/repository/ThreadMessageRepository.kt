package com.gromozeka.domain.repository

import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ToolSequenceMessageHeader
import kotlinx.serialization.Serializable

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
@Serializable
data class ThreadMessageLink(
    val threadId: Conversation.Thread.Id,
    val messageId: Conversation.Message.Id,
    val position: Int
)

/** Selected sparse bodies and the physical size of the same captured thread prefix. */
data class ThreadContextMessages(
    val messages: List<Conversation.Message>,
    val sourceMessageCount: Int,
) {
    init {
        require(sourceMessageCount >= messages.size) {
            "Source message count cannot be smaller than the selected context"
        }
    }
}

/** Lightweight visible text selected without hydrating the canonical message body. */
data class RecentVisibleMessage(
    val id: Conversation.Message.Id,
    val role: Conversation.Message.Role,
    val text: String,
) {
    init {
        require(role == Conversation.Message.Role.USER || role == Conversation.Message.Role.ASSISTANT) {
            "Recent visible message must have a user or assistant role"
        }
    }
}

/**
 * Repository for managing thread-message relationships.
 *
 * Threads are ordered sequences of messages. This repository manages the many-to-many
 * relationship between threads and messages with positional ordering.
 *
 * Key concepts:
 * - Messages exist independently (managed by MessageRepository)
 * - Threads reference messages via links with positions
 * - Same message can appear in multiple threads (different positions)
 * - Position determines display order (0 = first, 1 = second, etc)
 *
 * @see Conversation.Thread for thread model
 * @see Conversation.Message for message model
 */
interface ThreadMessageRepository {

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

    /** Checks the exact thread/message link without loading message bodies or other links. */
    suspend fun contains(threadId: Conversation.Thread.Id, messageId: Conversation.Message.Id): Boolean

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

    /** Ordered headers with current author attribution. Message bodies are stored independently. */
    suspend fun compactionHeaders(threadId: Conversation.Thread.Id): List<CompactionMessageHeader>

    /** Read exactly these immutable versions in the requested order, including current author attribution. */
    suspend fun compactionBodies(threadId: Conversation.Thread.Id, ids: List<Conversation.Message.Id>): List<Conversation.Message>

    /** Most recent visible user/assistant excerpts ending at an included message, bounded in rows and characters in SQL. */
    suspend fun recentVisibleMessageTextThrough(
        threadId: Conversation.Thread.Id,
        throughMessageId: Conversation.Message.Id,
        limit: Int,
        maxCharacters: Int,
    ): List<RecentVisibleMessage>

    /** Normal provider-aware model context, bounded at and including one message in the thread. */
    suspend fun getContextMessagesThrough(
        threadId: Conversation.Thread.Id,
        throughMessageId: Conversation.Message.Id,
        nativeProvider: String?,
    ): ThreadContextMessages

    /**
     * Returns an empty list when the post-checkpoint tool sequence is valid. When invalid, returns the
     * complete body-free window after the latest full checkpoint so the caller can plan a targeted repair.
     */
    suspend fun invalidToolSequenceWindow(threadId: Conversation.Thread.Id): List<ToolSequenceMessageHeader>

    /** Copy an unchanged prefix in SQL and append a replacement suffix without materializing prefix IDs. */
    suspend fun copyPrefixAndAddSuffix(
        sourceThreadId: Conversation.Thread.Id,
        targetThreadId: Conversation.Thread.Id,
        suffixStartPosition: Int,
        suffixMessageIds: List<Conversation.Message.Id>,
    )

    /**
     * Sufficient sparse raw history for the normal agent-model path, selected before body reads.
     * Preserves checkpoints, provider recovery sources and pre-projection enrichment anchors.
     * Not a timeline/archive read or a replacement for an external channel's raw history filter.
     */
    suspend fun getContextMessagesByThread(
        threadId: Conversation.Thread.Id,
        nativeProvider: String?,
    ): ThreadContextMessages

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
