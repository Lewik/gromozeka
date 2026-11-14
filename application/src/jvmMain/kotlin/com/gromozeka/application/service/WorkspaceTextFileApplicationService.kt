package com.gromozeka.application.service

import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.model.WorkspacePathReference
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.WorkerWorkspaceFileClient
import com.gromozeka.domain.service.WorkerWorkspaceFileReadRequest
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.service.WorkspacePathAccessContext
import com.gromozeka.domain.service.WorkspaceTextFile
import com.gromozeka.domain.service.WorkspaceFile
import com.gromozeka.domain.service.WorkspaceFileReader
import com.gromozeka.domain.service.MAX_WORKER_WORKSPACE_TEXT_FILE_BYTES
import com.gromozeka.domain.service.WorkspaceTextFileReader
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["gromozeka.runtime.server.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class WorkspaceTextFileApplicationService(
    private val workspaceService: WorkspaceDomainService,
    private val workerAccessService: WorkerAccessService,
    private val userDirectoryService: UserDirectoryService,
    private val workerTargetResolver: ConversationRuntimeWorkerTargetResolver,
    private val workerClient: WorkerWorkspaceFileClient,
) : WorkspaceTextFileReader, WorkspaceFileReader {
    override suspend fun read(reference: WorkspacePathReference, access: WorkspacePathAccessContext, maxBytes: Long): WorkspaceTextFile {
        require(maxBytes in 1..MAX_WORKER_WORKSPACE_TEXT_FILE_BYTES)
        val file = readFile(reference, access, maxBytes)
        return WorkspaceTextFile(file.reference, file.resolvedPath, file.fileName,
            file.content.bytes().decodeToString(throwOnInvalidSequence = true), file.sizeBytes)
    }

    override suspend fun readFile(
        reference: WorkspacePathReference,
        access: WorkspacePathAccessContext,
        maxBytes: Long,
    ): WorkspaceFile {
        val execution = workspaceService.resolveExecution(reference.workspaceMountId)
        access.expectedProjectId?.let { expectedProjectId ->
            require(execution.project.id == expectedProjectId) {
                "Workspace mount ${reference.workspaceMountId.value} belongs to project " +
                    "${execution.project.id.value}, not ${expectedProjectId.value}"
            }
        }

        val workerId = ConversationRuntimeWorkerId(execution.mount.workerId)
        val actorUserId = access.actorUserId
        if (actorUserId != null) {
            val actor = userDirectoryService.findActiveById(actorUserId)
                ?: error("Workspace file access requires an active user")
            workerAccessService.requirePermission(
                actor = actor,
                workerId = workerId,
                permission = WorkerPermission.USE,
                projectId = execution.project.id,
            )
        } else {
            workerAccessService.requireProjectAccess(workerId, execution.project.id)
        }

        val target = workerTargetResolver.requireRegistered(
            workerId,
            ConversationRuntimeCapability.LOCAL_AGENT_TOOL,
        )
        val file = workerClient.read(
            WorkerWorkspaceFileReadRequest(
                target = target,
                reference = reference,
                workspaceRootPath = execution.mount.rootPath,
                maxBytes = maxBytes,
            ),
            access = access.copy(expectedProjectId = execution.project.id),
        )
        check(file.reference == reference) { "Worker returned a different workspace reference" }
        check(file.sizeBytes <= maxBytes && file.content.bytes().size.toLong() == file.sizeBytes) {
            "Worker file payload violates the requested size limit"
        }
        return file
    }
}
