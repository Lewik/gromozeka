package com.gromozeka.client

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.WorkspaceCatalogService
import com.gromozeka.remote.protocol.FindWorkspaceMountsRequest
import com.gromozeka.remote.protocol.FindWorkspaceRequest
import com.gromozeka.remote.protocol.FindWorkspacesByProjectRequest
import com.gromozeka.remote.protocol.WorkspaceMountsResponse
import com.gromozeka.remote.protocol.WorkspaceResponse
import com.gromozeka.remote.protocol.WorkspacesResponse

internal class RemoteWorkspaceCatalogService(
    private val client: GromozekaWsClient,
) : WorkspaceCatalogService {
    override suspend fun findById(id: Workspace.Id): Workspace? =
        client.requestTyped<FindWorkspaceRequest, WorkspaceResponse>(
            FindWorkspaceRequest(id)
        ).workspace

    override suspend fun findByProject(projectId: Project.Id): List<Workspace> =
        client.requestTyped<FindWorkspacesByProjectRequest, WorkspacesResponse>(
            FindWorkspacesByProjectRequest(projectId)
        ).workspaces

    override suspend fun findMounts(workspaceId: Workspace.Id): List<WorkspaceMount> =
        client.requestTyped<FindWorkspaceMountsRequest, WorkspaceMountsResponse>(
            FindWorkspaceMountsRequest(workspaceId)
        ).mounts
}
