package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.repository.ThreadRepository
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.DeclarativeStateChangePublisher
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.domain.service.NoOpDeclarativeStateChangePublisher
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.service.UserConversationTabLayoutService
import klog.KLoggers
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Clock
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
    private val projectAccessService: ProjectAccessService,
    private val agentService: AgentDomainService,
    private val toolCallPairingService: ToolCallPairingService,
    private val conversationTabLayoutService: UserConversationTabLayoutService,
    private val conversationUnreadStateService: ConversationUnreadStateApplicationService,
    private val artifactService: ConversationArtifactApplicationService,
    private val suggestedRepliesGenerationService: SuggestedRepliesGenerationService,
    private val settingsProvider: SettingsProvider,
    private val stateChanges: DeclarativeStateChangePublisher = NoOpDeclarativeStateChangePublisher,
) : ConversationDomainService, ConversationRuntimeMessageAppender {
    private val log = KLoggers.logger(this)

    /**
     * Creates new conversation with initial empty thread.
     *
     * Validates the project, then creates a conversation with an empty initial thread.
     *
     * @param participants users and agents initially connected to the conversation
     * @param displayName optional conversation title (empty uses auto-generated name)
     * @return created conversation with new thread
     */
    @Transactional
    override suspend fun create(
        projectId: Project.Id,
        participants: Set<Conversation.Participant>,
        displayName: String,
    ): Conversation {
        val project = projectService.findById(projectId)
            ?: error("Project not found: ${projectId.value}")
        validateParticipants(project.id, participants)
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
            participants = participants,
            displayName = displayName,
            currentThread = initialThread.id,
            createdAt = now,
            updatedAt = now,
        )

        val createdConversation = conversationRepo.create(conversation)
        threadRepo.save(initialThread)
        return createdConversation.also(::publishConversationList)
    }

    /**
     * Finds conversation by unique identifier.
     *
     * @param id conversation identifier
     * @return conversation if found, null otherwise
     */
    override suspend fun findById(id: Conversation.Id): Conversation? =
        conversationRepo.findById(id)

    override suspend fun regenerateSuggestedReplies(
        conversationId: Conversation.Id,
        sourceMessageId: Conversation.Message.Id,
        actorUserId: User.Id?,
    ): List<String> {
        val conversation = findById(conversationId)
            ?: error("Conversation not found: ${conversationId.value}")
        val messages = loadCurrentMessages(conversationId)
        val sourceMessage = messages.firstOrNull { it.id == sourceMessageId }
            ?: error("Suggested reply source message not found: ${sourceMessageId.value}")
        require(sourceMessage.role == Conversation.Message.Role.ASSISTANT) {
            "Suggested replies require an assistant source message"
        }
        val sourceAgentId = (sourceMessage.author as? Conversation.Message.Author.Agent)?.agentDefinitionId
            ?: error("Suggested reply source message has no agent author")
        val mode = settingsProvider.userProfile.suggestedRepliesSettings.mode
        require(mode != UserProfile.SuggestedRepliesSettings.Mode.DISABLED) {
            "Suggested replies are disabled"
        }
        val runtimeSelection = when (mode) {
            UserProfile.SuggestedRepliesSettings.Mode.INLINE -> {
                val agent = agentService.findById(sourceAgentId)
                    ?: error("Agent not found: ${sourceAgentId.value}")
                agent.runtimeSelection
            }

            UserProfile.SuggestedRepliesSettings.Mode.SEPARATE_RUNTIME ->
                suggestedRepliesGenerationService.requireConfiguredRuntimeSelection()

            UserProfile.SuggestedRepliesSettings.Mode.DISABLED -> error("Unreachable")
        }
        return suggestedRepliesGenerationService.generate(
            conversation = conversation,
            messages = messages.takeWhile { it.id != sourceMessageId } + sourceMessage,
            sourceMessage = sourceMessage,
            agentDefinitionId = sourceAgentId,
            runtimeSelection = runtimeSelection,
            actorUserId = actorUserId,
            usageId = "suggested-replies:${sourceMessageId.value}:${uuid7()}",
        )
    }

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
        val conversation = conversationRepo.findById(id)
            ?: error("Conversation not found: ${id.value}")
        conversationTabLayoutService.removeConversation(id)
        conversationRepo.delete(id)
        publishConversationList(conversation)
        publishUnreadState(
            conversation.participants
                .filterIsInstance<Conversation.Participant.User>()
                .map { it.userId },
        )
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
        return conversationRepo.findById(conversationId).also { it?.let(::publishConversationList) }
    }

    @Transactional
    override suspend fun updateParticipants(
        conversationId: Conversation.Id,
        participants: Set<Conversation.Participant>,
    ): Conversation? {
        val conversation = conversationRepo.findById(conversationId) ?: return null
        validateParticipants(conversation.projectId, participants)
        conversationRepo.updateParticipants(conversationId, participants)
        val previousUserIds = conversation.participants
            .filterIsInstance<Conversation.Participant.User>()
            .mapTo(mutableSetOf()) { it.userId }
        val updatedUserIds = participants
            .filterIsInstance<Conversation.Participant.User>()
            .mapTo(mutableSetOf()) { it.userId }
        publishUnreadState(previousUserIds xor updatedUserIds)
        return conversationRepo.findById(conversationId).also { it?.let(::publishConversationList) }
    }

    private suspend fun validateParticipants(
        projectId: Project.Id,
        participants: Set<Conversation.Participant>,
    ) {
        require(participants.any { it is Conversation.Participant.User }) {
            "Conversation must have at least one user participant"
        }
        participants.forEach { participant ->
            when (participant) {
                is Conversation.Participant.User ->
                    projectAccessService.requirePermission(participant.userId, projectId, ProjectPermission.READ)
                is Conversation.Participant.Agent ->
                    requireAgentAvailableToProject(participant.agentDefinitionId, projectId)
            }
        }
    }

    private suspend fun requireAgentAvailableToProject(
        agentDefinitionId: AgentDefinition.Id,
        projectId: Project.Id,
    ) {
        val agent = agentService.findById(agentDefinitionId)
            ?: error("Agent not found: ${agentDefinitionId.value}")
        require(agent.type is AgentDefinition.Type.Global || agent.projectId == projectId) {
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
            participants = sourceConversation.participants,
            displayName = sourceConversation.displayName + " (fork)",
            currentThread = newThread.id,
            createdAt = now,
            updatedAt = now,
        )
        
        conversationRepo.create(newConversation)
        threadRepo.save(newThread)
        
        val sourceLinks = threadMessageRepo.getByThread(sourceConversation.currentThread)
        val sourceMessagesById = messageRepo.findByIds(sourceLinks.map { it.messageId }).associateBy { it.id }
        val sourceMessages = artifactService.cloneReferences(
            sourceConversationId = sourceConversation.id,
            targetConversation = newConversation,
            messages = sourceLinks.map { link ->
                sourceMessagesById[link.messageId]
                    ?: error("Message ${link.messageId.value} disappeared while forking conversation")
            },
        )
        
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
        
        return newConversation.also(::publishConversationList)
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
    override suspend fun appendRuntimeMessage(
        conversationId: Conversation.Id,
        message: Conversation.Message
    ): Conversation? {
        require(message.conversationId == conversationId) {
            "Message conversationId mismatch"
        }

        val conversation = conversationRepo.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")
        artifactService.validateReferences(conversationId, message.content)
        saveMessageIfAbsent(message)

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
        conversationUnreadStateService.recordMessage(conversation, message)

        log.debug("Appended message ${message.id} to thread ${currentThread.id} at position ${lastPosition + 1}")

        return conversationRepo.findById(conversationId).also { it?.let(::publishConversationList) }
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
            replyTo == other.replyTo &&
            role == other.role &&
            author == other.author &&
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
    internal suspend fun editRuntimeHistory(
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
        artifactService.validateReferences(conversationId, newContent)

        val editedMessage = Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            originalIds = listOf(messageId),
            role = targetMessage.role,
            author = targetMessage.author,
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

        return conversationRepo.findById(conversationId).also { it?.let(::publishConversationList) }
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
    internal suspend fun deleteRuntimeHistory(
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

        return conversationRepo.findById(conversationId).also { it?.let(::publishConversationList) }
    }

    private fun publishConversationList(conversation: Conversation) {
        stateChanges.publish(DeclarativeStateKey.projectConversations(conversation.projectId))
    }

    private fun publishUnreadState(userIds: Iterable<User.Id>) {
        stateChanges.publish(*userIds.map(DeclarativeStateKey::conversationUnreadState).toTypedArray())
    }
}

private infix fun <T> Set<T>.xor(other: Set<T>): Set<T> = (this - other) + (other - this)
