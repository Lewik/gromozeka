package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.infrastructure.db.persistence.tables.Contexts
import com.gromozeka.domain.model.Context
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ContextRepository
import kotlinx.serialization.json.Json

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.*
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service

@Service
class ExposedContextRepository(
    private val json: Json
) : ContextRepository {

    override suspend fun save(context: Context): Context = dbQuery {
        val exists = Contexts.selectAll().where { Contexts.id eq context.id.value }.count() > 0

        if (exists) {
            Contexts.update({ Contexts.id eq context.id.value }) {
                it[projectId] = context.projectId.value
                it[name] = context.name
                it[content] = context.content
                it[filesJson] = json.encodeToString(context.files)
                it[linksJson] = json.encodeToString(context.links)
                it[tags] = json.encodeToString(context.tags)
                it[extractedAt] = context.extractedAt.toKotlin()
                it[updatedAt] = context.updatedAt.toKotlin()
            }
        } else {
            Contexts.insert {
                it[id] = context.id.value
                it[projectId] = context.projectId.value
                it[name] = context.name
                it[content] = context.content
                it[filesJson] = json.encodeToString(context.files)
                it[linksJson] = json.encodeToString(context.links)
                it[tags] = json.encodeToString(context.tags)
                it[extractedAt] = context.extractedAt.toKotlin()
                it[createdAt] = context.createdAt.toKotlin()
                it[updatedAt] = context.updatedAt.toKotlin()
            }
        }
        context
    }

    override suspend fun findById(id: Context.Id): Context? = dbQuery {
        Contexts.selectAll().where { Contexts.id eq id.value }
            .map { it.toContext() }
            .singleOrNull()
    }

    override suspend fun delete(id: Context.Id): Unit = dbQuery {
        Contexts.deleteWhere { Contexts.id eq id.value }
    }

    override suspend fun findByProject(projectId: Project.Id): List<Context> = dbQuery {
        Contexts.selectAll().where { Contexts.projectId eq projectId.value }
            .orderBy(Contexts.updatedAt, SortOrder.DESC)
            .map { it.toContext() }
    }

    override suspend fun findByTags(tags: Set<String>): List<Context> = dbQuery {
        // TODO: Optimize using PostgreSQL JSON operators instead of in-memory filtering.
        //       Current approach loads ALL contexts into memory and filters - inefficient for large datasets.
        //       Better: Use native JSON queries like: WHERE tags::jsonb ?| array['tag1', 'tag2']
        //       Performance impact: O(n) memory load vs O(1) database filtering
        Contexts.selectAll()
            .map { it.toContext() }
            .filter { context -> context.tags.any { it in tags } }
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun findRecent(limit: Int): List<Context> = dbQuery {
        Contexts.selectAll()
            .orderBy(Contexts.updatedAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toContext() }
    }

    override suspend fun search(query: String): List<Context> = dbQuery {
        val pattern = "%$query%"
        Contexts.selectAll().where {
            (Contexts.name like pattern) or (Contexts.content like pattern)
        }
        .orderBy(Contexts.updatedAt, SortOrder.DESC)
        .map { it.toContext() }
    }

    override suspend fun count(): Int = dbQuery {
        Contexts.selectAll().count().toInt()
    }

    override suspend fun countByProject(projectId: Project.Id): Int = dbQuery {
        Contexts.selectAll().where { Contexts.projectId eq projectId.value }.count().toInt()
    }

    private fun ResultRow.toContext() = Context(
        id = Context.Id(this[Contexts.id]),
        projectId = Project.Id(this[Contexts.projectId]),
        name = this[Contexts.name],
        content = this[Contexts.content],
        files = json.decodeFromString(this[Contexts.filesJson]),
        links = json.decodeFromString(this[Contexts.linksJson]),
        tags = json.decodeFromString(this[Contexts.tags]),
        extractedAt = this[Contexts.extractedAt].toKotlinx(),
        createdAt = this[Contexts.createdAt].toKotlinx(),
        updatedAt = this[Contexts.updatedAt].toKotlinx()
    )
}
