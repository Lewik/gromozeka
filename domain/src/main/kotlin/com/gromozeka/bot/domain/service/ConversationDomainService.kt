package com.gromozeka.bot.domain.service

import com.gromozeka.bot.domain.model.Conversation

/**
 * Domain service for conversation lifecycle management.
 *
 * Orchestrates complex operations spanning multiple entities:
 * - Conversation: top-level container for AI interactions
 * - Thread: append-only message sequence (immutable by default)
 * - Message: individual user/assistant/system messages
 * - ThreadMessageLink: position-based message ordering
 *
 * Key patterns:
 * - Threads are immutable: edit/delete/squash creates new thread
 * - Fork creates independent conversation copy
 * - Atomic operations: conversation+thread creation, message appending
 *
 * @see Conversation for domain model hierarchy
 * @see ConversationDataService for conversation persistence
 * @see ThreadDataService for thread persistence
 * @see MessageDataService for message persistence
 * @see ThreadMessageDataService for message ordering
 */
interface ConversationDomainService {

    /**
     * Creates new conversation with initial empty thread.
     *
     * If project doesn't exist, creates it first.
     * This is a TRANSACTIONAL operation - creates conversation AND initial thread atomically.
     *
     * @param projectPath filesystem path to project (creates if not exists)
     * @param displayName human-readable conversation title (default: empty)
     * @param aiProvider AI provider identifier (e.g., "CLAUDE", "GEMINI")
     * @param modelName model identifier (e.g., "claude-3-5-sonnet-20241022")
     * @return created conversation with assigned IDs
     */
    suspend fun create(
        projectPath: String,
        displayName: String = "",
        aiProvider: String,
        modelName: String
    ): Conversation

    /**
     * Finds conversation by unique identifier.
     *
     * @param id conversation identifier
     * @return conversation if found, null otherwise
     */
    suspend fun findById(id: Conversation.Id): Conversation?

    /**
     * Retrieves project path for conversation.
     *
     * @param conversationId conversation identifier
     * @return project filesystem path, or null if conversation or project not found
     */
    suspend fun getProjectPath(conversationId: Conversation.Id): String?

    /**
     * Retrieves all conversations in project.
     *
     * @param projectPath filesystem path to project
     * @return conversations in project (empty list if project not found)
     */
    suspend fun findByProject(projectPath: String): List<Conversation>

    /**
     * Deletes conversation permanently.
     *
     * Infrastructure should cascade delete threads and thread-message links.
     * Messages may be preserved for garbage collection logic.
     * This is a transactional operation.
     *
     * @param id conversation to delete
     */
    suspend fun delete(id: Conversation.Id)

    /**
     * Updates conversation display name.
     *
     * This is a transactional operation.
     *
     * @param conversationId conversation to update
     * @param displayName new display name
     * @return updated conversation, or null if conversation not found
     */
    suspend fun updateDisplayName(
        conversationId: Conversation.Id,
        displayName: String
    ): Conversation?

    /**
     * Creates independent copy of conversation.
     *
     * Copies conversation metadata, current thread, all messages, and message ordering.
     * Message IDs remapped to new UUIDs for independent lifecycle.
     * Appends " (fork)" to display name.
     *
     * This is a COMPLEX TRANSACTIONAL operation:
     * 1. Create new conversation
     * 2. Create new thread
     * 3. Copy all messages with new IDs
     * 4. Copy thread-message links with remapped IDs
     *
     * @param conversationId source conversation
     * @return forked conversation with new IDs
     * @throws IllegalStateException if source conversation not found
     */
    suspend fun fork(conversationId: Conversation.Id): Conversation

    /**
     * Appends message to current thread.
     *
     * Validates message belongs to conversation.
     * Updates thread timestamp and position counter.
     *
     * This is a TRANSACTIONAL operation:
     * 1. Save message
     * 2. Add thread-message link with next position
     * 3. Update thread timestamp
     *
     * @param conversationId conversation identifier
     * @param message message to append (must have matching conversationId)
     * @return updated conversation, or null if conversation not found
     * @throws IllegalArgumentException if message.conversationId != conversationId
     * @throws IllegalStateException if conversation not found
     */
    suspend fun addMessage(
        conversationId: Conversation.Id,
        message: Conversation.Message
    ): Conversation?

