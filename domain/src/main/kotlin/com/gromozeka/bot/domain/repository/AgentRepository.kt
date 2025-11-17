package com.gromozeka.bot.domain.repository

import com.gromozeka.bot.domain.model.Agent

/**
 * Repository for managing AI agent definitions.
 *
 * Handles persistence of reusable agent role templates (e.g., "Code Reviewer", "Security Expert").
 * Each agent can be used in multiple conversation sessions.
 *
 * @see Agent for domain model
 */
interface AgentRepository {

    /**
     * Saves agent to persistent storage.
     *
     * Creates new agent if ID doesn't exist, updates existing otherwise.
     * Agent ID must be set before calling (use uuid7() for time-based ordering).
     * This is a transactional operation.
     *
     * @param agent agent to save with all fields populated
     * @return saved agent (unchanged, for fluent API)
     * @throws IllegalArgumentException if agent.id is blank
     */
    suspend fun save(agent: Agent): Agent

    /**
     * Finds agent by unique identifier.
     *
     * @param id agent identifier
     * @return agent if found, null if doesn't exist
     */
    suspend fun findById(id: Agent.Id): Agent?

    /**
     * Finds all agents, ordered by usage count (most used first).
     *
     * Returns empty list if no agents exist.
     * Both builtin and user-created agents included.
     *
     * @return all agents in descending usage order
     */
    suspend fun findAll(): List<Agent>

    /**
     * Deletes agent permanently.
     *
     * Deletion fails if agent is currently used in any conversation.
     * This is a transactional operation.
     *
     * @param id agent to delete
     * @throws IllegalStateException if agent is in use (usageCount > 0)
     */
    suspend fun delete(id: Agent.Id)

    /**
     * Counts total number of agents.
     *
     * Includes both builtin and user-created agents.
     *
     * @return total agent count
     */
    suspend fun count(): Int
}
