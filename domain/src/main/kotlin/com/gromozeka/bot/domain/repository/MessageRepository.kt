package com.gromozeka.bot.domain.repository

import com.gromozeka.bot.domain.model.Conversation

/**
 * Repository for managing conversation messages.
 *
 * Messages are immutable content items (user input, assistant responses, tool calls/results).
 * Messages exist independently of threads - threads reference messages via ThreadMessageRepository.
 *
 * Message lifecycle:
 * - Created once, never modified (immutability)
 * - Can be referenced by multiple threads (via ThreadMessageRepository)
 * - Can be source/result of squash operations (via SquashOperationRepository)
 * - Deleted only when no references exist (orphaned)
 *
 * @see Conversation.Message for domain model
 */
interface MessageRepository {

    /**
     * Saves message to persistent storage.
     *
     * Message ID must be set before calling (use uuid7() for time-based ordering).
     * Messages are immutable - calling save with existing ID throws exception.
     * This is a transactional operation.
     *
     * @param message message to save with all fields populated
     * @return saved message (unchanged, for fluent API)
     * @throws IllegalArgumentException if message.id is blank
     * @throws IllegalStateException if message with this ID already exists (immutability violation)
     */
    suspend fun save(message: Conversation.Message): Conversation.Message

    /**
     * Finds message by unique identifier.
     *
     * @param id message identifier
     * @return message if found, null if doesn't exist
     */
    suspend fun findById(id: Conversation.Message.Id): Conversation.Message?

    /**
     * Finds multiple messages by IDs.
     *
     * Returns only existing messages - missing IDs silently skipped.
     * Order of results matches order of input IDs where possible.
     *
     * @param ids message identifiers to find
     * @return found messages (may be fewer than requested IDs)
     */
    suspend fun findByIds(ids: List<Conversation.Message.Id>): List<Conversation.Message>

    /**
     * Finds all messages in conversation, ordered by creation time (oldest first).
     *
     * Returns messages from ALL threads in conversation.
     * For messages in specific thread, use ThreadMessageRepository.getMessagesByThread().
     *
     * Returns empty list if conversation has no messages or conversation doesn't exist.
     *
     * @param conversationId conversation to query
     * @return all conversation messages in chronological order
     */
    suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.Message>

    /**
     * Finds all derived versions of a message (by originalIds).
     *
     * Returns all messages where originalIds contains specified ID.
     * Used for edit/squash history tracking.
     *
     * @param originalId original message ID to find versions of
     * @return derived messages (edits, squash results) in chronological order
     */
    suspend fun findVersions(originalId: Conversation.Message.Id): List<Conversation.Message>

    // TODO: Message Garbage Collection
    // Delete orphaned messages (no references from thread_messages or squash_operations).
    // Implementation options:
    // 1. Background cleanup job
    // 2. Explicit cleanup method: suspend fun cleanupOrphaned(conversationId: Conversation.Id): Int
    // 3. Database CASCADE constraints (partial solution)
    //
    // Design decisions needed:
    // - When to run cleanup (on conversation delete? periodic job?)
    // - How to detect orphans efficiently (COUNT joins vs separate queries)
    // - Whether to preserve messages for audit trail (soft delete vs hard delete)
}
