package com.gromozeka.bot.repository

import com.gromozeka.bot.db.ChatDatabase
import com.gromozeka.bot.model.ThreadMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThreadMetadataRepository(private val database: ChatDatabase) {

    suspend fun insert(threadId: String) = withContext(Dispatchers.IO) {
        database.threadMetadataQueries.insertThreadMetadata(threadId)
    }

    suspend fun getAll(): List<ThreadMetadata> = withContext(Dispatchers.IO) {
        database.threadMetadataQueries.selectAllThreadMetadata()
            .executeAsList()
            .map { ThreadMetadata(it) }
    }

    suspend fun deleteById(threadId: String) = withContext(Dispatchers.IO) {
        database.threadMetadataQueries.deleteByThreadId(threadId)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        database.threadMetadataQueries.deleteAllThreadMetadata()
    }
}