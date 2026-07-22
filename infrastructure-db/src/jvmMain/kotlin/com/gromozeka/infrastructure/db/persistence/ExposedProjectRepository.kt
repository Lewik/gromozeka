package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ProjectRepository
import com.gromozeka.infrastructure.db.persistence.tables.Projects
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Service

/**
 * SQL-based implementation of ProjectRepository.
 */
@Service
class ExposedProjectRepository : ProjectRepository {

    override suspend fun save(project: Project): Project = dbQuery {
        val exists = Projects.selectAll().where { Projects.id eq project.id.value }.count() > 0

        if (exists) {
            Projects.update({ Projects.id eq project.id.value }) {
                it[name] = project.name
                it[description] = project.description
                it[lastUsedAt] = project.lastUsedAt.toKotlin()
            }
        } else {
            Projects.insert {
                it[id] = project.id.value
                it[name] = project.name
                it[description] = project.description
                it[createdAt] = project.createdAt.toKotlin()
                it[lastUsedAt] = project.lastUsedAt.toKotlin()
            }
        }

        project
    }

    override suspend fun findById(id: Project.Id): Project? = dbQuery {
        Projects.selectAll().where { Projects.id eq id.value }
            .map { it.toProject() }
            .singleOrNull()
    }

    override suspend fun findAll(): List<Project> = dbQuery {
        Projects.selectAll()
            .orderBy(Projects.lastUsedAt, SortOrder.DESC)
            .map { it.toProject() }
    }

    override suspend fun findRecent(limit: Int): List<Project> = dbQuery {
        Projects.selectAll()
            .orderBy(Projects.lastUsedAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toProject() }
    }

    override suspend fun delete(id: Project.Id): Unit = dbQuery {
        Projects.deleteWhere { Projects.id eq id.value }
    }

    override suspend fun exists(id: Project.Id): Boolean = dbQuery {
        Projects.selectAll().where { Projects.id eq id.value }.count() > 0
    }

    private fun ResultRow.toProject(): Project = Project(
        id = Project.Id(this[Projects.id]),
        name = this[Projects.name],
        description = this[Projects.description],
        createdAt = this[Projects.createdAt].toKotlinx(),
        lastUsedAt = this[Projects.lastUsedAt].toKotlinx()
    )
}
