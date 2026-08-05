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
 * Global and project definitions are mutable server-managed runtime entities.
 * Application resources are templates and are not [AgentDefinition] instances.
 *
 * Definition includes:
 * - Behavior (prompts, tools)
 * - AI configuration (provider, model)
 * - Metadata (name, description, type)
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique agent identifier
 * @property projectId owning project for project definitions, null for global definitions
 * @property name agent role name displayed in UI (e.g., "Code Reviewer", "Researcher")
 * @property prompts ordered list of prompt IDs defining agent behavior
 * @property skills Agent Skills available to the agent through progressive disclosure
 * @property runtimeSelection model configuration used to create the runtime for this agent.
 * @property runtimeOverrides optional per-agent overrides on top of the selected model configuration.
 * @property tools additional tool names preloaded when available; unavailable tools are reported to the runtime
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
    val skills: List<AgentSkill.Id> = emptyList(),
    val runtimeSelection: AiRuntimeSelection,
    val runtimeOverrides: AiRuntimeOverrides = AiRuntimeOverrides(),
    val tools: List<String> = emptyList(),
    val description: String? = null,
    val type: Type,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require((type is Type.Global) == (projectId == null)) {
            "Global agents must not belong to a project and project agents must have a project"
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
         * Mutable agent available to every project.
         */
        @Serializable
        @SerialName("global")
        object Global : Type()

        /**
         * Mutable agent owned by one logical project.
         */
        @Serializable
        @SerialName("project")
        object Project : Type()
    }
}
