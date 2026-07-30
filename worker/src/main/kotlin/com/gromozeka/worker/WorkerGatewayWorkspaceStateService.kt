package com.gromozeka.worker

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceExecutionContext
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.WorkerWorkspaceStateService
import com.gromozeka.remote.protocol.WorkerGatewayOperation
import com.gromozeka.remote.protocol.WorkerWorkspaceGatewayCodec
import com.gromozeka.remote.protocol.WorkerWorkspaceRequest
import com.gromozeka.remote.protocol.WorkerWorkspaceResponse
import org.springframework.stereotype.Service

@Service
class WorkerGatewayWorkspaceStateService(
    private val outbound: WorkerGatewayOutbound,
) : WorkerWorkspaceStateService {
    override suspend fun createAndMountFilesystemWorkspace(
        projectId: Project.Id,
        name: String,
        rootPath: String,
    ): WorkspaceExecutionContext =
        execute(
            WorkerWorkspaceRequest.CreateAndMountFilesystem(
                projectId = projectId,
                name = name,
                rootPath = rootPath,
            )
        ).requireExecutionContext()

    override suspend fun attachFilesystemWorkspace(
        projectId: Project.Id,
        workspaceId: Workspace.Id,
        rootPath: String,
    ): WorkspaceExecutionContext =
        execute(
            WorkerWorkspaceRequest.AttachFilesystem(
                projectId = projectId,
                workspaceId = workspaceId,
                rootPath = rootPath,
            )
        ).requireExecutionContext()

    override suspend fun findProjectMounts(projectId: Project.Id): List<WorkspaceMount> {
        val response = execute(WorkerWorkspaceRequest.FindProjectMounts(projectId))
        check(response is WorkerWorkspaceResponse.MountsResult) {
            "Unexpected workspace mounts response: ${response::class.simpleName}"
        }
        return response.mounts
    }

    private suspend fun execute(request: WorkerWorkspaceRequest): WorkerWorkspaceResponse =
        WorkerWorkspaceGatewayCodec.decodeResponse(
            outbound.execute(
                operation = WorkerGatewayOperation.WORKSPACE_STATE,
                payload = WorkerWorkspaceGatewayCodec.encodeRequest(request),
            )
        )

    private fun WorkerWorkspaceResponse.requireExecutionContext(): WorkspaceExecutionContext {
        check(this is WorkerWorkspaceResponse.ExecutionContextResult) {
            "Unexpected workspace execution response: ${this::class.simpleName}"
        }
        return context
    }
}
