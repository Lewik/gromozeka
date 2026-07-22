package com.gromozeka.client

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.WorkspaceCatalogService
import com.gromozeka.domain.service.WorkspaceManagementService
import com.gromozeka.remote.protocol.CreateFilesystemWorkspaceRequest
import com.gromozeka.remote.protocol.DeleteWorkspaceMountRequest
import com.gromozeka.remote.protocol.DeleteWorkspaceRequest
import com.gromozeka.remote.protocol.FindWorkspaceMountsRequest
import com.gromozeka.remote.protocol.FindWorkspaceRequest
import com.gromozeka.remote.protocol.FindWorkspacesByProjectRequest
import com.gromozeka.remote.protocol.WorkspaceMountsResponse
import com.gromozeka.remote.protocol.WorkspaceResponse
import com.gromozeka.remote.protocol.WorkspacesResponse
import com.gromozeka.remote.protocol.SavedResponse
import com.gromozeka.remote.protocol.UpdateWorkspaceRequest

internal class RemoteWorkspaceCatalogService(
    private val client: GromozekaWsClient,
) : WorkspaceCatalogService, WorkspaceManagementService {
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

    override suspend fun create(
        projectId: Project.Id,
        name: String,
    ): Workspace =
        client.requestTyped<CreateFilesystemWorkspaceRequest, WorkspaceResponse>(
            CreateFilesystemWorkspaceRequest(projectId, name)
        ).workspace ?: error("Server did not return the created workspace")

    override suspend fun update(workspaceId: Workspace.Id, name: String): Workspace =
        client.requestTyped<UpdateWorkspaceRequest, WorkspaceResponse>(
            UpdateWorkspaceRequest(workspaceId, name)
        ).workspace ?: error("Server did not return the updated workspace")

    override suspend fun delete(workspaceId: Workspace.Id) {
        client.requestTyped<DeleteWorkspaceRequest, SavedResponse>(DeleteWorkspaceRequest(workspaceId))
    }

    override suspend fun deleteMount(mountId: WorkspaceMount.Id) {
        client.requestTyped<DeleteWorkspaceMountRequest, SavedResponse>(DeleteWorkspaceMountRequest(mountId))
    }
}
