package com.gromozeka.shared.services

import com.gromozeka.shared.domain.Conversation
import com.gromozeka.shared.domain.Project
import com.gromozeka.shared.repository.ConversationRepository
import klog.KLoggers
import com.gromozeka.shared.repository.MessageRepository
import com.gromozeka.shared.repository.ThreadMessageRepository
import com.gromozeka.shared.repository.ThreadRepository
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Application service for conversation lifecycle and message management.
 *
 * Orchestrates operations across multiple repositories (conversations, threads,
 * messages, thread-message links) to maintain data consistency during complex
 * operations like editing, deleting, and squashing messages.
 *
 * Key responsibilities:
 * - Creating and managing conversations with initial threads
 * - Coordinating message append, edit, delete, and squash operations
 * - Maintaining thread immutability (operations create new threads)
 * - Forking conversations with message history duplication
 *
 * This service implements conversation branching model where threads are
 * immutable by default - edits create new threads preserving original history.
 */
class ConversationService(
    private val conversationRepo: ConversationRepository,
    private val threadRepo: ThreadRepository,
    private val messageRepo: MessageRepository,
    private val threadMessageRepo: ThreadMessageRepository,
    private val projectService: ProjectService
) {
    private val log = KLoggers.logger(this)

    /**
     * Creates new conversation with initial empty thread.
     *
     * Ensures project exists (creates if needed), then creates conversation
     * with empty initial thread ready for first message.
     *
     * @param projectPath absolute file system path to project directory
     * @param displayName optional conversation title (empty uses auto-generated name)
     * @param aiProvider AI provider identifier (default: "OLLAMA")
     * @param modelName model identifier (default: "llama3.2")
     * @return created conversation with new thread
     */
    suspend fun create(
        projectPath: String,
        displayName: String = "",
        aiProvider: String = "OLLAMA",
        modelName: String = "llama3.2"
    ): Conversation {
        val project = projectService.getOrCreate(projectPath)
        val now = Clock.System.now()

        val conversationId = Conversation.Id(uuid7())

        val initialThread = Conversation.Thread(
            id = Conversation.Thread.Id(uuid7()),
            conversationId = conversationId,
            originalThread = null,
            createdAt = now,
            updatedAt = now,
        )

        threadRepo.save(initialThread)

        val conversation = Conversation(
            id = conversationId,
            projectId = project.id,
            displayName = displayName,
            aiProvider = aiProvider,
            modelName = modelName,
            currentThread = initialThread.id,
            createdAt = now,
            updatedAt = now,
        )

        return conversationRepo.create(conversation)
    }

    /**
     * Finds conversation by unique identifier.
     *
     * @param id conversation identifier
     * @return conversation if found, null otherwise
     */
    suspend fun findById(id: Conversation.Id): Conversation? =
        conversationRepo.findById(id)

    /**
     * Retrieves project path for conversation.
     *
     * Looks up conversation, then resolves project path via ProjectService.
     *
     * @param conversationId conversation to query
     * @return project path if conversation and project exist, null otherwise
     */
    suspend fun getProjectPath(conversationId: Conversation.Id): String? {
        val conversation = findById(conversationId) ?: return null
        val project = projectService.findById(conversation.projectId) ?: return null
        return project.path
    }

    /**
     * Finds all conversations in project.
     *
     * @param projectPath absolute file system path to project
     * @return list of conversations (empty if project doesn't exist or has no conversations)
     */
    suspend fun findByProject(projectPath: String): List<Conversation> {
        val project = projectService.findByPath(projectPath) ?: return emptyList()
        return conversationRepo.findByProject(project.id)
    }

    /**
     * Deletes conversation and all associated data.
     *
     * Cascades to threads, thread-message links, and may remove orphaned messages
     * (implementation-specific).
     *
     * @param id conversation identifier
     */
    suspend fun delete(id: Conversation.Id) {
        conversationRepo.delete(id)
    }

    /**
     * Updates conversation display name.
     *
     * @param conversationId conversation identifier
     * @param displayName new display name
     * @return updated conversation if exists, null otherwise
     */
    suspend fun updateDisplayName(
        conversationId: Conversation.Id,
        displayName: String
    ): Conversation? {
        conversationRepo.updateDisplayName(conversationId, displayName)
        return conversationRepo.findById(conversationId)
    }

    /**
     * Creates independent copy of conversation with duplicate message history.
     *
     * Forks create new conversation in same project with:
     * - Copy of all messages in current thread (new message IDs)
     * - New thread with copied messages
     * - Display name suffixed with " (fork)"
     *
     * Original and forked conversations evolve independently after fork.
     *
     * @param conversationId conversation to fork
     * @return new forked conversation
     * @throws IllegalStateException if source conversation doesn't exist
     */
    suspend fun fork(conversationId: Conversation.Id): Conversation {
        val sourceConversation = conversationRepo.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        
        val now = Clock.System.now()
        
        val newConversationId = Conversation.Id(uuid7())
        
        val newThread = Conversation.Thread(
            id = Conversation.Thread.Id(uuid7()),
            conversationId = newConversationId,
            originalThread = null,
            createdAt = now,
            updatedAt = now,
        )
        
        threadRepo.save(newThread)
        
        val newConversation = Conversation(
            id = newConversationId,
            projectId = sourceConversation.projectId,
            displayName = sourceConversation.displayName + " (fork)",
            aiProvider = sourceConversation.aiProvider,
            modelName = sourceConversation.modelName,
            currentThread = newThread.id,
            createdAt = now,
            updatedAt = now,
        )
        
        conversationRepo.create(newConversation)
        
        val sourceMessages = threadMessageRepo.getMessagesByThread(sourceConversation.currentThread)
        val sourceLinks = threadMessageRepo.getByThread(sourceConversation.currentThread)
        
        val messageIdMap = mutableMapOf<Conversation.Message.Id, Conversation.Message.Id>()
        
        for (message in sourceMessages) {
            val newMessageId = Conversation.Message.Id(uuid7())
            messageIdMap[message.id] = newMessageId
            
            val newMessage = message.copy(
                id = newMessageId,
                conversationId = newConversationId,
                createdAt = now
            )
            messageRepo.save(newMessage)
        }
        
        val newLinks = sourceLinks.map { link ->
            link.copy(
                threadId = newThread.id,
                messageId = messageIdMap[link.messageId]!!
            )
        }
        
        threadMessageRepo.addBatch(newLinks)
        
        log.debug("Forked conversation $conversationId to ${newConversation.id}")
        
        return newConversation
    }

    /**
     * Appends message to current thread.
     *
     * Adds message to end of thread's message sequence, updates thread timestamp.
     * This is the only operation that modifies existing thread (append-only).
     *
     * @param conversationId conversation to append to
     * @param message message to append (must have matching conversationId)
     * @return updated conversation
     * @throws IllegalArgumentException if message conversationId doesn't match
     * @throws IllegalStateException if conversation doesn't exist
     */
    suspend fun addMessage(
        conversationId: Conversation.Id,
        message: Conversation.Message
    ): Conversation? {
        require(message.conversationId == conversationId) {
            "Message conversationId mismatch"
        }

        messageRepo.save(message)

        val conversation = conversationRepo.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")

        val currentThread = threadRepo.findById(conversation.currentThread)!!

        val lastPosition = threadMessageRepo.getMaxPosition(currentThread.id) ?: -1

        threadMessageRepo.add(currentThread.id, message.id, position = lastPosition + 1)

        threadRepo.updateTimestamp(currentThread.id, Clock.System.now())

        log.debug("Appended message ${message.id} to thread ${currentThread.id} at position ${lastPosition + 1}")

        return conversationRepo.findById(conversationId)
    }

    /**
     * Loads messages from current thread in order.
     *
     * @param conversationId conversation to query
     * @return ordered list of messages in current thread
     * @throws IllegalStateException if conversation doesn't exist
     */
    suspend fun loadCurrentMessages(conversationId: Conversation.Id): List<Conversation.Message> {
        val conversation = conversationRepo.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")

        return threadMessageRepo.getMessagesByThread(conversation.currentThread)
    }

    /**
     * Edits message by creating new thread with updated message.
     *
     * Creates new message with updated content, creates new thread with all
     * messages from current thread but substitutes edited message.
     * Original thread preserved for history/undo.
     *
     * @param conversationId conversation containing message
     * @param messageId message to edit
     * @param newContent updated content items
     * @return updated conversation with new current thread
     * @throws IllegalStateException if conversation doesn't exist
     * @throws IllegalArgumentException if message not found in current thread
     */
    suspend fun editMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id,
        newContent: List<Conversation.Message.ContentItem>
    ): Conversation? {
        val conversation = conversationRepo.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")

        val currentThreadId = conversation.currentThread
        val currentThread = threadRepo.findById(currentThreadId)!!
        val messages = threadMessageRepo.getMessagesByThread(currentThreadId)
        val links = threadMessageRepo.getByThread(currentThreadId)

        val targetMessage = messages.find { it.id == messageId }
            ?: throw IllegalArgumentException("Message $messageId not found in thread $currentThreadId")

        val editedMessage = Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            originalIds = listOf(messageId),
            role = targetMessage.role,
            content = newContent,
            instructions = targetMessage.instructions,
            createdAt = Clock.System.now()
        )

        messageRepo.save(editedMessage)

        val newThread = Conversation.Thread(
            id = Conversation.Thread.Id(uuid7()),
            conversationId = conversationId,
            originalThread = currentThreadId,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )

        threadRepo.save(newThread)

        val newLinks = links.map { link ->
            if (link.messageId == messageId) {
                link.copy(threadId = newThread.id, messageId = editedMessage.id)
            } else {
                link.copy(threadId = newThread.id)
            }
        }

        threadMessageRepo.addBatch(newLinks)

        conversationRepo.updateCurrentThread(conversationId, newThread.id)

        log.debug("Edited message $messageId, created new thread ${newThread.id}")

        return conversationRepo.findById(conversationId)
    }

    /**
     * Deletes single message by creating new thread without it.
     *
     * Creates new thread containing all messages except deleted one,
     * reindexes positions sequentially. Original thread preserved for history/undo.
     *
     * @param conversationId conversation containing message
     * @param messageId message to delete
     * @return updated conversation with new current thread
     * @throws IllegalStateException if conversation doesn't exist
     * @throws IllegalArgumentException if message not found in current thread
     */
    suspend fun deleteMessage(
        conversationId: Conversation.Id,
        messageId: Conversation.Message.Id
    ): Conversation? {
        val conversation = conversationRepo.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")

        val currentThreadId = conversation.currentThread
        val currentThread = threadRepo.findById(currentThreadId)!!
        val messages = threadMessageRepo.getMessagesByThread(currentThreadId)
        val links = threadMessageRepo.getByThread(currentThreadId)

        val targetMessage = messages.find { it.id == messageId }
            ?: throw IllegalArgumentException("Message $messageId not found in thread $currentThreadId")

        val newThread = Conversation.Thread(
            id = Conversation.Thread.Id(uuid7()),
            conversationId = conversationId,
            originalThread = currentThreadId,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )

        threadRepo.save(newThread)

        val newLinks = links
            .filter { it.messageId != messageId }
            .mapIndexed { index, link ->
                link.copy(threadId = newThread.id, position = index)
            }

        threadMessageRepo.addBatch(newLinks)

        conversationRepo.updateCurrentThread(conversationId, newThread.id)

        log.debug("Deleted message $messageId, created new thread ${newThread.id}")

        return conversationRepo.findById(conversationId)
    }

    /**
     * Deletes multiple messages by creating new thread without them.
     *
     * Creates new thread containing all messages except deleted ones,
     * reindexes positions sequentially. Original thread preserved for history/undo.
     *
     * @param conversationId conversation containing messages
     * @param messageIds list of message IDs to delete (must not be empty)
     * @return updated conversation with new current thread
     * @throws IllegalArgumentException if messageIds is empty or some messages not found
     * @throws IllegalStateException if conversation doesn't exist
     */
    suspend fun deleteMessages(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>
    ): Conversation? {
        require(messageIds.isNotEmpty()) {
            "Need at least 1 message to delete"
        }

        val conversation = conversationRepo.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")

        val currentThreadId = conversation.currentThread
        val currentThread = threadRepo.findById(currentThreadId)!!
        val messages = threadMessageRepo.getMessagesByThread(currentThreadId)
        val links = threadMessageRepo.getByThread(currentThreadId)

        val targetMessages = messages.filter { it.id in messageIds }
        if (targetMessages.size != messageIds.size) {
            throw IllegalArgumentException("Some messages not found in thread $currentThreadId")
        }

        val newThread = Conversation.Thread(
            id = Conversation.Thread.Id(uuid7()),
            conversationId = conversationId,
            originalThread = currentThreadId,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )

        threadRepo.save(newThread)

        val newLinks = links
            .filter { it.messageId !in messageIds }
            .mapIndexed { index, link ->
                link.copy(threadId = newThread.id, position = index)
            }

        threadMessageRepo.addBatch(newLinks)

        conversationRepo.updateCurrentThread(conversationId, newThread.id)

        log.debug("Deleted ${messageIds.size} message(s), created new thread ${newThread.id}")

        return conversationRepo.findById(conversationId)
    }

    /**
     * Squashes multiple messages into single consolidated message.
     *
     * Creates new message with squashed content, creates new thread where
     * squashed messages are replaced with single consolidated message at
     * position of last squashed message. Original thread preserved for history/undo.
     *
     * This is for manual concatenation - AI-powered squashing should use
     * SquashOperationRepository for tracking prompt/model/provenance.
     *
     * @param conversationId conversation containing messages
     * @param messageIds list of message IDs to squash (minimum 2 required)
     * @param squashedContent content for consolidated message
     * @return updated conversation with new current thread
     * @throws IllegalArgumentException if fewer than 2 messages or some messages not found
     * @throws IllegalStateException if conversation doesn't exist
     */
    suspend fun squashMessages(
        conversationId: Conversation.Id,
        messageIds: List<Conversation.Message.Id>,
        squashedContent: List<Conversation.Message.ContentItem>
    ): Conversation? {
        require(messageIds.size >= 2) {
            "Need at least 2 messages to squash"
        }

        val conversation = conversationRepo.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")

        val currentThreadId = conversation.currentThread
        val currentThread = threadRepo.findById(currentThreadId)!!
        val messages = threadMessageRepo.getMessagesByThread(currentThreadId)
        val links = threadMessageRepo.getByThread(currentThreadId)

        val targetMessages = messages.filter { it.id in messageIds }
        if (targetMessages.size != messageIds.size) {
            throw IllegalArgumentException("Some messages not found in thread $currentThreadId")
        }

        val lastMessageId = links.last { it.messageId in messageIds }.messageId

        val squashedMessage = Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            originalIds = messageIds,
            role = Conversation.Message.Role.USER,
            content = squashedContent,
            instructions = emptyList(),
            createdAt = Clock.System.now()
        )

        messageRepo.save(squashedMessage)

        val newThread = Conversation.Thread(
            id = Conversation.Thread.Id(uuid7()),
            conversationId = conversationId,
            originalThread = currentThreadId,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )

        threadRepo.save(newThread)

        var positionCounter = 0
        val newLinks = links.mapNotNull { link ->
            when {
                link.messageId == lastMessageId -> {
                    link.copy(threadId = newThread.id, messageId = squashedMessage.id, position = positionCounter++)
                }
                link.messageId in messageIds -> {
                    null
                }
                else -> {
                    link.copy(threadId = newThread.id, position = positionCounter++)
                }
            }
        }

        threadMessageRepo.addBatch(newLinks)

        conversationRepo.updateCurrentThread(conversationId, newThread.id)

        log.debug("Squashed ${messageIds.size} messages, created new thread ${newThread.id}")

        return conversationRepo.findById(conversationId)
    }
}
