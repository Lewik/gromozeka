package com.gromozeka.worker

import com.gromozeka.domain.service.WorkerWorkspaceFileHandler
import com.gromozeka.domain.service.WorkerWorkspaceFileReadRequest
import com.gromozeka.domain.service.WorkspaceFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.io.File

@Service
class WorkerWorkspaceFileService : WorkerWorkspaceFileHandler {
    override suspend fun read(request: WorkerWorkspaceFileReadRequest): WorkspaceFile =
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
            val content = com.gromozeka.domain.model.BinaryContent.fromBytes(bytes)
            WorkspaceFile(
                reference = request.reference,
                resolvedPath = file.path,
                fileName = file.name,
                content = content,
                sizeBytes = bytes.size.toLong(),
            )
        }
}
