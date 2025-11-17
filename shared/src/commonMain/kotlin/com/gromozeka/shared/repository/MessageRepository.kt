package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Conversation

/**
 * Repository for managing conversation messages.
 *
 * Handles persistence of messages independently from threads. Messages are
 * linked to threads via ThreadMessageRepository (many-to-many relationship).
 *
 * Messages are immutable once created - editing creates new messages with
 * references to originals via squash operations.
 *
 * Garbage collection: Messages may be orphaned when threads are deleted.
 * Cleanup strategy is implementation-specific (background job, explicit cleanup,
 * or database CASCADE constraints).
 */
interface MessageRepository {
    /**
     * Saves new message.
     *
     * Message ID must be set before calling (use uuid7() for time-based ordering).
     * Messages are immutable - this method creates new messages only, no updates.
     *
     * This is a transactional operation.
     *
     * @param message message to save (with all required fields)
     * @return saved message (same as input)
     */
    suspend fun save(message: Conversation.Message): Conversation.Message

    /**
     * Finds message by unique identifier.
     *
     * @param id message identifier
     * @return message if found, null if message doesn't exist
     */
    suspend fun findById(id: Conversation.Message.Id): Conversation.Message?

    /**
     * Finds multiple messages by their identifiers.
     *
     * Returned messages are in arbitrary order (not necessarily matching input order).
     * Missing IDs are silently skipped (result may be shorter than input list).
     *
     * @param ids list of message identifiers to retrieve
     * @return list of found messages (empty list if no messages found)
     */
    suspend fun findByIds(ids: List<Conversation.Message.Id>): List<Conversation.Message>

    /**
     * Finds all messages in a conversation.
     *
     * Returns ALL messages ever created in this conversation, including:
     * - Messages in current thread
     * - Messages in historical threads (from edit/delete operations)
     * - Squashed messages (both sources and results)
     *
     * Does NOT return messages ordered by thread position - use
     * ThreadMessageRepository.getMessagesByThread() for ordered thread messages.
     *
     * @param conversationId conversation to query
     * @return list of all messages in conversation (empty list if conversation has no messages)
     */
    suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.Message>

    /**
     * Finds all message versions derived from original message.
     *
     * Returns messages that reference the specified message ID in their
     * originalIds field (deprecated field, used for edit/squash provenance).
     *
     * This enables finding edited versions of a message or squash results
     * that included the original message.
     *
     * Note: This uses deprecated originalIds field. New code should use
     * SquashOperationRepository for structured squash provenance tracking.
     *
     * @param originalId message ID to search for in originalIds
     * @return list of messages that reference this ID (empty list if no versions found)
     */
    suspend fun findVersions(originalId: Conversation.Message.Id): List<Conversation.Message>

    // TODO: Message Garbage Collection
    // Delete messages that are no longer referenced:
    // - Not linked to any thread (orphaned in thread_messages table)
    // - Not referenced by squash operations (source_message_ids or result_message_id)
    // - Conversation was deleted (CASCADE)
    //
    // Implementation options:
    // - Background job periodically cleaning orphans
    // - Explicit cleanup method: suspend fun cleanupOrphaned(conversationId: Conversation.Id): Int
    // - Database CASCADE constraints (partial solution)
    //
    // Encapsulate cleanup logic in repository, leverage database constraints where possible.
}
