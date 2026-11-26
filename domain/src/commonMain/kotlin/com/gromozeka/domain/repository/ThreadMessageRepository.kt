package com.gromozeka.domain.repository

import com.gromozeka.domain.model.CompactionMessageHeader
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ContextCompactionResult
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

/** One bounded raw archive row. Raw history is traversed only through pages. */
data class ThreadMessagePageEntry(
    val position: Int,
    val message: Conversation.Message,
)

data class ThreadMessageReplacement(
    val sourceMessageId: Conversation.Message.Id,
    val replacementMessageId: Conversation.Message.Id,
)

/** Selected sparse bodies and the physical size of the same captured thread prefix. */
data class ThreadContextMessages(
    val messages: List<Conversation.Message>,
    val sourceMessageCount: Int,
    val continuationState: ContextCompactionResult.ContinuationState = ContextCompactionResult.ContinuationState(),
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

    /** Raw archive link access is deliberately page-only: a thread has no bounded maximum size. */
    suspend fun getLinkPage(
        threadId: Conversation.Thread.Id,
        afterPositionExclusive: Int = -1,
        limit: Int = 1_000,
    ): List<ThreadMessageLink>

    /** Raw archive body access is deliberately page-only: a thread has no bounded maximum size. */
    suspend fun getMessagePage(
        threadId: Conversation.Thread.Id,
        afterPositionExclusive: Int = -1,
        limit: Int = 256,
    ): List<ThreadMessagePageEntry>

    /** Complete canonical body only when the exact message belongs to the thread. */
    suspend fun findMessage(
        threadId: Conversation.Thread.Id,
        messageId: Conversation.Message.Id,
    ): Conversation.Message?

    /** Exact message or a current descendant whose immutable originalIds contain it. */
    suspend fun findMessageByIdOrOriginal(
        threadId: Conversation.Thread.Id,
        messageId: Conversation.Message.Id,
    ): Conversation.Message?

    /** Atomically copy one branch while applying an optional replacement and exclusions. */
    suspend fun copyWithChanges(
        sourceThreadId: Conversation.Thread.Id,
        targetThreadId: Conversation.Thread.Id,
        replacement: ThreadMessageReplacement? = null,
        excludedMessageIds: Set<Conversation.Message.Id> = emptySet(),
    )

    /** Checks the bounded text-only response-review retry threshold without hydrating thread history. */
    suspend fun hasAtLeastResponseReviewContinuations(
        threadId: Conversation.Thread.Id,
        turnId: String,
        requiredCount: Int,
    ): Boolean

    /**
     * Ordered headers from the latest full checkpoint (inclusive) through the current tail.
     * Conversation archives are unbounded; model and compaction paths must not materialize older windows.
     */
    suspend fun compactionWindowHeaders(threadId: Conversation.Thread.Id): List<CompactionMessageHeader>

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
