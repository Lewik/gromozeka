package com.gromozeka.domain.service

import com.gromozeka.domain.model.Prompt

/**
 * Service for prompt file operations.
 *
 * Handles copying builtin prompts to user directory and tracking imported prompts.
 * Infrastructure layer implements file system operations and registry persistence.
 *
 * This abstraction isolates Application layer from file system details.
 */
interface PromptPersistenceService {
    /**
     * Copies single builtin prompt to user prompts directory.
     *
     * Creates ${GROMOZEKA_HOME}/prompts/ if doesn't exist.
     * Overwrites existing user prompt file if present.
     * This is manual operation triggered by user.
     *
     * @param prompt builtin prompt to copy
     * @return success or failure result
     */
    suspend fun copyBuiltinToUser(prompt: Prompt): Result<Unit>

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