    /**
     * Loads messages from current thread.
     *
     * Messages returned in thread order (by position).
     *
     * @param conversationId conversation identifier
     * @return messages in current thread
     * @throws IllegalStateException if conversation not found
     */
    suspend fun loadCurrentMessages(conversationId: Conversation.Id): List<Conversation.Message>

    /**
     * Creates new thread with edited message.
     *
     * Immutable thread pattern: original thread preserved, new thread created.
     *
     * This is a COMPLEX TRANSACTIONAL operation:
     * 1. Create edited message (originalIds = [messageId])
     * 2. Create new thread (originalThread = currentThread)
     * 3. Copy thread-message links, replacing target message with edited
     * 4. Update conversation.currentThread
     *
     * @param conversationId conversation identifier
     * @param messageId message to edit
     * @param newContent replacement content
     * @return updated conversation with new current thread
     * @throws IllegalStateException if conversation not found
     * @throws IllegalArgumentException if message not found in current thread
     */
    suspend fun editMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
        newContent: List<Conversation.Message.ContentItem>
    ): Conversation?

    /**
     * Creates new thread without specified message.
     *
     * Immutable thread pattern: original thread preserved, new thread created.
     *
     * This is a COMPLEX TRANSACTIONAL operation:
     * 1. Create new thread (originalThread = currentThread)
     * 2. Copy thread-message links, filtering out target message
     * 3. Reindex positions (close gaps)
     * 4. Update conversation.currentThread
     *
     * @param conversationId conversation identifier
     * @param messageId message to remove
     * @return updated conversation with new current thread
     * @throws IllegalStateException if conversation not found
     * @throws IllegalArgumentException if message not found in current thread
     */
    suspend fun deleteMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id
    ): Conversation?

    /**
     * Creates new thread without specified messages (batch delete).
     *
     * Immutable thread pattern: original thread preserved, new thread created.
     *
     * This is a COMPLEX TRANSACTIONAL operation:
     * 1. Validate all messages exist in current thread
     * 2. Create new thread (originalThread = currentThread)
     * 3. Copy thread-message links, filtering out target messages
     * 4. Reindex positions (close gaps)
     * 5. Update conversation.currentThread
     *
     * @param conversationId conversation identifier
     * @param messageIds messages to remove (must be non-empty)
     * @return updated conversation with new current thread
     * @throws IllegalArgumentException if messageIds empty or messages not found
     * @throws IllegalStateException if conversation not found
     */
    suspend fun deleteMessages(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>
    ): Conversation?

    /**
     * Creates new thread with squashed message replacing originals.
     *
     * Squash is AI operation (summarization/restructuring), not concatenation.
     * Replaces multiple messages with single summarized message at position of last original.
     *
     * Immutable thread pattern: original thread preserved, new thread created.
     *
     * This is a COMPLEX TRANSACTIONAL operation:
     * 1. Validate at least 2 messages to squash
     * 2. Create squashed message (originalIds = messageIds, role = USER)
     * 3. Create new thread (originalThread = currentThread)
     * 4. Copy thread-message links:
     *    - Replace last original message with squashed
     *    - Filter out other original messages
     *    - Keep other messages
     * 5. Reindex positions (close gaps)
     * 6. Update conversation.currentThread
     *
     * @param conversationId conversation identifier
     * @param messageIds messages to squash (at least 2 required)
     * @param squashedContent content of squashed message
     * @return updated conversation with new current thread
     * @throws IllegalArgumentException if < 2 messages or messages not found
     * @throws IllegalStateException if conversation not found
     */
    suspend fun squashMessages(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
        squashedContent: List<Conversation.Message.ContentItem>
    ): Conversation?
}
