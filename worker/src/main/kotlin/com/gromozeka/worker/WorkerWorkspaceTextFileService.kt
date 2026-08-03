package com.gromozeka.worker

import com.gromozeka.domain.service.WorkerWorkspaceTextFileHandler
import com.gromozeka.domain.service.WorkerWorkspaceTextFileReadRequest
import com.gromozeka.domain.service.WorkspaceTextFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.io.File

@Service
class WorkerWorkspaceTextFileService : WorkerWorkspaceTextFileHandler {
    override suspend fun read(request: WorkerWorkspaceTextFileReadRequest): WorkspaceTextFile =
        withContext(Dispatchers.IO) {
            val requested = File(request.reference.path)
            val file = if (requested.isAbsolute) {
                requested
            } else {
                File(request.workspaceRootPath, request.reference.path)
            }.canonicalFile
            require(file.exists()) { "Workspace file does not exist: ${file.path}" }
            require(file.isFile) { "Workspace path is not a file: ${file.path}" }

            val bytes = file.inputStream().use { stream ->
                stream.readNBytes((request.maxBytes + 1).toInt())
            }
            require(bytes.size <= request.maxBytes) {
                "Workspace file is too large: ${bytes.size} bytes; max=${request.maxBytes}"
            }
            val content = bytes.decodeToString(throwOnInvalidSequence = true)
            WorkspaceTextFile(
                reference = request.reference,
                resolvedPath = file.path,
                fileName = file.name,
                content = content,
                sizeBytes = bytes.size.toLong(),
            )
        }
}
