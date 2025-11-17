package com.gromozeka.bot.domain.repository

import com.gromozeka.bot.domain.model.Project

/**
 * Domain service for managing project metadata.
 *
 * Projects represent filesystem directories where conversations occur.
 * Service coordinates project lifecycle and enforces business rules:
 * - Projects created on-demand when first accessed
 * - lastUsedAt automatically updated on access
 * - Favorites and search for quick project navigation
 *
 * @see Project for domain model
 * @see ProjectRepository for persistence operations
 */
interface ProjectDomainService {

    /**
     * Gets existing project or creates new one.
     *
     * If project exists, updates lastUsedAt timestamp.
     * If project doesn't exist, creates it with name derived from path.
     * This is a TRANSACTIONAL operation - either find+update or create.
     *
     * @param path filesystem path to project directory
     * @return existing or newly created project
     */
    suspend fun getOrCreate(path: String): Project

    /**
     * Finds project by unique identifier.
     *
     * @param id project identifier
     * @return project if found, null otherwise
     */
    suspend fun findById(id: Project.Id): Project?

    /**
     * Finds project by filesystem path.
     *
     * @param path exact filesystem path
     * @return project if found, null otherwise
     */
    suspend fun findByPath(path: String): Project?

    /**
     * Retrieves recently used projects.
     *
     * Projects ordered by lastUsedAt (most recent first).
     *
     * @param limit maximum projects to return (default: 10)
     * @return recent projects in descending order by lastUsedAt
     */
    suspend fun findRecent(limit: Int = 10): List<Project>

    /**
     * Retrieves favorited projects.
     *
     * @return all projects marked as favorite
     */
    suspend fun findFavorites(): List<Project>

    /**
     * Searches projects by query string.
     *
     * Matches against project name and path.
     * Implementation-specific search strategy (substring, fuzzy, etc).
     *
     * @param query search text
     * @return matching projects
     */
    suspend fun search(query: String): List<Project>

    /**
     * Toggles project favorite status.
     *
     * If favorited, removes favorite. If not favorited, adds favorite.
     * This is a transactional operation.
     *
     * @param id project to toggle
     * @return updated project, or null if project not found
     */
    suspend fun toggleFavorite(id: Project.Id): Project?

    /**
     * Updates project's last used timestamp to current time.
     *
     * Called when user opens conversation in project.
     * This is a transactional operation.
     *
     * @param id project to update
     * @return updated project, or null if project not found
     */
    suspend fun updateLastUsed(id: Project.Id): Project?
}
