package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Project

/**
 * Repository for managing project entities.
 *
 * Project represents a filesystem directory associated with conversations.
 * Projects track usage frequency (lastUsedAt) and user preferences (favorite, archived).
 *
 * @see Project for domain model
 */
interface ProjectRepository {

    /**
     * Saves project to persistent storage.
     *
     * Creates new project if ID doesn't exist, updates existing otherwise.
     * Project ID must be set before calling (use uuid7() for time-based ordering).
     * This is a transactional operation.
     *
     * @param project project to save with all fields populated
     * @return saved project (unchanged, for fluent API)
     * @throws IllegalArgumentException if project.id is blank or project.path is blank
     */
    suspend fun save(project: Project): Project

    /**
     * Finds project by unique identifier.
     *
     * @param id project identifier
     * @return project if found, null if doesn't exist
     */
    suspend fun findById(id: Project.Id): Project?

    /**
     * Finds project by filesystem path.
     *
     * Path must be exact match (absolute path).
     *
     * @param path absolute filesystem path
     * @return project if found, null if no project with this path exists
     */
    suspend fun findByPath(path: String): Project?

    /**
     * Finds all projects, ordered by last used (most recent first).
     *
     * Returns empty list if no projects exist.
     * Includes archived and favorite projects.
     *
     * @return all projects in descending lastUsedAt order
     */
    suspend fun findAll(): List<Project>

    /**
     * Finds recently used projects, ordered by last used (most recent first).
     *
     * Excludes archived projects (archived = false).
     *
     * @param limit maximum number of projects to return
     * @return recent projects up to specified limit
     */
    suspend fun findRecent(limit: Int): List<Project>

    /**
     * Finds favorite projects, ordered by last used (most recent first).
     *
     * Returns only projects marked as favorite (favorite = true).
     *
     * @return favorite projects in descending lastUsedAt order
     */
    suspend fun findFavorites(): List<Project>

    /**
     * Searches projects by name or description (fuzzy match).
     *
     * Search is case-insensitive and matches substrings in name or description.
     * Results ordered by last used (most recent first).
     *
     * @param query search query (non-empty)
     * @return matching projects ordered by lastUsedAt descending
     */
    suspend fun search(query: String): List<Project>

    /**
     * Deletes project permanently.
     *
     * Deletion fails if project has associated conversations.
     * This is a transactional operation.
     *
     * @param id project to delete
     * @throws IllegalStateException if project has conversations
     */
    suspend fun delete(id: Project.Id)

    /**
     * Checks if project exists by ID.
     *
     * @param id project identifier
     * @return true if project exists, false otherwise
     */
    suspend fun exists(id: Project.Id): Boolean

    /**
     * Counts total number of projects.
     *
     * Includes archived and favorite projects.
     *
     * @return total project count
     */
    suspend fun count(): Int
}
