package com.gromozeka.application.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.ProjectRepository
import com.gromozeka.domain.repository.ProjectDomainService
import klog.KLoggers
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Application service for project management.
 *
 * Handles project lifecycle including automatic creation on first use,
 * favorite toggling, and last-used timestamp tracking.
 *
 * Projects organize conversations by workspace/codebase and map to
 * file system directories. Service ensures projects exist before
 * creating dependent entities (conversations, contexts).
 */
@Service
class ProjectApplicationService(
    private val projectRepository: ProjectRepository,
) : ProjectDomainService {
    private val log = KLoggers.logger(this)

    /**
     * Retrieves project by path or creates if doesn't exist.
     *
     * Updates lastUsedAt timestamp on existing projects.
     * Creates new project with auto-generated name from path.
     *
     * This is the primary method for ensuring project existence
     * before creating conversations or contexts.
     *
     * @param path absolute file system path to project directory
     * @return existing or newly created project
     */
    @Transactional
    override suspend fun getOrCreate(path: String): Project {
        val existing = projectRepository.findByPath(path)
        if (existing != null) {
            updateLastUsed(existing.id)
            return existing
        }

        val newProject = createProject(path)
        return projectRepository.save(newProject)
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
     * Finds project by file system path.
     *
     * @param path absolute file system path (exact match, case-sensitive)
     * @return project if found, null otherwise
     */
    override suspend fun findByPath(path: String): Project? =
        projectRepository.findByPath(path)

    /**
     * Finds most recently used projects.
     *
     * @param limit maximum number of projects to return (default: 10)
     * @return recent projects ordered by lastUsedAt (newest first)
     */
    override suspend fun findRecent(limit: Int): List<Project> =
        projectRepository.findRecent(limit)

    /**
     * Retrieves all favorite projects.
     *
     * @return list of favorite projects
     */
    override suspend fun findFavorites(): List<Project> =
        projectRepository.findFavorites()

    /**
     * Searches projects by name, path, or description.
     *
     * @param query search text
     * @return list of matching projects
     */
    override suspend fun search(query: String): List<Project> =
        projectRepository.search(query)

    /**
     * Toggles project favorite status.
     *
     * Flips favorite flag: true → false, false → true.
     *
     * @param id project identifier
     * @return updated project if exists, null otherwise
     */
    @Transactional
    override suspend fun toggleFavorite(id: Project.Id): Project? {
        val project = projectRepository.findById(id) ?: return null
        val updated = project.copy(favorite = !project.favorite)
        return projectRepository.save(updated)
    }

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

    /**
     * Creates new project with auto-generated configuration.
     *
     * Internal helper for getOrCreate().
     * Generates name from last path component, sets initial timestamps.
     *
     * @param path absolute file system path
     * @return new project instance (not yet persisted)
     */
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
