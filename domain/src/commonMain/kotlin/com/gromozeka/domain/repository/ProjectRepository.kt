package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Project

/**
 * [SPECIFICATION] Repository for managing project entities.
 *
 * ## Storage Architecture
 *
 * Implementation stores Project data across three databases:
 * - **SQL**: id, path, timestamps (source of truth for queries, indexing)
 * - **JSON** (.gromozeka/project.json): name, description, domain_patterns (versioned in Git)
 * - **Neo4j**: denormalized copy (project_id, name) for code spec filtering
 *
 * Repository abstracts this split - clients work with unified Project entity.
 * All three storage backends kept in sync automatically on save().
 *
 * ## Implementation Pattern
 *
 * Recommended: Composite Repository Pattern
 * - ProjectSqlRepository - SQL operations
 * - ProjectJsonRepository - JSON file operations
 * - ProjectNeo4jRepository - Neo4j graph operations
 * - CompositeProjectRepository - coordinates all three
 *
 * @see Project for domain model
 */
interface ProjectRepository {

    /**
     * Saves project to all storage backends.
     *
     * Writes to:
     * 1. SQL database (id, path, timestamps)
     * 2. JSON file .gromozeka/project.json (name, description, domain_patterns)
     * 3. Neo4j graph (denormalized project_id, name for code spec filtering)
     *
     * Uses upsert semantics: creates if doesn't exist, updates if exists.
     * This is a transactional operation - all three succeed or all fail.
     *
     * @param project project to save with all fields populated
     * @return saved project (unchanged, for fluent API)
     * @throws IllegalArgumentException if project.id is blank or project.path is blank
     */
    suspend fun save(project: Project): Project

    /**
     * Finds project by unique identifier.
     *
     * Reads from SQL + JSON, assembles complete Project entity.
     * Neo4j not queried (contains denormalized copy only).
     *
     * @param id project identifier
     * @return project if found, null if doesn't exist
     */
    suspend fun findById(id: Project.Id): Project?

    /**
     * Finds project by filesystem path.
     *
     * Path must be exact match (absolute path).
     * Reads from SQL + JSON.
     *
     * @param path absolute filesystem path
     * @return project if found, null if no project with this path exists
     */
    suspend fun findByPath(path: String): Project?

    /**
     * Finds all projects, ordered by last used (most recent first).
     *
     * Returns empty list if no projects exist.
     * Reads from SQL + JSON for each project.
     *
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
     * Deletes project from all storage backends.
     *
     * Removes from:
     * 1. SQL database
     * 2. Neo4j graph
     * Note: JSON file (.gromozeka/project.json) remains in filesystem.
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
     * Queries SQL database only.
     *
     * @param id project identifier
     * @return true if project exists, false otherwise
     */
    suspend fun exists(id: Project.Id): Boolean
}
