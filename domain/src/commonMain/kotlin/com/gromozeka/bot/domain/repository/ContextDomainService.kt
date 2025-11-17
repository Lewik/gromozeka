package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Context

/**
 * Domain service for managing conversation contexts.
 *
 * Contexts are extracted information packets containing:
 * - Textual content (key facts, decisions, summaries)
 * - File references with read specifications
 * - External links for documentation/resources
 * - Tags for categorization
 *
 * Service coordinates context lifecycle and project association:
 * - Creates parent project on-demand if not exists
 * - Supports search by name, tags, recency
 * - Tracks extraction and update timestamps
 *
 * @see Context for domain model
 * @see ContextRepository for persistence operations
 * @see ProjectDomainService for project creation
 */
interface ContextDomainService {

    /**
     * Creates new context in project.
     *
     * If project doesn't exist, creates it first.
     * This is a TRANSACTIONAL operation - project creation and context creation are atomic.
     *
     * @param projectPath filesystem path to project (creates project if not exists)
     * @param name context identifier within project
     * @param content textual context data
     * @param files file references with read specifications (default: empty)
     * @param links external resource URLs (default: empty)
     * @param tags categorization labels (default: empty)
     * @return created context with assigned ID and timestamps
     */
    suspend fun createContext(
        projectPath: String,
        name: String,
        content: String,
        files: List<Context.File> = emptyList(),
        links: List<String> = emptyList(),
        tags: Set<String> = emptySet()
    ): Context

    /**
     * Finds context by unique identifier.
     *
     * @param id context identifier
     * @return context if found, null otherwise
     */
    suspend fun findById(id: Context.Id): Context?

    /**
     * Retrieves all contexts in project.
     *
     * @param projectPath filesystem path to project
     * @return contexts in project (empty list if project not found)
     */
    suspend fun findByProject(projectPath: String): List<Context>

    /**
     * Finds context by name within project.
     *
     * Context names are unique within project scope.
     *
     * @param projectPath filesystem path to project
     * @param name context name
     * @return context if found, null if project or context not found
     */
    suspend fun findByName(projectPath: String, name: String): Context?

    /**
     * Retrieves contexts matching any of specified tags.
     *
     * Returns contexts having at least one tag from the set.
     * Cross-project search.
     *
     * @param tags tag set to match
     * @return contexts with matching tags
     */
    suspend fun findByTags(tags: Set<String>): List<Context>

    /**
     * Retrieves recently created contexts.
     *
     * Contexts ordered by createdAt (most recent first).
     * Cross-project search.
     *
     * @param limit maximum contexts to return (default: 10)
     * @return recent contexts in descending order by createdAt
     */
    suspend fun findRecent(limit: Int = 10): List<Context>

    /**
     * Updates context data.
     *
     * All parameters optional - null means keep existing value.
     * Updates updatedAt timestamp.
     * This is a transactional operation.
     *
     * @param id context to update
     * @param content new textual content (null = keep existing)
     * @param files new file references (null = keep existing)
     * @param links new external URLs (null = keep existing)
     * @param tags new tag set (null = keep existing)
     * @return updated context, or null if context not found
     */
    suspend fun updateContent(
        id: Context.Id,
        content: String? = null,
        files: List<Context.File>? = null,
        links: List<String>? = null,
        tags: Set<String>? = null
    ): Context?

    /**
     * Deletes context permanently.
     *
     * This is a transactional operation.
     *
     * @param id context to delete
     */
    suspend fun delete(id: Context.Id)

    /**
     * Counts contexts in project.
     *
     * @param projectPath filesystem path to project
     * @return count of contexts (0 if project not found)
     */
    suspend fun count(projectPath: String): Int
}
