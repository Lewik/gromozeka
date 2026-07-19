package com.gromozeka.domain.model

import com.gromozeka.domain.model.ai.AiRuntimeOverrides
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.jvm.JvmInline

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
 * Runtime tasks carry this definition so each independently claimed step uses
 * the same immutable agent configuration.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique agent identifier. File-backed agents store it in JSON,
 * and loaders verify it against the expected storage-derived id.
 * @property name agent role name displayed in UI (e.g., "Code Reviewer", "Researcher")
 * @property prompts ordered list of prompt IDs defining agent behavior
 * @property runtimeSelection model configuration used to create the runtime for this agent.
 * @property runtimeOverrides optional per-agent overrides on top of the selected model configuration.
 * @property tools list of available tool names for this agent
 * @property description optional human-readable explanation of agent's purpose
 * @property type agent location type. File-backed agents store it in JSON,
 * and loaders verify it against the place where the file was found.
 * @property createdAt timestamp when agent was created (immutable)
 * @property updatedAt timestamp of last modification (name, prompts, or description change)
 */
@Serializable
data class AgentDefinition(
    val id: Id,
    val name: String,
    val prompts: List<Prompt.Id>,
    val runtimeSelection: AiRuntimeSelection,
    val runtimeOverrides: AiRuntimeOverrides = AiRuntimeOverrides(),
    val tools: List<String> = emptyList(), // tool names to resolve via ToolRegistry
    val description: String? = null,
    val type: Type,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /**
     * Unique agent identifier.
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
    @JsonClassDiscriminator("kind")
    sealed class Type {
        /**
         * Builtin agent shipped with Gromozeka.
         * Stored in application resources.
         * ID format: "agents/architect.json" (relative to resources/)
         */
        @Serializable
        @SerialName("builtin")
        object Builtin : Type()

        /**
         * Global agent stored in user's home directory.
         * Available across all projects for this user.
         * ID format: "agents/my-agent.json" (relative to ~/.gromozeka/)
         */
        @Serializable
        @SerialName("global")
        object Global : Type()

        /**
         * Project-specific agent stored in project directory.
         * Versioned with project code.
         * ID format: ".gromozeka/agents/architect.json" (relative to project root)
         */
        @Serializable
        @SerialName("project")
        object Project : Type()

        /**
         * Inline agent created dynamically (e.g., via MCP).
         * Persisted by the runtime repository, not as a filesystem agent file.
         * ID format: UUID string
         */
        @Serializable
        @SerialName("inline")
        object Inline : Type()
    }

}
