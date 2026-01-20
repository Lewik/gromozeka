package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ProjectRepository
import com.gromozeka.domain.repository.ProjectConfigRepository
import com.gromozeka.infrastructure.db.persistence.tables.Projects
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Service

/**
 * SQL-based implementation of ProjectRepository.
 * 
 * Stores minimal metadata in SQL (id, path, timestamps).
 * Delegates name/description/domain_patterns to ProjectConfigRepository (JSON files).
 * 
 * This is NOT a complete implementation - requires coordination with:
 * - ProjectConfigRepository (JSON file storage)
 * - Neo4j repository (denormalized project data for code spec filtering)
 */
@Service
class ExposedProjectRepository(
    private val projectConfigRepository: ProjectConfigRepository
) : ProjectRepository {

    override suspend fun save(project: Project): Project = dbQuery {
        val exists = Projects.selectAll().where { Projects.id eq project.id.value }.count() > 0

        if (exists) {
            Projects.update({ Projects.id eq project.id.value }) {
                it[path] = project.path
                it[lastUsedAt] = project.lastUsedAt.toKotlin()
            }
        } else {
            Projects.insert {
                it[id] = project.id.value
                it[path] = project.path
                it[createdAt] = project.createdAt.toKotlin()
                it[lastUsedAt] = project.lastUsedAt.toKotlin()
            }
        }
        
        // TODO: Write to JSON file via ProjectConfigRepository
        // TODO: Write to Neo4j for denormalized project data
        
        project
    }

    override suspend fun findById(id: Project.Id): Project? = dbQuery {
        Projects.selectAll().where { Projects.id eq id.value }
            .map { it.toProject() }
            .singleOrNull()
    }

    override suspend fun findByPath(path: String): Project? = dbQuery {
        Projects.selectAll().where { Projects.path eq path }
            .map { it.toProject() }
            .singleOrNull()
    }

    override suspend fun findAll(): List<Project> = dbQuery {
        Projects.selectAll().map { it.toProject() }
    }

    override suspend fun findRecent(limit: Int): List<Project> = dbQuery {
        Projects.selectAll()
            .orderBy(Projects.lastUsedAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toProject() }
    }

    override suspend fun delete(id: Project.Id): Unit = dbQuery {
        // TODO: Also delete from Neo4j
        Projects.deleteWhere { Projects.id eq id.value }
    }

    override suspend fun exists(id: Project.Id): Boolean = dbQuery {
        Projects.selectAll().where { Projects.id eq id.value }.count() > 0
    }

    private suspend fun ResultRow.toProject(): Project {
        val path = this[Projects.path]
        
        // Read name and description from JSON file
        val name = projectConfigRepository.getProjectName(path)
        val description = projectConfigRepository.getProjectDescription(path)
        
        return Project(
            id = Project.Id(this[Projects.id]),
            path = path,
            name = name,
            description = description,
            createdAt = this[Projects.createdAt].toKotlinx(),
            lastUsedAt = this[Projects.lastUsedAt].toKotlinx()
        )
    }
}
