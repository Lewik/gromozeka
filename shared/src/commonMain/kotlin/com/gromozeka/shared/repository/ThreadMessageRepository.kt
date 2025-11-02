package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Conversation

/**
 * ThreadMessageLink - связь между Thread и Message с позицией
 */
data class ThreadMessageLink(
    val threadId: Conversation.Thread.Id,
    val messageId: Conversation.Message.Id,
    val position: Int
)

interface ThreadMessageRepository {
    /**
     * Добавить связь thread -> message с определенной позицией
     */
    suspend fun add(threadId: Conversation.Thread.Id, messageId: Conversation.Message.Id, position: Int)

    /**
     * Добавить несколько связей batch (для оптимизации копирования при явных операциях)
     */
    suspend fun addBatch(links: List<ThreadMessageLink>)

    /**
     * Получить все связи для Thread (с порядком)
     */
    suspend fun getByThread(threadId: Conversation.Thread.Id): List<ThreadMessageLink>

    /**
     * Получить максимальную позицию (для append)
     */
    suspend fun getMaxPosition(threadId: Conversation.Thread.Id): Int?

    /**
     * Загрузить Messages в порядке (с JOIN) - готовые Message объекты
     */
    suspend fun getMessagesByThread(threadId: Conversation.Thread.Id): List<Conversation.Message>

    /**
     * Удалить все связи Thread (при удалении Thread - CASCADE)
     */
    suspend fun deleteByThread(threadId: Conversation.Thread.Id)
}
