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
 * Builtin definitions are immutable blueprints. Project definitions are mutable
 * runtime entities that may be imported from a worker workspace or created in UI.
 *
 * Definition includes:
 * - Behavior (prompts, tools)
 * - AI configuration (provider, model)
 * - Metadata (name, description, type)
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique agent identifier
 * @property projectId owning project for project definitions, null for builtins
 * @property name agent role name displayed in UI (e.g., "Code Reviewer", "Researcher")
 * @property prompts ordered list of prompt IDs defining agent behavior
 * @property runtimeSelection model configuration used to create the runtime for this agent.
 * @property runtimeOverrides optional per-agent overrides on top of the selected model configuration.
 * @property tools list of available tool names for this agent
 * @property description optional human-readable explanation of agent's purpose
 * @property type definition scope
 * @property createdAt timestamp when agent was created (immutable)
 * @property updatedAt timestamp of last modification (name, prompts, or description change)
 */
@Serializable
data class AgentDefinition(
    val id: Id,
    val projectId: Project.Id? = null,
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
    init {
        require((type is Type.Builtin) == (projectId == null)) {
            "Builtin agents must not belong to a project and project agents must have a project"
        }
    }

    /**
     * Unique agent identifier.
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * Agent definition scope.
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
         * Mutable agent owned by one logical project.
         */
        @Serializable
        @SerialName("project")
        object Project : Type()
    }
}
