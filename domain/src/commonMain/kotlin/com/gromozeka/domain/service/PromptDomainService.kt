package com.gromozeka.domain.service

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Project

/**
 * Domain service for managing prompt templates.
 *
 * Coordinates the global and project prompt catalogs.
 *
 * @see Prompt for domain model
 * @see PromptRepository for persistence operations
 */
interface PromptDomainService {

    /**
     * Finds prompt by ID.
     *
     * @param id prompt identifier
     * @return prompt if found, null otherwise
     */
    suspend fun findById(id: Prompt.Id): Prompt?

    /**
     * Finds all available prompts.
     *
     * @return all global and project prompts
     */
    suspend fun findAll(): List<Prompt>

    suspend fun findByProject(projectId: Project.Id): List<Prompt>

    suspend fun createPrompt(
        projectId: Project.Id?,
        name: String,
        content: String,
    ): Prompt

    suspend fun updatePrompt(
        id: Prompt.Id,
        name: String,
        content: String,
    ): Prompt?

    suspend fun deletePrompt(id: Prompt.Id)
}

/**
 * [SPECIFICATION] Resolves prompts for the current runtime context.
 */
interface PromptAssemblyService {
    suspend fun assembleSystemPrompt(
        promptIds: List<Prompt.Id>,
        runtimeContext: com.gromozeka.domain.model.RuntimeEnvironmentContext,
    ): List<String>
}
