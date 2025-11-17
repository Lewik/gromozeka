package com.gromozeka.shared.repository

import com.gromozeka.shared.domain.Agent

/**
 * Repository for managing AI agent definitions.
 *
 * Handles persistence of agent templates including name, system prompt,
 * description, and usage statistics.
 *
 * Agents are reusable templates that define AI behavior. Built-in agents
 * cannot be deleted and should be treated as read-only system defaults.
 */
interface AgentRepository {
    /**
     * Saves new agent or updates existing agent.
     *
     * Creates agent if ID doesn't exist, updates if ID exists.
     * Agent ID must be set before calling (use uuid7() for time-based ordering).
     *
     * Built-in agents (isBuiltin=true) should not be modified via this method
     * (business logic enforcement, not repository constraint).
     *
     * This is a transactional operation.
     *
     * @param agent agent to save (with all required fields)
     * @return saved agent (same as input for create, updated version for update)
     */
    suspend fun save(agent: Agent): Agent

    /**
     * Finds agent by unique identifier.
     *
     * @param id agent identifier
     * @return agent if found, null if agent doesn't exist
     */
    suspend fun findById(id: Agent.Id): Agent?

    /**
     * Finds all agents.
     *
     * Returns all agents including built-in and user-created.
     * Ordering is implementation-specific (may be by usage count, name, or creation time).
     *
     * @return list of all agents (empty list if no agents exist)
     */
    suspend fun findAll(): List<Agent>

    /**
     * Deletes agent.
     *
     * Built-in agents (isBuiltin=true) should not be deleted via this method
     * (business logic enforcement, not repository constraint).
     *
     * @param id agent identifier
     */
    suspend fun delete(id: Agent.Id)

    /**
     * Returns total number of agents.
     *
     * @return agent count (includes both built-in and user-created agents)
     */
    suspend fun count(): Int
}
