package com.gromozeka.domain.service

/**
 * Registry for tracking imported CLAUDE.md prompt files.
 *
 * Maintains list of absolute file paths that have been imported into Gromozeka.
 * Infrastructure layer implements persistence (e.g., JSON file in GROMOZEKA_HOME).
 *
 * This abstraction prevents duplicate imports and tracks prompt sources.
 */
interface ImportedPromptsRegistry {
    /**
     * Loads all registered imported prompt paths.
     *
     * @return list of absolute file paths
     */
    suspend fun load(): List<String>

    /**
     * Adds new prompt paths to registry.
     *
     * Appends to existing registry without duplicates.
     * This is a transactional operation.
     *
     * @param paths absolute file paths to register
     */
    suspend fun add(paths: List<String>)

    /**
     * Clears all registered imported prompts.
     *
     * This is a transactional operation.
     */
    suspend fun clear()
}
