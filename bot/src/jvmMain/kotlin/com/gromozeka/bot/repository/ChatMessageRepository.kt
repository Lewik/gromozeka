package com.gromozeka.bot.repository

import com.gromozeka.bot.db.ChatDatabase
import com.gromozeka.bot.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant

class ChatMessageRepository(private val database: ChatDatabase) {

    suspend fun insertMessage(message: ChatMessage) = withContext(Dispatchers.IO) {
        database.chatMessageQueries.insertMessage(
            id = message.id,
            role = message.role.name,
            timestamp = message.timestamp.toString(),
            metadata_type = message.metadataType.name,
        )
    }

    suspend fun getAllMessages(): List<ChatMessage> = withContext(Dispatchers.IO) {
        database.chatMessageQueries.selectAllMessages()
            .executeAsList()
            .map {
                ChatMessage(
                    id = it.id,
                    role = ChatMessage.Role.valueOf(it.role),
                    content = emptyList(),
                    timestamp = Instant.parse(it.timestamp),
                    metadataType = ChatMessage.MetadataType.valueOf(it.metadata_type)
                )
            }
    }

    suspend fun getLatestMessageId(): String? = withContext(Dispatchers.IO) {
        database.chatMessageQueries.latestMessageId().executeAsOneOrNull()
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        database.chatMessageQueries.deleteAllMessages()
    }
}
