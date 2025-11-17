package com.gromozeka.bot.domain.repository

import com.gromozeka.bot.domain.model.Agent

/**
 * Domain service for managing AI agent definitions.
 *
 * Coordinates agent lifecycle and enforces business rules:
 * - Builtin agents cannot be deleted
 * - System prompts and descriptions can be updated independently
 * - Usage count tracked automatically (managed by infrastructure)
 *
 * @see Agent for domain model
 * @see AgentRepository for persistence operations
 */
interface AgentDomainService {

    /**
     * Creates new agent definition.
     *
     * Generates UUIDv7 for time-based ordering and sets creation timestamps.
     * This is a transactional operation.
     *
     * @param name agent role name (e.g., "Code Reviewer", "Security Expert")
     * @param systemPrompt defines agent behavior and personality
     * @param description optional human-readable agent description
     * @param isBuiltin true for system agents, false for user-created
     * @return created agent with assigned ID
     */
    suspend fun createAgent(
        name: String,
        systemPrompt: String,
        description: String? = null,
        isBuiltin: Boolean = false
    ): Agent

    /**
     * Finds agent by unique identifier.
     *
     * @param id agent identifier
     * @return agent if found, null otherwise
     */
    suspend fun findById(id: Agent.Id): Agent?

    /**
     * Retrieves all agents in system.
     *
     * @return all agents (builtin and user-created)
     */
    suspend fun findAll(): List<Agent>

    /**
     * Updates agent configuration.
     *
     * Only systemPrompt and description can be updated.
     * Name and isBuiltin are immutable after creation.
     * This is a transactional operation.
     *
     * @param id agent to update
     * @param systemPrompt new system prompt (null = keep existing)
     * @param description new description (null = keep existing)
     * @return updated agent, or null if agent not found
     */
    suspend fun update(
        id: Agent.Id,
        systemPrompt: String? = null,
        description: String? = null
    ): Agent?

    /**
     * Deletes agent definition.
     *
     * Builtin agents cannot be deleted - operation logs warning and returns silently.
     * User-created agents are permanently removed.
     * This is a transactional operation.
     *
     * Note: Does not check if agent is in use by conversations.
     * Infrastructure should enforce referential integrity.
     *
     * @param id agent to delete
     */
    suspend fun delete(id: Agent.Id)

    /**
     * Counts total number of agents.
     *
     * @return count of all agents (builtin and user-created)
     */
    suspend fun count(): Int
}
