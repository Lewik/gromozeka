package com.gromozeka.domain.repository

import com.gromozeka.domain.model.Agent
import com.gromozeka.domain.model.Prompt

/**
 * Domain service for managing AI agent definitions.
 *
 * Coordinates agent lifecycle and enforces business rules:
 * - Builtin agents cannot be deleted (protected system agents)
 * - Prompts and descriptions can be updated independently
 * - All agents use ordered list of prompts for behavior definition
 *
 * @see Agent for domain model
 * @see AgentRepository for persistence operations
 */
interface AgentDomainService {

    /**
     * Creates new agent definition from prompts.
     *
     * Generates UUIDv7 for time-based ordering and sets creation timestamps.
     * This is a transactional operation.
     *
     * @param name agent role name (e.g., "Code Reviewer", "Security Expert")
     * @param prompts ordered list of prompt IDs
     * @param description optional human-readable agent description
     * @param type agent scope type (builtin, global, or project-specific)
     * @return created agent with assigned ID
     */
    suspend fun createAgent(
        name: String,
        prompts: List<Prompt.Id>,
        description: String? = null,
        type: Agent.Type
    ): Agent

    /**
     * Assembles system prompt for agent.
     *
     * Convenience method that delegates to PromptDomainService.assembleSystemPrompt().
     * Returns final system prompt ready for AI model.
     *
     * @param agent agent to assemble prompt for
     * @param projectPath optional project path for Dynamic prompts (e.g., Environment)
     * @return assembled system prompt string
     */
    suspend fun assembleSystemPrompt(agent: Agent, projectPath: String): String

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
     * Updates agent prompts or description.
     *
     * Name and type are immutable after creation.
     * This is a transactional operation.
     *
     * @param id agent to update
     * @param prompts new ordered prompts list (null = keep existing)
     * @param description new description (null = keep existing)
     * @return updated agent, or null if agent not found
     */
    suspend fun update(
        id: Agent.Id,
        prompts: List<Prompt.Id>? = null,
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
