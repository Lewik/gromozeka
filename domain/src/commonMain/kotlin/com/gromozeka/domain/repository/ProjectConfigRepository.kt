package com.gromozeka.domain.repository

/**
 * [SPECIFICATION] Repository for reading project configuration from .gromozeka/project.json
 *
 * Provides read-only access to project configuration file.
 * Used by tools and services that need domain_patterns without loading full Project entity.
 *
 * ## File Format
 *
 * ```json
 * {
 *   "name": "Gromozeka Development",
 *   "description": "Multi-agent AI desktop assistant",
 *   "domain_patterns": [
 *     "domain/src/**/*.kt",
 *     "core/src/**/*.kt"
 *   ]
 * }
 * ```
 *
 * ## Implementation
 *
 * Infrastructure layer implements this using kotlinx.serialization.json.
 * Creates file with defaults if doesn't exist.
 *
 * ## Use Cases
 *
 * - IndexDomainToGraphTool reads domain_patterns for code indexing
 * - ProjectRepository reads/writes full config during save()
 * - Other tools needing project-specific configuration
 */
interface ProjectConfigRepository {

    /**
     * Reads domain indexing patterns from .gromozeka/project.json
     *
     * Creates file with defaults if doesn't exist:
     * - name: last path segment
     * - description: null
     * - domain_patterns: ["domain/src/**/*.kt"]
     *
     * @param projectPath absolute path to project root directory
     * @return list of glob patterns for domain files
     */
    suspend fun getDomainPatterns(projectPath: String): List<String>

    /**
     * Reads project name from .gromozeka/project.json
     *
     * Creates file with defaults if doesn't exist.
     *
     * @param projectPath absolute path to project root directory
     * @return project name
     */
    suspend fun getProjectName(projectPath: String): String

    /**
     * Reads project description from .gromozeka/project.json
     *
     * Creates file with defaults if doesn't exist.
     *
     * @param projectPath absolute path to project root directory
     * @return project description or null if not set
     */
    suspend fun getProjectDescription(projectPath: String): String?
}
