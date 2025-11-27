package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Prompt

/**
 * Repository for managing prompt templates.
 *
 * Prompts are stored as files and edited in external editor (IDEA).
 * Repository provides read-only access to prompt files.
 *
 * Sources:
 * - Builtin: Shipped with application (resources)
 * - File: Files on disk (user, Claude global/project, imported)
 * - Remote: Downloaded from URLs
 *
 * @see Prompt for domain model
 */
interface PromptRepository {

    /**
     * Finds prompt by unique identifier.
     *
     * Searches across all sources (builtin, file, remote).
     *
     * @param id prompt identifier
     * @return prompt if found, null otherwise
     */
    suspend fun findById(id: Prompt.Id): Prompt?

    /**
     * Finds all available prompts from all sources.
     *
     * Includes builtin, file-based, and remote prompts.
     *
     * @return all prompts ordered by source, then by name
     */
    suspend fun findAll(): List<Prompt>

    /**
     * Finds prompts by type.
     *
     * @param type filter by prompt type (BUILTIN, PROJECT, or GLOBAL)
     * @return prompts of specified type, ordered by name
     */
    suspend fun findByType(type: Prompt.Type): List<Prompt>

    /**
     * Refreshes prompt list from filesystem.
     *
     * Re-scans directories to detect file changes.
     * Use after external editor modified files.
     */
    suspend fun refresh()

    /**
     * Counts total number of prompts.
     *
     * Includes all sources (builtin, file, remote).
     *
     * @return total prompt count
     */
    suspend fun count(): Int
    
    /**
     * Saves prompt to database.
     *
     * Used for imported and inline prompts that need persistence.
     * Builtin and scanned file prompts are not saved (exist as files).
     *
     * @param prompt prompt to save
     * @return saved prompt
     */
    suspend fun save(prompt: Prompt): Prompt
}
