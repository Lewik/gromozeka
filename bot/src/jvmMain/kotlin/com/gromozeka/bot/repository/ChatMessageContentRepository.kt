package com.gromozeka.bot.repository

import com.gromozeka.bot.db.ChatDatabase
import com.gromozeka.bot.model.ChatMessageContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatMessageContentRepository(private val database: ChatDatabase) {

    suspend fun insert(content: ChatMessageContent, messageId: String) = withContext(Dispatchers.IO) {
        database.chatMessageContentQueries.insertMessageContent(
            message_id = messageId,
            content = content.content,
            type = content.type.name,
            annotations_json = content.annotationsJson
        )
    }

    suspend fun getByMessageId(messageId: String): List<ChatMessageContent> = withContext(Dispatchers.IO) {
        database.chatMessageContentQueries.selectByMessageId(messageId)
            .executeAsList()
            .map {
                ChatMessageContent(
                    content = it.content,
                    type = ChatMessageContent.Type.valueOf(it.type),
                    annotationsJson = it.annotations_json
                )
            }
    }

    suspend fun deleteByMessageId(messageId: String) = withContext(Dispatchers.IO) {
        database.chatMessageContentQueries.deleteByMessageId(messageId)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        database.chatMessageContentQueries.deleteAllMessageContent()
    }
}