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
 * @property id unique agent identifier (UUIDv7 for time-based ordering)
 * @property name agent role name displayed in UI (e.g., "Code Reviewer", "Researcher")
 * @property prompts ordered list of prompt IDs defining agent behavior
 * @property description optional human-readable explanation of agent's purpose
 * @property type agent scope type (builtin, global, or project-specific)
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
     * Unique agent identifier (UUIDv7).
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * Agent scope type defining where agent is stored and managed.
     */
    @Serializable
    enum class Type {
        /**
         * Builtin agent shipped with Gromozeka.
         * Stored in application resources, immutable.
         */
        BUILTIN,

        /**
         * Global agent stored in user's home directory (~/.gromozeka/agents).
         * Available across all projects for this user.
         */
        GLOBAL,

        /**
         * Project-specific agent stored in project directory (.gromozeka/agents).
         * Serialized and versioned with project code.
         */
        PROJECT
    }
}
