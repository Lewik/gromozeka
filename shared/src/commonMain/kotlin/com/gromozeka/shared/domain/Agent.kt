package com.gromozeka.shared.domain

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * AI agent definition with role, behavior, and usage tracking.
 *
 * Agents are reusable templates that define AI behavior through system prompts.
 * Each agent has a specific role (e.g., "Code Reviewer", "Security Expert")
 * and can be used across multiple conversations.
 *
 * Built-in agents are system-provided and cannot be deleted or modified.
 * Custom agents can be created, modified, and deleted by users.
 *
 * Usage tracking enables popularity-based sorting and analytics.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique agent identifier (time-based UUID for sortability)
 * @property name agent role or display name (e.g., "Code Reviewer", "Research Assistant")
 * @property systemPrompt instructions that define agent behavior and personality
 * @property description optional human-readable description of agent capabilities
 * @property isBuiltin true for system-provided agents (cannot be deleted), false for user-created agents
 * @property usageCount number of times this agent has been used (incremented on conversation creation)
 * @property createdAt timestamp when agent was created (immutable)
 * @property updatedAt timestamp of last modification (system prompt, name, or description change)
 */
@Serializable
data class Agent(
    val id: Id,
    val name: String,
    val systemPrompt: String,
    val description: String? = null,
    val isBuiltin: Boolean = false,
    val usageCount: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)
}
