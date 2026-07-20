package com.gromozeka.domain.service

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Project

/**
 * Domain service for managing prompt templates.
 *
 * Coordinates the builtin and project prompt catalogs.
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
     * @return all builtin and project prompts
     */
    suspend fun findAll(): List<Prompt>

    suspend fun findByProject(projectId: Project.Id): List<Prompt>

    /**
     * Creates a mutable project prompt.
     *
     * Used for dynamically created prompts (e.g., via MCP tools).
     * Generates UUIDv7 for time-based ordering.
     * This is a transactional operation.
     *
     * @param name human-readable prompt name
     * @param content markdown text of the prompt
     * @return created prompt with assigned ID
     */
    suspend fun createProjectPrompt(projectId: Project.Id, name: String, content: String): Prompt
    
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
