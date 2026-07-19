package com.gromozeka.domain.service

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
     * Creates environment prompt for ad-hoc use.
     *
     * Used for dynamically created prompts (e.g., via MCP tools).
     * Generates UUIDv7 for time-based ordering.
     * This is a transactional operation.
     *
     * @param name human-readable prompt name
     * @param content markdown text of the prompt
     * @return created prompt with assigned ID
     */
    suspend fun createEnvironmentPrompt(name: String, content: String): Prompt
    
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
    
    /**
     * Imports all CLAUDE.md files found on disk.
     *
     * Uses mdfind (Spotlight) on macOS for fast search.
     * Fallback to find in home directory on other platforms.
     * Skips already imported files.
     *
     * @return success with count of imported prompts, or failure
     */
    suspend fun importAllClaudeMd(): Result<Int>
}

/**
 * [SPECIFICATION] Resolves prompts for a logical workspace and runtime worker.
 */
interface PromptAssemblyService {
    suspend fun assembleSystemPrompt(
        promptIds: List<Prompt.Id>,
        runtimeContext: com.gromozeka.domain.model.RuntimeEnvironmentContext,
    ): List<String>
}
