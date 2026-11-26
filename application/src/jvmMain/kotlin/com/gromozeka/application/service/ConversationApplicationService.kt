package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationCompactionIndex
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.repository.ConversationMessageBatchRepository
import com.gromozeka.domain.repository.samePersistentBodyAs
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.ThreadMessageRepository
import com.gromozeka.domain.repository.ThreadMessageReplacement
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
    private val messageBatchRepo: ConversationMessageBatchRepository,
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
    ): Conversation = createConversation(projectId, participants, displayName, pinsFrom = null)

    override suspend fun createWithCopiedPins(
        projectId: Project.Id,
        participants: Set<Conversation.Participant>,
        sourceConversationId: Conversation.Id,
        displayName: String,
    ): Conversation = createConversation(projectId, participants, displayName, sourceConversationId)

    private suspend fun createConversation(
        projectId: Project.Id,
        participants: Set<Conversation.Participant>,
        displayName: String,
        pinsFrom: Conversation.Id?,
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
            autoRespondAgentIds = Conversation.defaultAutoRespondAgentIds(participants),
            displayName = displayName,
            currentThread = initialThread.id,
            createdAt = now,
            updatedAt = now,
        )

        val createdConversation = if (pinsFrom == null) conversationRepo.create(conversation)
            else createPinCopy(conversation, pinsFrom)
        threadRepo.save(initialThread)
        if (pinsFrom != null) stateChanges.publish(DeclarativeStateKey.conversationPins(createdConversation.id))
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
        require(conversation.externalChannel == null) { "Suggested replies are unavailable in an external channel" }
        require(threadMessageRepo.contains(conversation.currentThread, sourceMessageId)) {
            "Suggested reply source message not found: ${sourceMessageId.value}"
        }
        val sourceMessage = threadMessageRepo.compactionBodies(
            conversation.currentThread,
            listOf(sourceMessageId),
        ).single()
        require(sourceMessage.role == Conversation.Message.Role.ASSISTANT) {
            "Suggested replies require an assistant source message"
        }
        val sourceAgentId = (sourceMessage.author as? Conversation.Message.Author.Agent)?.agentDefinitionId
            ?: error("Suggested reply source message has no agent author")
        val settings = settingsProvider.userProfile.suggestedRepliesSettings
        val mode = settings.mode
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
        val suggestionContext = when (settings.contextMode) {
            UserProfile.SuggestedRepliesSettings.ContextMode.RECENT_MESSAGES ->
                SuggestedRepliesGenerationService.Context.Recent(
                    threadMessageRepo.recentVisibleMessageTextThrough(
                        conversation.currentThread,
                        sourceMessageId,
                        SUGGESTED_REPLIES_RECENT_CONTEXT_MESSAGES,
                        SUGGESTED_REPLIES_RECENT_MESSAGE_CHARACTERS,
                    )
                )
            UserProfile.SuggestedRepliesSettings.ContextMode.FULL_THREAD ->
                SuggestedRepliesGenerationService.Context.Full(
                    threadMessageRepo.getContextMessagesThrough(
                        conversation.currentThread,
                        sourceMessageId,
                        suggestedRepliesGenerationService.nativeContextProvider(runtimeSelection),
                    ).messages
                )
        }
        return suggestedRepliesGenerationService.generate(
            conversation = conversation,
            context = suggestionContext,
            sourceMessage = sourceMessage,
            agentDefinitionId = sourceAgentId,
            runtimeSelection = runtimeSelection,
            actorUserId = actorUserId,
            usageId = "suggested-replies:${sourceMessageId.value}:${uuid7()}",
            settings = settings,
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
        var previousUserIds = emptySet<User.Id>()
        val updated = conversationRepo.updateParticipantSettings(conversationId) { current ->
            previousUserIds = current.participants.filterIsInstance<Conversation.Participant.User>()
                .mapTo(mutableSetOf()) { it.userId }
            current.withParticipants(participants)
        } ?: return null
        val updatedUserIds = participants
            .filterIsInstance<Conversation.Participant.User>()
            .mapTo(mutableSetOf()) { it.userId }
        publishUnreadState(previousUserIds xor updatedUserIds)
        return updated.also(::publishConversationList)
    }

    @Transactional
    override suspend fun updateAutoRespondAgentIds(
        conversationId: Conversation.Id,
        agentDefinitionIds: Set<AgentDefinition.Id>,
    ): Conversation? {
        return conversationRepo.updateParticipantSettings(conversationId) { current ->
            current.copy(autoRespondAgentIds = agentDefinitionIds)
        }.also { it?.let(::publishConversationList) }
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
            autoRespondAgentIds = sourceConversation.autoRespondAgentIds,
            displayName = sourceConversation.displayName + " (fork)",
            currentThread = newThread.id,
            createdAt = now,
            updatedAt = now,
        )
        
        createPinCopy(newConversation, sourceConversation.id)
        threadRepo.save(newThread)
        
        // Allocate every destination ID before copying bodies so checkpoint references can
        // be remapped without ever materializing the unbounded archive in one collection.
        val messageIdMap = mutableMapOf<Conversation.Message.Id, Conversation.Message.Id>()
        var afterPosition = -1
        while (true) {
            val page = threadMessageRepo.getLinkPage(sourceConversation.currentThread, afterPosition, ARCHIVE_PAGE_SIZE)
            if (page.isEmpty()) break
            page.forEach { link ->
                check(messageIdMap.put(link.messageId, Conversation.Message.Id(uuid7())) == null) {
                    "Duplicate message ${link.messageId.value} while forking conversation"
                }
            }
            afterPosition = page.last().position
        }

        val artifactReplacements = mutableMapOf<Artifact.Id, Artifact.Reference>()
        afterPosition = -1
        while (true) {
            val page = threadMessageRepo.getMessagePage(sourceConversation.currentThread, afterPosition, ARCHIVE_PAGE_SIZE)
            if (page.isEmpty()) break
            val sourceMessages = artifactService.cloneReferences(
                sourceConversationId = sourceConversation.id,
                targetConversation = newConversation,
                messages = page.map { it.message },
                replacements = artifactReplacements,
            )
            val newMessages = sourceMessages.map { message ->
                message.copy(
                    id = messageIdMap.getValue(message.id),
                    conversationId = newConversationId,
                    content = message.content.map { item ->
                        if (item is Conversation.Message.ContentItem.ContextCompactionResult) item.copy(
                            sourceMessageIds = item.sourceMessageIds.map { messageIdMap[it] ?: it },
                            sourceBoundaryMessageId = item.sourceBoundaryMessageId?.let { messageIdMap[it] ?: it },
                            invalidatedReplayMessageIds = item.invalidatedReplayMessageIds.map { messageIdMap[it] ?: it },
                            historyTransition = null,
                        ) else item
                    },
                )
            }
            messageRepo.saveBatch(newMessages)
            threadMessageRepo.addBatch(page.zip(newMessages) { entry, message ->
                com.gromozeka.domain.repository.ThreadMessageLink(newThread.id, message.id, entry.position)
            })
            afterPosition = page.last().position
        }
        
        log.debug("Forked conversation $conversationId to ${newConversation.id}")
        
        stateChanges.publish(DeclarativeStateKey.conversationPins(newConversation.id))
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
    override suspend fun appendRuntimeMessage(conversationId: Conversation.Id, message: Conversation.Message): Conversation? {
        appendRuntimeMessages(conversationId, listOf(message))
        return conversationRepo.findById(conversationId)
    }

    override suspend fun appendRuntimeMessages(conversationId: Conversation.Id, messages: List<Conversation.Message>): List<Conversation.Message> {
        require(messages.all { it.conversationId == conversationId }) { "Message conversationId mismatch" }
        val conversation = conversationRepo.findById(conversationId) ?: error("Conversation not found: $conversationId")
        artifactService.validateReferences(conversationId, messages.flatMap { it.content })
        val appended = messageBatchRepo.append(conversationId, conversation.currentThread, messages, Clock.System.now())
        if (appended.isNotEmpty()) {
            appended.forEach { conversationUnreadStateService.recordMessage(conversation, it) }
            conversationRepo.findById(conversationId)?.let(::publishConversationList)
        }
        return appended
    }

    @Transactional
    internal suspend fun replaceExternalRuntimeMessage(originalId: Conversation.Message.Id, message: Conversation.Message) {
        val conversation = requireNotNull(conversationRepo.findById(message.conversationId))
        require(conversation.externalChannel != null)
        if (threadMessageRepo.contains(conversation.currentThread, message.id)) return
        val previous = threadMessageRepo.findMessageByIdOrOriginal(conversation.currentThread, originalId)
        if (previous == null) {
            appendRuntimeMessage(conversation.id, message)
            return
        }
        require(previous.role == Conversation.Message.Role.USER && message.role == Conversation.Message.Role.USER)
        val replacement = message.copy(originalIds = (previous.originalIds + previous.id).distinct())
        artifactService.validateReferences(conversation.id, replacement.content)
        saveMessageIfAbsent(replacement)
        val now = Clock.System.now()
        val thread = Conversation.Thread(id = Conversation.Thread.Id(uuid7()), conversationId = conversation.id,
            originalThread = conversation.currentThread, createdAt = now, updatedAt = now)
        threadRepo.save(thread)
        threadMessageRepo.copyWithChanges(
            sourceThreadId = conversation.currentThread,
            targetThreadId = thread.id,
            replacement = ThreadMessageReplacement(previous.id, replacement.id),
        )
        conversationRepo.updateCurrentThread(conversation.id, thread.id)
        publishConversationList(conversation)
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
        val targetMessage = threadMessageRepo.findMessage(currentThreadId, messageId)
            ?: throw IllegalArgumentException("Message $messageId not found in thread $currentThreadId")
        val headers = threadMessageRepo.compactionWindowHeaders(currentThreadId)
        com.gromozeka.domain.model.requireEditableConversationMessage(targetMessage)
        ensureMessagesAreInMutableCompactionWindow(headers, setOf(messageId), "edit")
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

        val invalidated = ConversationCompactionIndex(headers.map { it.node }).dependentSummaryIds(setOf(messageId))
        threadMessageRepo.copyWithChanges(
            sourceThreadId = currentThreadId,
            targetThreadId = newThread.id,
            replacement = ThreadMessageReplacement(messageId, editedMessage.id),
            excludedMessageIds = invalidated,
        )

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
        val selectedIds = messageIds.toSet()
        if (selectedIds.size != messageIds.size || messageIds.any { threadMessageRepo.findMessage(currentThreadId, it) == null }) {
            throw IllegalArgumentException("Some messages not found in thread $currentThreadId")
        }
        val headers = threadMessageRepo.compactionWindowHeaders(currentThreadId)
        ensureMessagesAreInMutableCompactionWindow(headers, selectedIds, "delete")

        val requestedDeletes = toolCallPairingService.includePairedHeaders(headers, selectedIds)
        // Pair expansion must not bypass a full checkpoint's protected prefix.
        ensureMessagesAreInMutableCompactionWindow(headers, requestedDeletes, "delete")
        val allIdsToDelete = requestedDeletes +
            ConversationCompactionIndex(headers.map { it.node }).dependentSummaryIds(requestedDeletes)

        val newThread = Conversation.Thread(
            id = Conversation.Thread.Id(uuid7()),
            conversationId = conversationId,
            originalThread = currentThreadId,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )

        threadRepo.save(newThread)

        threadMessageRepo.copyWithChanges(
            sourceThreadId = currentThreadId,
            targetThreadId = newThread.id,
            excludedMessageIds = allIdsToDelete,
        )

        conversationRepo.updateCurrentThread(conversationId, newThread.id)

        log.debug("Deleted ${messageIds.size} selected message(s), ${allIdsToDelete.size} including pairs and dependent summaries; created new thread ${newThread.id}")

        return conversationRepo.findById(conversationId).also { it?.let(::publishConversationList) }
    }

    private suspend fun createPinCopy(conversation: Conversation, sourceId: Conversation.Id): Conversation = try {
        conversationRepo.createWithCopiedPins(conversation, sourceId)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: com.gromozeka.domain.model.ConversationPinException) {
        throw error
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Conversation pin copying requires an available source in the same project")
    } catch (_: Exception) {
        throw com.gromozeka.domain.model.ConversationPinOperationFailedException()
    }

    private fun publishConversationList(conversation: Conversation) {
        stateChanges.publish(DeclarativeStateKey.projectConversations(conversation.projectId))
    }

    private fun publishUnreadState(userIds: Iterable<User.Id>) {
        stateChanges.publish(*userIds.map(DeclarativeStateKey::conversationUnreadState).toTypedArray())
    }
}

private infix fun <T> Set<T>.xor(other: Set<T>): Set<T> = (this - other) + (other - this)

private const val ARCHIVE_PAGE_SIZE = 1_000
