package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Project

/**
 * Repository for managing software projects and workspaces.
 *
 * Handles persistence of project metadata including file system path,
 * display name, favorite/archived status, and usage timestamps.
 *
 * Projects organize conversations by codebase/workspace and provide
 * context for AI interactions. Each project maps to a file system directory.
 */
interface ProjectRepository {
    /**
     * Saves new project or updates existing project.
     *
     * Creates project if ID doesn't exist, updates if ID exists.
     * Project ID must be set before calling (use uuid7() for time-based ordering).
     *
     * This is a transactional operation.
     *
     * @param project project to save (with all required fields)
     * @return saved project (same as input for create, updated version for update)
     */
    suspend fun save(project: Project): Project

    /**
     * Finds project by unique identifier.
     *
     * @param id project identifier
     * @return project if found, null if project doesn't exist
     */
    suspend fun findById(id: Project.Id): Project?

    /**
     * Finds project by file system path.
     *
     * Searches for exact path match (case-sensitive).
     * Path should be absolute and normalized.
     *
     * @param path absolute file system path
     * @return project if found, null if no project with this path exists
     */
    suspend fun findByPath(path: String): Project?

    /**
     * Finds all projects.
     *
     * Returns all projects including favorites, active, and archived.
     * Ordering is implementation-specific (may be by last used time, name, or creation time).
     *
     * @return list of all projects (empty list if no projects exist)
     */
    suspend fun findAll(): List<Project>

    /**
     * Finds most recently used projects.
     *
     * Returns projects ordered by lastUsedAt (most recent first).
     * Includes all projects regardless of favorite/archived status.
     *
     * @param limit maximum number of projects to return
     * @return list of recent projects (may be shorter than limit if fewer projects exist)
     */
    suspend fun findRecent(limit: Int): List<Project>

    /**
     * Finds favorite projects.
     *
     * Returns projects with favorite=true.
     * Ordering is implementation-specific.
     *
     * @return list of favorite projects (empty list if no favorites)
     */
    suspend fun findFavorites(): List<Project>

    /**
     * Searches projects by name, path, or description.
     *
     * Searches project name, path, and description fields for query string.
     * Search implementation is repository-specific (may be full-text, substring, or fuzzy).
     *
     * @param query search text (empty string may return all projects or no projects, implementation-specific)
     * @return list of matching projects (empty list if no matches)
     */
    suspend fun search(query: String): List<Project>

    /**
     * Deletes project and all associated data.
     *
     * This is a CASCADE delete operation - removes project and may cascade
     * to conversations (implementation-specific).
     *
     * @param id project identifier
     */
    suspend fun delete(id: Project.Id)

    /**
     * Checks if project exists.
     *
     * @param id project identifier
     * @return true if project exists, false otherwise
     */
    suspend fun exists(id: Project.Id): Boolean

    /**
     * Returns total number of projects.
     *
     * @return project count (includes favorites, active, and archived)
     */
    suspend fun count(): Int
}
