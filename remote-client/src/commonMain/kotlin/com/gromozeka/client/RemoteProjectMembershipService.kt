package com.gromozeka.client

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectMembership
import com.gromozeka.domain.model.User
import com.gromozeka.remote.protocol.ListProjectMembershipsRequest
import com.gromozeka.remote.protocol.ProjectMembershipRemovedResponse
import com.gromozeka.remote.protocol.ProjectMembershipResponse
import com.gromozeka.remote.protocol.ProjectMembershipsResponse
import com.gromozeka.remote.protocol.RemoveProjectMembershipRequest
import com.gromozeka.remote.protocol.SetProjectMembershipRequest

class RemoteProjectMembershipService internal constructor(
    private val client: GromozekaWsClient,
) {
    suspend fun list(projectId: Project.Id): List<ProjectMembership> =
        client.requestTyped<ListProjectMembershipsRequest, ProjectMembershipsResponse>(
            ListProjectMembershipsRequest(projectId)
        ).memberships

    suspend fun set(
        projectId: Project.Id,
        userId: User.Id,
        role: ProjectMembership.Role,
    ): ProjectMembership =
        client.requestTyped<SetProjectMembershipRequest, ProjectMembershipResponse>(
            SetProjectMembershipRequest(projectId, userId, role)
        ).membership

    suspend fun remove(
        projectId: Project.Id,
        userId: User.Id,
    ): Boolean =
        client.requestTyped<RemoveProjectMembershipRequest, ProjectMembershipRemovedResponse>(
            RemoveProjectMembershipRequest(projectId, userId)
        ).removed
}
