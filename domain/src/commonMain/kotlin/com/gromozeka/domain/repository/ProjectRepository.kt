package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Project

/**
 * [SPECIFICATION] Repository for managing project entities.
 *
 * Repository abstracts storage layout - clients work with a unified Project entity.
 *
 * @see Project for domain model
 */
interface ProjectRepository {

    /**
     * Saves project with upsert semantics: creates if it doesn't exist, updates if it exists.
     *
     * Uses upsert semantics: creates if doesn't exist, updates if exists.
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
     * @param path absolute filesystem path
     * @return project if found, null if no project with this path exists
     */
    suspend fun findByPath(path: String): Project?

    /**
     * Finds all projects, ordered by last used (most recent first).
     *
     * Returns empty list if no projects exist.
     * @return all projects in descending lastUsedAt order
     */
    suspend fun findAll(): List<Project>

    /**
     * Finds recently used projects, ordered by last used (most recent first).
     *
     * @param limit maximum number of projects to return
     * @return recent projects up to specified limit
     */
    suspend fun findRecent(limit: Int): List<Project>

    /**
     * Deletion fails if project has associated conversations.
     *
     * @param id project to delete
     * @throws IllegalStateException if project has conversations
     */
    suspend fun delete(id: Project.Id)

    /**
     * Checks if project exists by ID.
     *
     * Queries SQL database only.
     *
     * @param id project identifier
     * @return true if project exists, false otherwise
     */
    suspend fun exists(id: Project.Id): Boolean
}
