package com.gromozeka.domain.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectMembership
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.User

interface ProjectAccessService {
    suspend fun create(
        actorUserId: User.Id,
        name: String,
        description: String? = null,
        id: Project.Id? = null,
    ): Project

    suspend fun findById(
        actorUserId: User.Id,
        id: Project.Id,
    ): Project?

    suspend fun findRecent(
        actorUserId: User.Id,
        limit: Int = 10,
    ): List<Project>

    suspend fun findAll(actorUserId: User.Id): List<Project>

    suspend fun update(
        actorUserId: User.Id,
        id: Project.Id,
        name: String,
        description: String? = null,
    ): Project

    suspend fun delete(
        actorUserId: User.Id,
        id: Project.Id,
    )

    suspend fun updateLastUsed(
        actorUserId: User.Id,
        id: Project.Id,
    ): Project?

    suspend fun requirePermission(
        actorUserId: User.Id,
        projectId: Project.Id,
        permission: ProjectPermission,
    ): ProjectMembership

    suspend fun can(
        actorUserId: User.Id,
        projectId: Project.Id,
        permission: ProjectPermission,
    ): Boolean

    suspend fun listMemberships(
        actorUserId: User.Id,
        projectId: Project.Id,
    ): List<ProjectMembership>

    suspend fun setMembership(
        actorUserId: User.Id,
        projectId: Project.Id,
        userId: User.Id,
        role: ProjectMembership.Role,
    ): ProjectMembership

    suspend fun removeMembership(
        actorUserId: User.Id,
        projectId: Project.Id,
        userId: User.Id,
    ): Boolean
}

class ProjectAccessDeniedException :
    IllegalStateException("Project is unavailable or access is denied")
