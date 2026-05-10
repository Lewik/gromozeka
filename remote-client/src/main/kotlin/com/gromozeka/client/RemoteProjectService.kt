package com.gromozeka.client

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.service.ProjectDomainService
import com.gromozeka.remote.protocol.FindProjectByIdRequest
import com.gromozeka.remote.protocol.FindProjectByPathRequest
import com.gromozeka.remote.protocol.FindRecentProjectsRequest
import com.gromozeka.remote.protocol.GetOrCreateProjectRequest
import com.gromozeka.remote.protocol.NullableProjectResponse
import com.gromozeka.remote.protocol.ProjectResponse
import com.gromozeka.remote.protocol.ProjectsResponse
import com.gromozeka.remote.protocol.UpdateProjectLastUsedRequest

internal class RemoteProjectService(
    private val client: GromozekaWsClient,
) : ProjectDomainService {
    override suspend fun getOrCreate(path: String): Project =
        client.requestTyped<GetOrCreateProjectRequest, ProjectResponse>(GetOrCreateProjectRequest(path)).project

    override suspend fun findById(id: Project.Id): Project? =
        client.requestTyped<FindProjectByIdRequest, NullableProjectResponse>(FindProjectByIdRequest(id)).project

    override suspend fun findByPath(path: String): Project? =
        client.requestTyped<FindProjectByPathRequest, NullableProjectResponse>(FindProjectByPathRequest(path)).project

    override suspend fun findRecent(limit: Int): List<Project> =
        client.requestTyped<FindRecentProjectsRequest, ProjectsResponse>(FindRecentProjectsRequest(limit)).projects

    override suspend fun updateLastUsed(id: Project.Id): Project? =
        client.requestTyped<UpdateProjectLastUsedRequest, NullableProjectResponse>(UpdateProjectLastUsedRequest(id)).project
}
