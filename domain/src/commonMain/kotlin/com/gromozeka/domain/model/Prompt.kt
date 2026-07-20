package com.gromozeka.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Single reusable prompt fragment.
 *
 * Prompts are building blocks for agent system prompts.
 * Agent contains ordered list of prompt IDs that are assembled into final system prompt.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id stable unique prompt identifier
 * @property projectId owning project for project prompts, null for builtins
 * @property name human-readable prompt name (displayed in UI)
 * @property content markdown text of the prompt
 * @property type prompt ownership scope
 * @property createdAt timestamp when prompt was created (immutable)
 * @property updatedAt timestamp of last modification
 */
@Serializable
data class Prompt(
    val id: Id,
    val projectId: Project.Id? = null,
    val name: String,
    val content: String,
    val type: Type,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    init {
        require((type is Type.Builtin) == (projectId == null)) {
            "Builtin prompts must not belong to a project and project prompts must have a project"
        }
    }

    /**
     * Stable unique prompt identifier.
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * Prompt scope.
     */
    @Serializable
    sealed class Type {
        /**
         * Builtin prompt shipped with Gromozeka.
         * Stored in application resources.
         * ID format: "prompts/shared-base.md" (relative to resources/)
         */
        @Serializable
        object Builtin : Type()

        /**
         * Mutable prompt owned by one logical project.
         */
        @Serializable
        object Project : Type()
    }

    /**
     * Thrown when referenced prompt is not found.
     *
     * @property promptId ID of missing prompt
     */
    class NotFoundException(val promptId: Id) :
        Exception("Prompt not found: ${promptId.value}")
}
