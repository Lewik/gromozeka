package com.gromozeka.bot.repository.exposed

import com.gromozeka.bot.repository.exposed.tables.Projects
import com.gromozeka.shared.domain.Project
import com.gromozeka.shared.repository.ProjectRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.time.Instant

class ExposedProjectRepository(
    private val json: Json
) : ProjectRepository {

    override suspend fun save(project: Project): Project = dbQuery {
        val exists = Projects.selectAll().where { Projects.id eq project.id.value }.count() > 0

        if (exists) {
            Projects.update({ Projects.id eq project.id.value }) {
                it[path] = project.path
                it[name] = project.name
                it[description] = project.description
                it[favorite] = project.favorite
                it[archived] = project.archived
                it[lastUsedAt] = project.lastUsedAt
            }
        } else {
            Projects.insert {
                it[id] = project.id.value
                it[path] = project.path
                it[name] = project.name
                it[description] = project.description
                it[favorite] = project.favorite
                it[archived] = project.archived
                it[createdAt] = project.createdAt
                it[lastUsedAt] = project.lastUsedAt
            }
        }
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

    override suspend fun findFavorites(): List<Project> = dbQuery {
        Projects.selectAll().where { Projects.favorite eq true }
            .orderBy(Projects.lastUsedAt, SortOrder.DESC)
            .map { it.toProject() }
    }

    override suspend fun search(query: String): List<Project> = dbQuery {
        val pattern = "%$query%"
        Projects.selectAll().where {
            (Projects.name like pattern) or (Projects.description like pattern)
        }
        .orderBy(Projects.lastUsedAt, SortOrder.DESC)
        .map { it.toProject() }
    }

    override suspend fun delete(id: Project.Id): Unit = dbQuery {
        Projects.deleteWhere { Projects.id eq id.value }
    }

    override suspend fun exists(id: Project.Id): Boolean = dbQuery {
        Projects.selectAll().where { Projects.id eq id.value }.count() > 0
    }

    override suspend fun count(): Int = dbQuery {
        Projects.selectAll().count().toInt()
    }

    private fun ResultRow.toProject() = Project(
        id = Project.Id(this[Projects.id]),
        path = this[Projects.path],
        name = this[Projects.name],
        description = this[Projects.description],
        favorite = this[Projects.favorite],
        archived = this[Projects.archived],
        createdAt = this[Projects.createdAt],
        lastUsedAt = this[Projects.lastUsedAt]
    )
}
