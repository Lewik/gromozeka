package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Context
import com.gromozeka.domain.model.Project

/**
 * Repository for managing conversation contexts.
 *
 * Context represents extracted knowledge from conversations - important facts,
 * file references, and links organized by topic.
 *
 * @see Context for domain model
 */
interface ContextRepository {

    /**
     * Saves context to persistent storage.
     *
     * Creates new context if ID doesn't exist, updates existing otherwise.
     * Context ID must be set before calling (use uuid7() for time-based ordering).
     * This is a transactional operation.
     *
     * @param context context to save with all fields populated
     * @return saved context (unchanged, for fluent API)
     * @throws IllegalArgumentException if context.id is blank or context.name is blank
     */
    suspend fun save(context: Context): Context

    /**
     * Finds context by unique identifier.
     *
     * @param id context identifier
     * @return context if found, null if doesn't exist
     */
    suspend fun findById(id: Context.Id): Context?

    /**
     * Deletes context permanently.
     *
     * This is a transactional operation.
     *
     * @param id context to delete
     */
    suspend fun delete(id: Context.Id)

    /**
     * Finds all contexts for specified project, ordered by creation time (newest first).
     *
     * Returns empty list if project has no contexts or project doesn't exist.
     *
     * @param projectId project to query
     * @return contexts in descending createdAt order
     */
    suspend fun findByProject(projectId: Project.Id): List<Context>

    /**
     * Finds contexts matching any of specified tags.
     *
     * Returns contexts where tags intersect with query tags (OR operation).
     * Ordered by relevance (number of matching tags, then by recency).
     *
     * @param tags tags to search for (non-empty)
     * @return matching contexts ordered by relevance
     */
    suspend fun findByTags(tags: Set<String>): List<Context>

    /**
     * Finds recently created contexts, ordered by creation time (newest first).
     *
     * @param limit maximum number of contexts to return
     * @return recent contexts up to specified limit
     */
    suspend fun findRecent(limit: Int): List<Context>

    /**
     * Searches contexts by name or content (fuzzy match).
     *
     * Search is case-insensitive and matches substrings in name or content.
     * Results ordered by relevance (exact matches first, then partial matches).
     *
     * @param query search query (non-empty)
     * @return matching contexts ordered by relevance
     */
    suspend fun search(query: String): List<Context>

    /**
     * Counts total number of contexts.
     *
     * @return total context count
     */
    suspend fun count(): Int

    /**
     * Counts contexts for specified project.
     *
     * @param projectId project to query
     * @return number of contexts in project
     */
    suspend fun countByProject(projectId: Project.Id): Int
}
