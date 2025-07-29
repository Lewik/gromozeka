package com.gromozeka.bot.repository

import com.gromozeka.bot.db.ChatDatabase
import com.gromozeka.bot.model.FileMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileMetadataRepository(private val database: ChatDatabase) {

    suspend fun insert(metadata: FileMetadata) = withContext(Dispatchers.IO) {
        database.fileMetadataQueries.insertFileMetadata(
            file_id = metadata.fileId,
            sha256 = metadata.sha256,
            relative_path = metadata.relativePath
        )
    }

    suspend fun getAll(): List<FileMetadata> = withContext(Dispatchers.IO) {
        database.fileMetadataQueries.selectAllFileMetadata()
            .executeAsList()
            .map {
                FileMetadata(
                    fileId = it.file_id,
                    sha256 = it.sha256,
                    relativePath = it.relative_path
                )
            }
    }

    suspend fun getByFileId(fileId: String): FileMetadata? = withContext(Dispatchers.IO) {
        database.fileMetadataQueries.selectByFileId(fileId)
            .executeAsOneOrNull()
            ?.let {
                FileMetadata(
                    fileId = it.file_id,
                    sha256 = it.sha256,
                    relativePath = it.relative_path
                )
            }
    }

    suspend fun getByRelativePath(path: String): FileMetadata? = withContext(Dispatchers.IO) {
        database.fileMetadataQueries.selectByRelativePath(path)
            .executeAsOneOrNull()
            ?.let {
                FileMetadata(
                    fileId = it.file_id,
                    sha256 = it.sha256,
                    relativePath = it.relative_path
                )
            }
    }

    suspend fun deleteById(fileId: String) = withContext(Dispatchers.IO) {
        database.fileMetadataQueries.deleteById(fileId)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        database.fileMetadataQueries.deleteAll()
    }
}
