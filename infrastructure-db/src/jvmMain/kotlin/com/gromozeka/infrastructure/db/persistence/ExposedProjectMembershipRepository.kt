package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectMembership
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.ProjectMembershipRepository
import com.gromozeka.infrastructure.db.persistence.tables.ProjectMemberships
import com.gromozeka.infrastructure.db.persistence.tables.Projects
import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Service

@Service
class ExposedProjectMembershipRepository : ProjectMembershipRepository {
    override suspend fun save(membership: ProjectMembership): ProjectMembership = dbQuery {
        val updated = ProjectMemberships.update(
            where = {
                (ProjectMemberships.projectId eq membership.projectId.value) and
                    (ProjectMemberships.userId eq membership.userId.value)
            },
        ) {
            it[role] = membership.role.name
        }
        if (updated == 0) {
            ProjectMemberships.insert {
                it[projectId] = membership.projectId.value
                it[userId] = membership.userId.value
                it[role] = membership.role.name
                it[createdAt] = membership.createdAt
                it[createdByUserId] = membership.createdByUserId.value
            }
        }
        membership
    }

    override suspend fun find(
        projectId: Project.Id,
        userId: User.Id,
    ): ProjectMembership? = dbQuery {
        ProjectMemberships.selectAll()
            .where {
                (ProjectMemberships.projectId eq projectId.value) and
                    (ProjectMemberships.userId eq userId.value)
            }
            .singleOrNull()
            ?.toProjectMembership()
    }

    override suspend fun findByProject(projectId: Project.Id): List<ProjectMembership> = dbQuery {
        ProjectMemberships.selectAll()
            .where { ProjectMemberships.projectId eq projectId.value }
            .map { it.toProjectMembership() }
    }

    override suspend fun findByUser(userId: User.Id): List<ProjectMembership> = dbQuery {
        ProjectMemberships.selectAll()
            .where { ProjectMemberships.userId eq userId.value }
            .map { it.toProjectMembership() }
    }

    override suspend fun delete(
        projectId: Project.Id,
        userId: User.Id,
    ): Boolean = dbQuery {
        ProjectMemberships.deleteWhere {
            (ProjectMemberships.projectId eq projectId.value) and
                (ProjectMemberships.userId eq userId.value)
        } == 1
    }

    override suspend fun countOwners(projectId: Project.Id): Long = dbQuery {
        ProjectMemberships.selectAll()
            .where {
                (ProjectMemberships.projectId eq projectId.value) and
                    (ProjectMemberships.role eq ProjectMembership.Role.OWNER.name)
            }
            .count()
    }

    override suspend fun assignUnownedProjectsToFirstOwner(
        userId: User.Id,
        createdAt: Instant,
    ): Int = dbQuery {
        val ownedProjectIds = ProjectMemberships.selectAll()
            .mapTo(mutableSetOf()) { it[ProjectMemberships.projectId] }
        val unownedProjectIds = Projects.selectAll()
            .map { it[Projects.id] }
            .filterNot(ownedProjectIds::contains)
        unownedProjectIds.forEach { projectId ->
            ProjectMemberships.insert {
                it[ProjectMemberships.projectId] = projectId
                it[ProjectMemberships.userId] = userId.value
                it[role] = ProjectMembership.Role.OWNER.name
                it[ProjectMemberships.createdAt] = createdAt
                it[createdByUserId] = userId.value
            }
        }
        unownedProjectIds.size
    }

    private fun ResultRow.toProjectMembership(): ProjectMembership =
        ProjectMembership(
            projectId = Project.Id(this[ProjectMemberships.projectId]),
            userId = User.Id(this[ProjectMemberships.userId]),
            role = ProjectMembership.Role.valueOf(this[ProjectMemberships.role]),
            createdAt = this[ProjectMemberships.createdAt],
            createdByUserId = User.Id(this[ProjectMemberships.createdByUserId]),
        )
}
