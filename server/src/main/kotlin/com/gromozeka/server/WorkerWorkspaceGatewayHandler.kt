package com.gromozeka.server

import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerWorkspaceGatewayCodec
import com.gromozeka.remote.protocol.WorkerWorkspaceRequest
import com.gromozeka.remote.protocol.WorkerWorkspaceResponse
import org.springframework.stereotype.Service

@Service
class WorkerWorkspaceGatewayHandler(
    private val workspaceDomainService: WorkspaceDomainService,
    private val workerAccessService: WorkerAccessService,
) : WorkerGatewayServerRequestHandler {
    override val operation = WorkerGatewayOperation.WORKSPACE_STATE

    override suspend fun execute(
        identity: ConversationRuntimeWorkerIdentity,
        request: WorkerGatewayMessage.Request,
    ): ByteArray {
        require(request.operation == operation) {
            "Worker cannot invoke Server operation ${request.operation}"
        }
        val response = when (
            val workspaceRequest = WorkerWorkspaceGatewayCodec.decodeRequest(request.payload)
        ) {
            is WorkerWorkspaceRequest.CreateAndMountFilesystem -> {
                workerAccessService.requireProjectAccess(identity.workerId, workspaceRequest.projectId)
                WorkerWorkspaceResponse.ExecutionContextResult(
                    workspaceDomainService.createAndMountFilesystemWorkspace(
                        projectId = workspaceRequest.projectId,
                        name = workspaceRequest.name,
                        workerId = identity.workerId.value,
                        rootPath = workspaceRequest.rootPath,
                    )
                )
            }

            is WorkerWorkspaceRequest.AttachFilesystem -> {
                workerAccessService.requireProjectAccess(identity.workerId, workspaceRequest.projectId)
                val workspace = workspaceDomainService.findById(workspaceRequest.workspaceId)
                    ?: error("Workspace not found: ${workspaceRequest.workspaceId.value}")
                require(workspace.projectId == workspaceRequest.projectId) {
                    "Workspace ${workspace.id.value} does not belong to project ${workspaceRequest.projectId.value}"
                }
                WorkerWorkspaceResponse.ExecutionContextResult(
                    workspaceDomainService.attachFilesystem(
                        workspaceId = workspace.id,
                        workerId = identity.workerId.value,
                        rootPath = workspaceRequest.rootPath,
                    )
                )
            }

            is WorkerWorkspaceRequest.FindProjectMounts -> {
                workerAccessService.requireProjectAccess(identity.workerId, workspaceRequest.projectId)
                WorkerWorkspaceResponse.MountsResult(
                    workspaceDomainService.findByProject(workspaceRequest.projectId)
                        .flatMap { workspaceDomainService.findMounts(it.id) }
                        .filter { it.workerId == identity.workerId.value }
                )
            }
        }
        return WorkerWorkspaceGatewayCodec.encodeResponse(response)
    }
}
