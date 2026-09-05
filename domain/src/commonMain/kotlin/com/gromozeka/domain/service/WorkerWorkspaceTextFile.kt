package com.gromozeka.domain.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkspacePathReference
import kotlinx.serialization.Serializable

const val MAX_WORKER_WORKSPACE_TEXT_FILE_BYTES = 12L * 1024L * 1024L

@Serializable
data class WorkspacePathAccessContext(
    val actorUserId: User.Id? = null,
    val expectedProjectId: Project.Id? = null,
) {
    init {
        require(actorUserId != null || expectedProjectId != null) {
            "Workspace path access requires an actor or expected project"
        }
    }
}

@Serializable
data class WorkspaceTextFile(
    val reference: WorkspacePathReference,
    val resolvedPath: String,
    val fileName: String,
    val content: String,
    val sizeBytes: Long,
) {
    init {
        require(resolvedPath.isNotBlank()) { "Resolved workspace file path must not be blank" }
        require(fileName.isNotBlank()) { "Workspace file name must not be blank" }
        require(sizeBytes >= 0) { "Workspace file size must not be negative" }
    }
}

fun interface WorkspaceTextFileReader {
    suspend fun read(
        reference: WorkspacePathReference,
        access: WorkspacePathAccessContext,
        maxBytes: Long,
    ): WorkspaceTextFile
}

@Serializable
data class WorkerWorkspaceTextFileReadRequest(
    val target: ConversationRuntimeWorkerIdentity,
    val reference: WorkspacePathReference,
    val workspaceRootPath: String,
    val maxBytes: Long,
) {
    init {
        require(workspaceRootPath.isNotBlank()) { "Workspace root path must not be blank" }
        require(maxBytes in 1..MAX_WORKER_WORKSPACE_TEXT_FILE_BYTES) {
            "Workspace text file max bytes must be between 1 and $MAX_WORKER_WORKSPACE_TEXT_FILE_BYTES"
        }
    }
}

interface WorkerWorkspaceTextFileClient {
    suspend fun read(request: WorkerWorkspaceTextFileReadRequest, access: WorkspacePathAccessContext): WorkspaceTextFile
}

interface WorkerWorkspaceTextFileHandler {
    suspend fun read(request: WorkerWorkspaceTextFileReadRequest): WorkspaceTextFile
}
