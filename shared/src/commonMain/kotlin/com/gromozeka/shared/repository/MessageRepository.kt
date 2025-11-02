package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Conversation

interface MessageRepository {
    suspend fun save(message: Conversation.Message): Conversation.Message
    suspend fun findById(id: Conversation.Message.Id): Conversation.Message?
    suspend fun findByIds(ids: List<Conversation.Message.Id>): List<Conversation.Message>
    suspend fun findByConversation(conversationId: Conversation.Id): List<Conversation.Message>

    /**
     * Найти все версии сообщения (по originalIds)
     * Возвращает все Messages, у которых в originalIds есть указанный ID
     */
    suspend fun findVersions(originalId: Conversation.Message.Id): List<Conversation.Message>

    // TODO: Message Garbage Collection
    // Удалить Message если:
    // - Нет ссылок из thread_messages (orphaned)
    // - Нет ссылок из squash_operations (source_message_ids, result_message_id)
    // - Conversation удалена (CASCADE)
    // Реализация: background job или explicit cleanup метод
    // Возможный метод: suspend fun cleanupOrphaned(conversationId: Conversation.Id): Int
    // Инкапсулировать логику в репозитории, частично использовать средства БД (CASCADE, constraints)
}
