package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Prompt

/**
 * Domain service for managing prompt templates.
 *
 * Coordinates prompt discovery and system prompt assembly.
 * Prompts edited in external editor (IDEA) - service provides
 * file discovery and prompt assembly only.
 *
 * @see Prompt for domain model
 * @see PromptRepository for persistence operations
 */
interface PromptDomainService {

    /**
     * Assembles system prompt from ordered list of prompts.
     *
     * Loads prompts by IDs, joins content with separator.
     * Result represents complete system prompt ready for agent use.
     *
     * @param promptIds ordered list of prompt IDs to assemble
     * @param separator string to join prompts with
     * @return assembled system prompt string
     * @throws Prompt.NotFoundException if any referenced prompt doesn't exist
     */
    suspend fun assembleSystemPrompt(
        promptIds: List<Prompt.Id>,
        separator: String = "\n\n---\n\n"
    ): String

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
     * @return all prompts (builtin, file, remote)
     */
    suspend fun findAll(): List<Prompt>

    /**
     * Refreshes prompt list from filesystem.
     *
     * Re-scans prompt directories to detect changes made in external editor.
     */
    suspend fun refresh()

    /**
     * Creates inline text prompt for ad-hoc use.
     *
     * Used for dynamically created prompts (e.g., via MCP tools).
     * Generates UUIDv7 for time-based ordering.
     * This is a transactional operation.
     *
     * @param name human-readable prompt name
     * @param content markdown text of the prompt
     * @return created prompt with assigned ID
     */
    suspend fun createInlinePrompt(name: String, content: String): Prompt
    
    /**
     * Copies single builtin prompt to user prompts directory.
     *
     * Creates ${GROMOZEKA_HOME}/prompts/ if doesn't exist.
     * Overwrites existing user prompt file if present.
     * This is manual operation triggered by user.
     *
     * @param id builtin prompt identifier
     * @return success or failure result
     */
    suspend fun copyBuiltinPromptToUser(id: Prompt.Id): Result<Unit>
    
    /**
     * Resets all builtin prompts to user prompts directory.
     *
     * Copies all builtin prompts from resources to ${GROMOZEKA_HOME}/prompts/.
     * Creates directory if doesn't exist.
     * Overwrites existing user prompt files.
     * This is manual operation triggered by user.
     *
     * @return success with count of copied prompts, or failure
     */
    suspend fun resetAllBuiltinPrompts(): Result<Int>
}
