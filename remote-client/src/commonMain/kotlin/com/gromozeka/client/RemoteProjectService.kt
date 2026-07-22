package com.gromozeka.client

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.remote.protocol.CreateProjectRequest
import com.gromozeka.remote.protocol.FindProjectByIdRequest
import com.gromozeka.remote.protocol.FindRecentProjectsRequest
import com.gromozeka.remote.protocol.FindProjectsRequest
import com.gromozeka.remote.protocol.NullableProjectResponse
import com.gromozeka.remote.protocol.ProjectResponse
import com.gromozeka.remote.protocol.ProjectsResponse
import com.gromozeka.remote.protocol.UpdateProjectLastUsedRequest
import com.gromozeka.remote.protocol.UpdateProjectRequest
import com.gromozeka.remote.protocol.DeleteProjectRequest
import com.gromozeka.remote.protocol.SavedResponse

internal class RemoteProjectService(
    private val client: GromozekaWsClient,
) : ProjectDomainService {
    override suspend fun create(
        name: String,
        description: String?,
        id: Project.Id?,
    ): Project {
        require(id == null) { "Remote project creation does not accept a caller-provided id" }
        return client.requestTyped<CreateProjectRequest, ProjectResponse>(
            CreateProjectRequest(name, description)
        ).project
    }

    override suspend fun findById(id: Project.Id): Project? =
        client.requestTyped<FindProjectByIdRequest, NullableProjectResponse>(FindProjectByIdRequest(id)).project

    override suspend fun findRecent(limit: Int): List<Project> =
        client.requestTyped<FindRecentProjectsRequest, ProjectsResponse>(FindRecentProjectsRequest(limit)).projects

    override suspend fun findAll(): List<Project> =
        client.requestTyped<FindProjectsRequest, ProjectsResponse>(FindProjectsRequest).projects

    override suspend fun update(id: Project.Id, name: String, description: String?): Project =
        client.requestTyped<UpdateProjectRequest, ProjectResponse>(
            UpdateProjectRequest(id, name, description)
        ).project

    override suspend fun delete(id: Project.Id) {
        client.requestTyped<DeleteProjectRequest, SavedResponse>(DeleteProjectRequest(id))
    }

    override suspend fun updateLastUsed(id: Project.Id): Project? =
        client.requestTyped<UpdateProjectLastUsedRequest, NullableProjectResponse>(UpdateProjectLastUsedRequest(id)).project
}
