package com.gromozeka.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Agent definition - reusable configuration template for AI agent behavior.
 *
 * AgentDefinition is a data class representing agent configuration that can be:
 * - Stored as files (builtin, global, project-specific)
 * - Created dynamically (inline)
 * - Reused across multiple conversations
 *
 * Definition includes:
 * - Behavior (prompts, tools)
 * - AI configuration (provider, model)
 * - Metadata (name, description, type)
 *
 * Running agent instances (application.actor.Agent) use this definition
 * and can switch between definitions during conversation.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique agent identifier (hash of file path or generated for inline)
 * @property name agent role name displayed in UI (e.g., "Code Reviewer", "Researcher")
 * @property prompts ordered list of prompt IDs defining agent behavior
 * @property aiProvider AI provider identifier (e.g., "ANTHROPIC", "OPENAI", "GEMINI")
 * @property modelName model identifier (e.g., "claude-3-5-sonnet-20241022", "gpt-4")
 * @property tools list of available tool names for this agent
 * @property description optional human-readable explanation of agent's purpose
 * @property type agent location type with file path (or inline for dynamic agents)
 * @property createdAt timestamp when agent was created (immutable)
 * @property updatedAt timestamp of last modification (name, prompts, or description change)
 */
@Serializable
data class AgentDefinition(
    val id: Id,
    val name: String,
    val prompts: List<Prompt.Id>,
    val aiProvider: String, // TODO: make it AIProvider enum
    val modelName: String,
    val tools: List<String> = emptyList(), // tool names to resolve via ToolRegistry
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
