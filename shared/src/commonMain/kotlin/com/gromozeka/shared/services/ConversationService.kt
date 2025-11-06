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

class ConversationService(
    private val conversationRepo: ConversationRepository,
    private val threadRepo: ThreadRepository,
    private val messageRepo: MessageRepository,
    private val threadMessageRepo: ThreadMessageRepository,
    private val projectService: ProjectService
) {
    private val log = KLoggers.logger(this)

    suspend fun create(
        projectPath: String,
        displayName: String = "",
        aiProvider: String = "CLAUDE_CODE",
        modelName: String = "sonnet"
    ): Conversation {
        val project = projectService.getOrCreate(projectPath)
        val now = Clock.System.now()

        val conversationId = Conversation.Id(uuid7())

        // Create initial empty Thread
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

    suspend fun findById(id: Conversation.Id): Conversation? =
        conversationRepo.findById(id)

    suspend fun getProjectPath(conversationId: Conversation.Id): String? {
        val conversation = findById(conversationId) ?: return null
        val project = projectService.findById(conversation.projectId) ?: return null
        return project.path
    }

    suspend fun findByProject(projectPath: String): List<Conversation> {
        val project = projectService.findByPath(projectPath) ?: return emptyList()
        return conversationRepo.findByProject(project.id)
    }

    suspend fun delete(id: Conversation.Id) {
        conversationRepo.delete(id)
    }

    suspend fun updateDisplayName(
        conversationId: Conversation.Id,
        displayName: String
    ): Conversation? {
        conversationRepo.updateDisplayName(conversationId, displayName)
        return conversationRepo.findById(conversationId)
    }

    /**
     * Добавить сообщение к разговору (mutable append)
     */
    suspend fun addMessage(
        conversationId: Conversation.Id,
        message: Conversation.Message
    ): Conversation? {
        require(message.conversationId == conversationId) {
            "Message conversationId mismatch"
        }

        // 1. Сохранить Message
        messageRepo.save(message)

        // 2. Загрузить conversation
        val conversation = conversationRepo.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")

        // 3. Thread всегда существует
        val currentThread = threadRepo.findById(conversation.currentThread)!!

        // Получить последнюю позицию
        val lastPosition = threadMessageRepo.getMaxPosition(currentThread.id) ?: -1

        // INSERT новую связь
        threadMessageRepo.add(currentThread.id, message.id, position = lastPosition + 1)

        // UPDATE thread.updated_at
        threadRepo.updateTimestamp(currentThread.id, Clock.System.now())

        log.debug("Appended message ${message.id} to thread ${currentThread.id} at position ${lastPosition + 1}")

        return conversationRepo.findById(conversationId)
    }

    /**
     * Загрузить все сообщения текущего треда
     */
    suspend fun loadCurrentMessages(conversationId: Conversation.Id): List<Conversation.Message> {
        val conversation = conversationRepo.findById(conversationId)
            ?: throw IllegalStateException("Conversation not found: $conversationId")

        return threadMessageRepo.getMessagesByThread(conversation.currentThread)
    }

    /**
     * Редактировать сообщение (Copy-on-Write)
     *
     * - Создает новый Thread с копией всех сообщений
     * - Заменяет content целевого сообщения
     * - Устанавливает originalIds = [messageId]
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
     * Удалить сообщение (Copy-on-Write)
     *
     * - Создает новый Thread без удаленного сообщения
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
     * Объединить несколько сообщений в одно (Copy-on-Write)
     *
     * - Создает новое сообщение с объединенным content
     * - Устанавливает originalIds = messageIds
     * - Role = USER по умолчанию
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

        val firstMessageId = links.first { it.messageId in messageIds }.messageId

        // TODO: Implement AI-powered squash with SquashOperation tracking
        //       Current: simple concatenation via originalIds (deprecated)
        //       Future: AI summarization with prompt + model stored in SquashOperation
        //       Then: set squashOperationId instead of originalIds
        val squashedMessage = Conversation.Message(
            id = Conversation.Message.Id(uuid7()),
            conversationId = conversationId,
            originalIds = messageIds, // TODO: migrate to squashOperationId after AI squash implementation
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
                link.messageId == firstMessageId -> {
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
