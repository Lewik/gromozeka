package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Project

/**
 * Repository for managing prompt templates.
 *
 * Global and project prompts are stored in the central catalog.
 *
 * @see Prompt for domain model
 */
interface PromptRepository {

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
     * Finds global prompts and prompts owned by the given project.
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

    suspend fun delete(id: Prompt.Id)
}
