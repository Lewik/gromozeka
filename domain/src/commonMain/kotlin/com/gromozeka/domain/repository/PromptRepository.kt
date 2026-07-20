package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Project

/**
 * Repository for managing prompt templates.
 *
 * Builtin prompts are loaded from application resources. Mutable project
 * prompts are stored in the central project catalog.
 *
 * @see Prompt for domain model
 */
interface PromptRepository {

    /**
     * Finds builtin prompt by unique identifier.
     *
     * Only works for builtin: prompts (no project context needed).
     *
     * @param id builtin prompt identifier (must start with "builtin:")
     * @return prompt if found, null otherwise
     */
    suspend fun findBuiltinById(id: Prompt.Id): Prompt?

    /**
     * Finds prompt by unique identifier.
     *
     * @param id prompt identifier
     * @return prompt if found, null otherwise
     */
    suspend fun findById(id: Prompt.Id): Prompt?

    /**
     * @return all centrally available prompts
     */
    suspend fun findAll(): List<Prompt>

    /**
     * Finds builtin prompts and prompts owned by the given project.
     */
    suspend fun findByProject(projectId: Project.Id): List<Prompt>

    /**
     * @param type filter by prompt type
     * @return prompts of specified type, ordered by name
     */
    suspend fun findByType(type: Prompt.Type): List<Prompt>

    /**
     * Counts total number of prompts.
     *
     * @return total prompt count
     */
    suspend fun count(): Int
    
    /**
     * Saves prompt to database.
     *
     * @param prompt prompt to save
     * @return saved prompt
     */
    suspend fun save(prompt: Prompt): Prompt
}
