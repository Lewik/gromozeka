package com.gromozeka.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * AI agent definition with behavior configuration.
 *
 * Agent represents a reusable role template (e.g., "Code Reviewer", "Security Expert").
 * Multiple conversation sessions can use the same agent definition.
 *
 * Agent behavior defined through ordered list of prompts that are assembled
 * into final system prompt via PromptDomainService.assembleSystemPrompt().
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique agent identifier (hash of file path or generated for inline)
 * @property name agent role name displayed in UI (e.g., "Code Reviewer", "Researcher")
 * @property prompts ordered list of prompt IDs defining agent behavior
 * @property description optional human-readable explanation of agent's purpose
 * @property type agent location type with file path (or inline for dynamic agents)
 * @property createdAt timestamp when agent was created (immutable)
 * @property updatedAt timestamp of last modification (name, prompts, or description change)
 */
@Serializable
data class Agent(
    val id: Id,
    val name: String,
    val prompts: List<Prompt.Id>,
    val description: String? = null,
    val type: Type,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /**
     * Unique agent identifier (hash of file path or UUID for inline).
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * Agent location type.
     * Type determines where agent is stored and how its ID is resolved.
     * ID contains relative path specific to each type.
     */
    @Serializable
    sealed class Type {
        /**
         * Builtin agent shipped with Gromozeka.
         * Stored in application resources.
         * ID format: "agents/architect.json" (relative to resources/)
         */
        @Serializable
        object Builtin : Type()

        /**
         * Global agent stored in user's home directory.
         * Available across all projects for this user.
         * ID format: "agents/my-agent.json" (relative to ~/.gromozeka/)
         */
        @Serializable
        object Global : Type()

        /**
         * Project-specific agent stored in project directory.
         * Versioned with project code.
         * ID format: ".gromozeka/agents/architect.json" (relative to project root)
         */
        @Serializable
        object Project : Type()

        /**
         * Inline agent created dynamically (e.g., via MCP).
         * Exists only in memory, not persisted to filesystem.
         * ID format: UUID string
         */
        @Serializable
        object Inline : Type()
    }
}
