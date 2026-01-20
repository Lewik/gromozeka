package com.gromozeka.domain.service

import com.gromozeka.domain.model.Project

/**
 * [SPECIFICATION] Domain service for managing project lifecycle.
 *
 * Projects represent filesystem directories where conversations occur.
 * Service coordinates project lifecycle and enforces business rules:
 * - Projects created on-demand when first accessed
 * - lastUsedAt automatically updated on access
 * - Configuration read from .gromozeka/project.json (created with defaults if missing)
 *
 * ## Implementation Responsibilities
 *
 * Business Logic Agent implements this service delegating to ProjectRepository.
 * Repository handles all storage coordination (SQL + JSON + Neo4j).
 * Service focuses on business rules and orchestration.
 *
 * @see Project for domain model
 * @see ProjectRepository for persistence operations
 */
interface ProjectDomainService {

    /**
     * Gets existing project or creates new one.
     *
     * On retrieval:
     * - Updates lastUsedAt timestamp
     * - Reads configuration from .gromozeka/project.json
     *
     * On creation:
     * - Creates .gromozeka/project.json with defaults (name from path, empty description)
     * - Saves to SQL + JSON + Neo4j via repository
     *
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
