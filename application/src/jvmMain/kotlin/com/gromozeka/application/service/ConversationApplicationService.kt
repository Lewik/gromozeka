package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ConversationTabLayoutService
import com.gromozeka.domain.service.ProjectDomainService
import klog.KLoggers
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.repository.ThreadRepository
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
@Service
class ConversationApplicationService(
    private val conversationRepo: ConversationRepository,
    private val threadRepo: ThreadRepository,
    private val messageRepo: MessageRepository,
    private val threadMessageRepo: ThreadMessageRepository,
    private val projectService: ProjectDomainService,
    private val agentService: AgentDomainService,
    private val toolCallPairingService: ToolCallPairingService,
    private val conversationTabLayoutService: ConversationTabLayoutService,
) : ConversationDomainService {
    private val log = KLoggers.logger(this)

    /**
     * Creates new conversation with initial empty thread.
     *
     * Validates the project, then creates a conversation with an empty initial thread.
     *
     * @param displayName optional conversation title (empty uses auto-generated name)
     * @param agentDefinitionId agent definition to use for this conversation
     * @return created conversation with new thread
     */
    @Transactional
    override suspend fun create(
        projectId: Project.Id,
        displayName: String,
        agentDefinitionId: com.gromozeka.domain.model.AgentDefinition.Id
    ): Conversation {
        val project = projectService.findById(projectId)
            ?: error("Project not found: ${projectId.value}")
        requireAgentAvailableToProject(agentDefinitionId, project.id)
        val now = Clock.System.now()

        val conversationId = Conversation.Id(uuid7())

        val initialThread = Conversation.Thread(
            id = Conversation.Thread.Id(uuid7()),
            conversationId = conversationId,
            originalThread = null,
            createdAt = now,
            updatedAt = now,
        )

        val conversation = Conversation(
            id = conversationId,
            projectId = project.id,
            agentDefinitionId = agentDefinitionId,
            displayName = displayName,
            currentThread = initialThread.id,
            createdAt = now,
            updatedAt = now,
        )

        val createdConversation = conversationRepo.create(conversation)
        threadRepo.save(initialThread)
        return createdConversation
    }

    /**
     * Finds conversation by unique identifier.
     *
     * @param id conversation identifier
     * @return conversation if found, null otherwise
     */
    override suspend fun findById(id: Conversation.Id): Conversation? =
        conversationRepo.findById(id)

    override suspend fun getProject(conversationId: Conversation.Id): Project {
        val conversation = findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        return projectService.findById(conversation.projectId)
            ?: throw IllegalStateException("Project not found: ${conversation.projectId}")
    }

    /**
     * Finds all conversations in project.
     *
     * @param projectId logical project identifier
     * @return list of conversations (empty if project doesn't exist or has no conversations)
     */
    override suspend fun findByProject(projectId: Project.Id): List<Conversation> =
        conversationRepo.findByProject(projectId)

    /**
     * Deletes conversation and all associated data.
     *
     * Cascades to threads, thread-message links, and may remove orphaned messages
     * (implementation-specific).
     *
     * @param id conversation identifier
     */
    @Transactional
    override suspend fun delete(id: Conversation.Id) {
        conversationTabLayoutService.close(id)
        conversationRepo.delete(id)
    }

    /**
     * Updates conversation display name.
     *
     * @param conversationId conversation identifier
     * @param displayName new display name
     * @return updated conversation if exists, null otherwise
     */
    @Transactional
    override suspend fun updateDisplayName(
        conversationId: Conversation.Id,
        displayName: String
    ): Conversation? {
        require(displayName.length <= 255) { "Conversation display name must not exceed 255 characters" }
        conversationRepo.updateDisplayName(conversationId, displayName)
        return conversationRepo.findById(conversationId)
    }

    @Transactional
    override suspend fun updateAgentDefinition(
        conversationId: Conversation.Id,
        agentDefinitionId: AgentDefinition.Id,
    ): Conversation? {
        val conversation = conversationRepo.findById(conversationId) ?: return null
        requireAgentAvailableToProject(agentDefinitionId, conversation.projectId)
        conversationRepo.updateAgentDefinition(conversationId, agentDefinitionId)
        return conversationRepo.findById(conversationId)
    }

    private suspend fun requireAgentAvailableToProject(
        agentDefinitionId: AgentDefinition.Id,
        projectId: Project.Id,
    ) {
        val agent = agentService.findById(agentDefinitionId)
            ?: error("Agent not found: ${agentDefinitionId.value}")
        require(agent.type is AgentDefinition.Type.Builtin || agent.projectId == projectId) {
            "Agent ${agentDefinitionId.value} does not belong to project ${projectId.value}"
        }
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
    @Transactional
    override suspend fun fork(conversationId: Conversation.Id): Conversation {
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
        
        val newConversation = Conversation(
            id = newConversationId,
            projectId = sourceConversation.projectId,
            agentDefinitionId = sourceConversation.agentDefinitionId,
            displayName = sourceConversation.displayName + " (fork)",
            currentThread = newThread.id,
            createdAt = now,
            updatedAt = now,
        )
        
        conversationRepo.create(newConversation)
        threadRepo.save(newThread)
        
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
    @Transactional
    override suspend fun addMessage(
        conversationId: Conversation.Id,
        message: Conversation.Message
    ): Conversation? {
        require(message.conversationId == conversationId) {
            "Message conversationId mismatch"
        }

        saveMessageIfAbsent(message)

        val conversation = conversationRepo.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")

        val currentThread = threadRepo.findById(conversation.currentThread)!!
        val existingLinks = threadMessageRepo.getByThread(currentThread.id)
        if (existingLinks.any { it.messageId == message.id }) {
            log.debug("Message ${message.id} is already linked to thread ${currentThread.id}")
            return conversationRepo.findById(conversationId)
        }

        val lastPosition = threadMessageRepo.getMaxPosition(currentThread.id) ?: -1

        runCatching {
            threadMessageRepo.add(currentThread.id, message.id, position = lastPosition + 1)
        }.onFailure { error ->
            val linkedAfterRace = threadMessageRepo.getByThread(currentThread.id).any { it.messageId == message.id }
            if (!linkedAfterRace) {
                throw error
            }
        }

        threadRepo.updateTimestamp(currentThread.id, Clock.System.now())
        conversationRepo.touch(conversationId)

        log.debug("Appended message ${message.id} to thread ${currentThread.id} at position ${lastPosition + 1}")

        return conversationRepo.findById(conversationId)
    }

    private suspend fun saveMessageIfAbsent(message: Conversation.Message) {
        val existing = messageRepo.findById(message.id)
        if (existing != null) {
            require(existing.samePersistentBodyAs(message)) {
                "Message id collision with different content: ${message.id.value}"
            }
            return
        }

        runCatching {
            messageRepo.save(message)
        }.onFailure { error ->
            val savedAfterRace = messageRepo.findById(message.id)
            if (savedAfterRace == null || !savedAfterRace.samePersistentBodyAs(message)) {
                throw error
            }
        }
    }

    private fun Conversation.Message.samePersistentBodyAs(other: Conversation.Message): Boolean =
        conversationId == other.conversationId &&
            originalIds == other.originalIds &&
            squashOperationId == other.squashOperationId &&
            replyTo == other.replyTo &&
            role == other.role &&
            content == other.content &&
            instructions == other.instructions &&
            providerMetadata == other.providerMetadata &&
            error == other.error

    /**
     * Loads messages from current thread in order.
     *
     * @param conversationId conversation to query
     * @return ordered list of messages in current thread
     * @throws IllegalStateException if conversation doesn't exist
     */
    override suspend fun loadCurrentMessages(conversationId: Conversation.Id): List<Conversation.Message> {
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
    @Transactional
    override suspend fun editMessage(
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
        ensureMessagesAreNotCoveredByCompaction(messages, setOf(messageId), "edit")

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
    @Transactional
    override suspend fun deleteMessages(
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
        ensureMessagesAreNotCoveredByCompaction(messages, messageIds.toSet(), "delete")

        // Build pairing map to identify paired ToolCalls/ToolResults
        val pairingMap = toolCallPairingService.buildPairingMap(messages)
        
        // Collect all ToolCall IDs from deleted messages (both from ToolCall and ToolResult content)
        // These are IDs of tool calls that will be removed from thread
        val deletingToolCallIds = targetMessages
            .flatMap { it.content }
            .flatMap { content ->
                when (content) {
                    is Conversation.Message.ContentItem.ToolCall -> 
                        if (pairingMap[content.id]?.toolResult != null) listOf(content.id) else emptyList()
                    is Conversation.Message.ContentItem.ToolResult -> 
                        if (pairingMap[content.toolUseId]?.toolCall != null) listOf(content.toolUseId) else emptyList()
                    else -> emptyList()
                }
            }
            .toSet()
        
        // Find messages containing the paired ToolCalls/ToolResults that must also be deleted
        val pairedMessageIds = messages
            .filter { it.id !in messageIds }
            .filter { message ->
                message.content.any { content ->
                    when (content) {
                        is Conversation.Message.ContentItem.ToolResult -> content.toolUseId in deletingToolCallIds
                        is Conversation.Message.ContentItem.ToolCall -> content.id in deletingToolCallIds
                        else -> false
                    }
                }
            }
            .map { it.id }
            .toSet()
        
        val allIdsToDelete = messageIds.toSet() + pairedMessageIds

        val newThread = Conversation.Thread(
            id = Conversation.Thread.Id(uuid7()),
            conversationId = conversationId,
            originalThread = currentThreadId,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )

        threadRepo.save(newThread)

        val newLinks = links
            .filter { it.messageId !in allIdsToDelete }
            .mapIndexed { index, link ->
                link.copy(threadId = newThread.id, position = index)
            }

        threadMessageRepo.addBatch(newLinks)

        conversationRepo.updateCurrentThread(conversationId, newThread.id)

        log.debug("Deleted ${messageIds.size} message(s) + ${pairedMessageIds.size} paired, created new thread ${newThread.id}")

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
    @Transactional
    override suspend fun squashMessages(
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
        ensureMessagesAreNotCoveredByCompaction(messages, messageIds.toSet(), "squash")

        val lastMessageId = links.last { it.messageId in messageIds }.messageId

        val compactionMessage = Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            role = Conversation.Message.Role.ASSISTANT,
            content = listOf(
                Conversation.Message.ContentItem.ContextCompactionResult(
                    payload = Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary(
                        text = squashedContent.toReadableCompactionText(),
                    ),
                    origin = Conversation.Message.ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                    sourceMessageIds = messageIds,
                )
            ),
            instructions = emptyList(),
            createdAt = Clock.System.now()
        )

        messageRepo.save(compactionMessage)

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
                    link.copy(threadId = newThread.id, messageId = compactionMessage.id, position = positionCounter++)
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

    private fun ensureMessagesAreNotCoveredByCompaction(
        messages: List<Conversation.Message>,
        targetMessageIds: Set<Conversation.Message.Id>,
        operation: String,
    ) {
        val compactionIndex = messages.indexOfLast { message ->
            message.content.any { it is Conversation.Message.ContentItem.ContextCompactionResult }
        }
        if (compactionIndex < 0) return

        val lockedMessageIds = messages.take(compactionIndex + 1).mapTo(mutableSetOf()) { it.id }
        val lockedTargets = targetMessageIds.intersect(lockedMessageIds)
        require(lockedTargets.isEmpty()) {
            "Cannot $operation message(s) covered by context compaction: ${lockedTargets.joinToString { it.value }}"
        }
    }

    private fun List<Conversation.Message.ContentItem>.toReadableCompactionText(): String {
        val text = mapNotNull { content ->
            when (content) {
                is Conversation.Message.ContentItem.UserMessage -> content.text
                is Conversation.Message.ContentItem.AssistantMessage -> content.structured.fullText
                is Conversation.Message.ContentItem.System -> content.content
                is Conversation.Message.ContentItem.ContextCompactionResult -> content.toReadableCompactionText()
                is Conversation.Message.ContentItem.ToolCall -> "[tool_call:${content.call.name}] ${content.call.input}"
                is Conversation.Message.ContentItem.ToolResult -> "[tool_result:${content.toolName}]"
                is Conversation.Message.ContentItem.Thinking -> null
                is Conversation.Message.ContentItem.ImageItem -> "[image:${content.source.type}]"
                is Conversation.Message.ContentItem.UnknownJson -> content.json.toString()
            }
        }.joinToString("\n").trim()

        require(text.isNotBlank()) { "Context compaction result text must not be blank" }
        return text
    }

    private fun Conversation.Message.ContentItem.ContextCompactionResult.toReadableCompactionText(): String =
        when (val payload = payload) {
            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.ReadableSummary -> payload.text
            is Conversation.Message.ContentItem.ContextCompactionResult.Payload.OpaqueProviderState ->
                "[context_compaction:${providerScope?.provider ?: "unknown"}]"
        }
}
