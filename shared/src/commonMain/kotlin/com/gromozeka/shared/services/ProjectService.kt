package com.gromozeka.shared.services

import com.gromozeka.shared.domain.Project
import com.gromozeka.shared.repository.ProjectRepository
import klog.KLoggers
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Instant

class ProjectService(
    private val projectRepository: ProjectRepository,
) {
    private val log = KLoggers.logger(this)

    suspend fun getOrCreate(path: String): Project {
        val existing = projectRepository.findByPath(path)
        if (existing != null) {
            updateLastUsed(existing.id)
            return existing
        }

        val newProject = createProject(path)
        return projectRepository.save(newProject)
    }

    suspend fun findById(id: Project.Id): Project? =
        projectRepository.findById(id)

    suspend fun findByPath(path: String): Project? =
        projectRepository.findByPath(path)

    suspend fun findRecent(limit: Int = 10): List<Project> =
        projectRepository.findRecent(limit)

    suspend fun findFavorites(): List<Project> =
        projectRepository.findFavorites()

    suspend fun search(query: String): List<Project> =
        projectRepository.search(query)

    suspend fun toggleFavorite(id: Project.Id): Project? {
        val project = projectRepository.findById(id) ?: return null
        val updated = project.copy(favorite = !project.favorite)
        return projectRepository.save(updated)
    }

    suspend fun updateLastUsed(id: Project.Id): Project? {
        val project = projectRepository.findById(id) ?: return null
        val updated = project.copy(lastUsedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()))
        return projectRepository.save(updated)
    }

    private fun createProject(path: String): Project {
        val name = path.substringAfterLast('/')
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        return Project(
            id = Project.Id(uuid7()),
            path = path,
            name = name,
            createdAt = now,
            lastUsedAt = now
        )
    }
}
