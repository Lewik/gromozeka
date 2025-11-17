package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Context
import com.gromozeka.shared.domain.Project

/**
 * Repository for managing extracted conversation contexts.
 *
 * Handles persistence of distilled knowledge extracted from conversations,
 * including content, file references, links, and searchable tags.
 *
 * Contexts enable efficient knowledge reuse without replaying full conversations.
 * They are project-scoped and support multiple query methods for discovery.
 */
interface ContextRepository {
    /**
     * Saves new context or updates existing context.
     *
     * Creates context if ID doesn't exist, updates if ID exists.
     * Context ID must be set before calling (use uuid7() for time-based ordering).
     *
     * This is a transactional operation.
     *
     * @param context context to save (with all required fields)
     * @return saved context (same as input for create, updated version for update)
     */
    suspend fun save(context: Context): Context

    /**
     * Finds context by unique identifier.
     *
     * @param id context identifier
     * @return context if found, null if context doesn't exist
     */
    suspend fun findById(id: Context.Id): Context?

    /**
     * Deletes context.
     *
     * @param id context identifier
     */
    suspend fun delete(id: Context.Id)

    /**
     * Finds all contexts in a project.
     *
     * Returns contexts ordered by update time (most recently updated first).
     *
     * @param projectId project to query
     * @return list of contexts (empty list if project has no contexts)
     */
    suspend fun findByProject(projectId: Project.Id): List<Context>

    /**
     * Finds contexts matching ALL specified tags.
     *
     * Returns contexts that have all tags in the input set (AND operation, not OR).
     * Ordering is implementation-specific.
     *
     * @param tags set of tags to match (empty set returns no results)
     * @return list of matching contexts (empty list if no matches)
     */
    suspend fun findByTags(tags: Set<String>): List<Context>

    /**
     * Finds most recently updated contexts.
     *
     * Returns contexts ordered by update time (most recent first).
     * Useful for "recent contexts" UI or quick access to latest work.
     *
     * @param limit maximum number of contexts to return
     * @return list of recent contexts (may be shorter than limit if fewer contexts exist)
     */
    suspend fun findRecent(limit: Int): List<Context>

    /**
     * Searches contexts by content and name.
     *
     * Searches context name and content fields for query string.
     * Search implementation is repository-specific (may be full-text, substring, or fuzzy).
     *
     * @param query search text (empty string may return all contexts or no contexts, implementation-specific)
     * @return list of matching contexts (empty list if no matches)
     */
    suspend fun search(query: String): List<Context>

    /**
     * Returns total number of contexts across all projects.
     *
     * @return context count
     */
    suspend fun count(): Int

    /**
     * Returns number of contexts in specific project.
     *
     * @param projectId project to count
     * @return context count for project (0 if project has no contexts or doesn't exist)
     */
    suspend fun countByProject(projectId: Project.Id): Int
}
