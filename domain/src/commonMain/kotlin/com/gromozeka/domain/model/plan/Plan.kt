package com.gromozeka.domain.model.plan

import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Plan - persistent entity for managing multi-step tasks.
 *
 * A plan represents a structured sequence of steps to accomplish a task.
 * Plans can be:
 * - Created manually by user
 * - Extracted from successful executions (future)
 * - Used as templates for similar tasks
 * - Cloned and adapted to new contexts
 *
 * Plans are not tightly coupled to threads - one plan can be used across different threads,
 * and one thread can use multiple plans.
 *
 * @property id unique plan identifier
 * @property name short plan name (displayed in UI)
 * @property description detailed description of the task this plan was created for
 * @property isTemplate template flag - if true, plan is intended for reuse
 * @property createdAt plan creation timestamp
 */
data class Plan(
    val id: Id,
    val name: String,
    val description: String,
    val isTemplate: Boolean,
    val createdAt: Instant
) {
    /**
     * Returns text for vectorization and semantic search.
     * Combines name and description for maximum search accuracy.
     */
    fun getTextForVectorization(): String = "$name. $description"
    
    /**
     * Typed plan identifier.
     * Prevents confusion with other UUIDs in the system (Thread.Id, Message.Id, etc.)
     */
    @Serializable
    @JvmInline
    value class Id(val value: String) {
        companion object {
            fun generate(): Id = Id(uuid7())
        }
    }
}
