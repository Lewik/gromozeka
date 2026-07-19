package com.gromozeka.application.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ProjectRepository
import com.gromozeka.domain.service.ProjectDomainService
import klog.KLoggers
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
/**
 * Application service for project management.
 *
 * Handles project lifecycle including automatic creation on first use
 * and last-used timestamp tracking.
 *
 * Projects organize conversations and workspaces without owning a filesystem
 * path or worker placement.
 */
@Service
class ProjectApplicationService(
    private val projectRepository: ProjectRepository,
) : ProjectDomainService {
    private val log = KLoggers.logger(this)

    @Transactional
    override suspend fun create(
        name: String,
        description: String?,
        id: Project.Id?,
    ): Project {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Project name must not be blank" }
        val projectId = id ?: Project.Id(uuid7())
        require(projectRepository.findById(projectId) == null) {
            "Project already exists: ${projectId.value}"
        }
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        return projectRepository.save(
            Project(
                id = projectId,
                name = normalizedName,
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = now,
                lastUsedAt = now,
            )
        )
    }

    /**
     * Finds project by unique identifier.
     *
     * @param id project identifier
     * @return project if found, null otherwise
     */
    override suspend fun findById(id: Project.Id): Project? =
        projectRepository.findById(id)

    /**
     * Finds most recently used projects.
     *
     * @param limit maximum number of projects to return (default: 10)
     * @return recent projects ordered by lastUsedAt (newest first)
     */
    override suspend fun findRecent(limit: Int): List<Project> =
        projectRepository.findRecent(limit)

    /**
     * Updates project's last-used timestamp to current time.
     *
     * Called when project is accessed (conversation created, context added, etc.).
     *
     * @param id project identifier
     * @return updated project if exists, null otherwise
     */
    @Transactional
    override suspend fun updateLastUsed(id: Project.Id): Project? {
        val project = projectRepository.findById(id) ?: return null
        val updated = project.copy(lastUsedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()))
        return projectRepository.save(updated)
    }

}
