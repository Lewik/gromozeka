package com.gromozeka.domain.service

import com.gromozeka.domain.model.Project

/**
 * [SPECIFICATION] Domain service for managing project lifecycle.
 *
 * Projects are logical containers for conversations and workspaces.
 * Service coordinates project lifecycle and enforces business rules:
 * - lastUsedAt automatically updated on access
 *
 * ## Implementation Responsibilities
 *
 * Business Logic Agent implements this service delegating to ProjectRepository.
 * Repository handles storage coordination.
 * Service focuses on business rules and orchestration.
 *
 * @see Project for domain model
 * @see ProjectRepository for persistence operations
 */
interface ProjectDomainService {

    suspend fun create(
        name: String,
        description: String? = null,
        id: Project.Id? = null,
    ): Project

    /**
     * Finds project by unique identifier.
     *
     * @param id project identifier
     * @return project if found, null otherwise
     */
    suspend fun findById(id: Project.Id): Project?

    /**
     * Retrieves recently used projects.
     *
     * Projects ordered by lastUsedAt (most recent first).
     *
     * @param limit maximum projects to return (default: 10)
     * @return recent projects in descending order by lastUsedAt
     */
    suspend fun findRecent(limit: Int = 10): List<Project>

    suspend fun findAll(): List<Project>

    suspend fun update(
        id: Project.Id,
        name: String,
        description: String? = null,
    ): Project

    suspend fun delete(id: Project.Id)

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
