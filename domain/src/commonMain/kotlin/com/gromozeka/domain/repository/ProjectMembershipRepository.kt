package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectMembership
import com.gromozeka.domain.model.User
import kotlin.time.Instant

interface ProjectMembershipRepository {
    suspend fun save(membership: ProjectMembership): ProjectMembership

    suspend fun find(
        projectId: Project.Id,
        userId: User.Id,
    ): ProjectMembership?

    suspend fun findByProject(projectId: Project.Id): List<ProjectMembership>

    suspend fun findByUser(userId: User.Id): List<ProjectMembership>

    suspend fun delete(
        projectId: Project.Id,
        userId: User.Id,
    ): Boolean

    suspend fun countOwners(projectId: Project.Id): Long

    suspend fun assignUnownedProjectsToFirstOwner(
        userId: User.Id,
        createdAt: Instant,
    ): Int
}
