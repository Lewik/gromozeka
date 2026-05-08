package com.gromozeka.infrastructure.db.persistence.mongo

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ProjectRepository
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

@Service
@Primary
class MongoProjectRepository(
    database: MongoDatabase,
) : ProjectRepository {
    private val projects: MongoCollection<Project> = database.getCollection("projects")
    private val indexes = MongoIndexInitializer {
        projects.createIndex(Indexes.ascending("id"), IndexOptions().unique(true))
        projects.createIndex(Indexes.ascending("path"), IndexOptions().unique(true))
        projects.createIndex(Indexes.descending("lastUsedAt"))
    }

    override suspend fun save(project: Project): Project {
        indexes.ensure()
        projects.upsertByDomainId(project.id.value, project)
        return project
    }

    override suspend fun findById(id: Project.Id): Project? {
        indexes.ensure()
        return projects.findByDomainId(id.value)
    }

    override suspend fun findByPath(path: String): Project? {
        indexes.ensure()
        return projects.find(Filters.eq("path", path)).firstOrNull()
    }

    override suspend fun findAll(): List<Project> {
        indexes.ensure()
        return projects.find().sort(Indexes.descending("lastUsedAt")).toList()
    }

    override suspend fun findRecent(limit: Int): List<Project> {
        indexes.ensure()
        return projects.find().sort(Indexes.descending("lastUsedAt")).limit(limit).toList()
    }

    override suspend fun delete(id: Project.Id) {
        indexes.ensure()
        projects.deleteMany(Filters.eq("id", id.value))
    }

    override suspend fun exists(id: Project.Id): Boolean {
        indexes.ensure()
        return projects.countDocuments(Filters.eq("id", id.value)) > 0
    }
}
