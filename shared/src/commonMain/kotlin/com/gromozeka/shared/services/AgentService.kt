package com.gromozeka.shared.services

import com.gromozeka.domain.model.Agent
import com.gromozeka.domain.repository.AgentRepository
import klog.KLoggers
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Instant

/**
 * Application service for AI agent definition management.
 *
 * Handles agent lifecycle including creation, updates, and deletion.
 * Enforces business rules like preventing built-in agent deletion.
 *
 * Agents are reusable templates defining AI behavior through system prompts.
 * Each agent has specific role and can be used across multiple conversations.
 */
class AgentService(
    private val agentRepository: AgentRepository,
) {
    private val log = KLoggers.logger(this)

    /**
     * Creates new agent with specified configuration.
     *
     * Generates unique ID and sets initial timestamps.
     * New agents start with zero usage count.
     *
     * @param name agent role or display name (e.g., "Code Reviewer")
     * @param systemPrompt instructions defining agent behavior
     * @param description optional human-readable description of capabilities
     * @param isBuiltin true for system agents (prevents deletion), false for user agents
     * @return created agent
     */
    suspend fun createAgent(
        name: String,
        systemPrompt: String,
        description: String? = null,
        isBuiltin: Boolean = false
    ): Agent {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        val agent = Agent(
            id = Agent.Id(uuid7()),
            name = name,
            systemPrompt = systemPrompt,
            description = description,
            isBuiltin = isBuiltin,
            usageCount = 0,
            createdAt = now,
            updatedAt = now
        )

        return agentRepository.save(agent)
    }

    /**
     * Finds agent by unique identifier.
     *
     * @param id agent identifier
     * @return agent if found, null otherwise
     */
    suspend fun findById(id: Agent.Id): Agent? =
        agentRepository.findById(id)

    /**
     * Retrieves all agents including built-in and user-created.
     *
     * @return list of all agents
     */
    suspend fun findAll(): List<Agent> =
        agentRepository.findAll()

    /**
     * Updates agent configuration.
     *
     * Only updates non-null parameters. Updates timestamp on any change.
     * Built-in agents can be updated (no enforcement at service level).
     *
     * @param id agent identifier
     * @param systemPrompt new system prompt (null keeps existing)
     * @param description new description (null keeps existing)
     * @return updated agent if exists, null otherwise
     */
    suspend fun update(
        id: Agent.Id,
        systemPrompt: String? = null,
        description: String? = null
    ): Agent? {
        val agent = agentRepository.findById(id) ?: return null
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        val updated = agent.copy(
            systemPrompt = systemPrompt ?: agent.systemPrompt,
            description = description ?: agent.description,
            updatedAt = now
        )

        return agentRepository.save(updated)
    }

    /**
     * Deletes agent.
     *
     * Prevents deletion of built-in agents - logs warning and returns without error.
     * User-created agents are deleted normally.
     *
     * @param id agent identifier
     */
    suspend fun delete(id: Agent.Id) {
        val agent = agentRepository.findById(id)
        if (agent?.isBuiltin == true) {
            log.warn("Cannot delete builtin agent: ${agent.name}")
            return
        }
        agentRepository.delete(id)
    }

    /**
     * Returns total agent count.
     *
     * @return number of agents (includes built-in and user-created)
     */
    suspend fun count(): Int =
        agentRepository.count()
}
