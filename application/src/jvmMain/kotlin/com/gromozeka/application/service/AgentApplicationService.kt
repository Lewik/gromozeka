package com.gromozeka.application.service

import com.gromozeka.domain.model.Agent
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.domain.repository.AgentDomainService
import klog.KLoggers
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Application service for AI agent definition management.
 *
 * Handles agent lifecycle including creation, updates, and deletion.
 * Enforces business rules like preventing built-in agent deletion.
 *
 * Agents are reusable templates defining AI behavior through system prompts.
 * Each agent has specific role and can be used across multiple conversations.
 */
@Service
class AgentApplicationService(
    private val agentRepository: AgentRepository,
    private val promptDomainService: com.gromozeka.domain.repository.PromptDomainService,
) : AgentDomainService {
    private val log = KLoggers.logger(this)

    /**
     * Creates new agent with specified configuration.
     *
     * Generates unique ID and sets initial timestamps.
     *
     * @param name agent role or display name (e.g., "Code Reviewer")
     * @param prompts ordered list of prompt IDs defining agent behavior
     * @param description optional human-readable description of capabilities
     * @param type agent scope type (builtin, global, or project-specific)
     * @return created agent
     */
    @Transactional
    override suspend fun createAgent(
        name: String,
        prompts: List<com.gromozeka.domain.model.Prompt.Id>,
        description: String?,
        type: Agent.Type
    ): Agent {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        val agent = Agent(
            id = Agent.Id(uuid7()),
            name = name,
            prompts = prompts,
            description = description,
            type = type,
            createdAt = now,
            updatedAt = now
        )

        return agentRepository.save(agent)
    }
    
    override suspend fun assembleSystemPrompt(agent: Agent, projectPath: String): String {
        return promptDomainService.assembleSystemPrompt(agent.prompts, projectPath = projectPath)
    }

    /**
     * Finds agent by unique identifier.
     *
     * @param id agent identifier
     * @return agent if found, null otherwise
     */
    override suspend fun findById(id: Agent.Id): Agent? =
        agentRepository.findById(id)

    /**
     * Retrieves all agents including built-in and user-created.
     *
     * @param projectPath path to current project (for loading PROJECT agents), null for global context
     * @return list of all agents
     */
    override suspend fun findAll(projectPath: String?): List<Agent> =
        agentRepository.findAll(projectPath)

    /**
     * Updates agent configuration.
     *
     * Only updates non-null parameters. Updates timestamp on any change.
     * Builtin agents can be updated (no enforcement at service level).
     *
     * @param id agent identifier
     * @param prompts new prompts list (null keeps existing)
     * @param description new description (null keeps existing)
     * @return updated agent if exists, null otherwise
     */
    @Transactional
    override suspend fun update(
        id: Agent.Id,
        prompts: List<com.gromozeka.domain.model.Prompt.Id>?,
        description: String?
    ): Agent? {
        val agent = agentRepository.findById(id) ?: return null
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        val updated = agent.copy(
            prompts = prompts ?: agent.prompts,
            description = description ?: agent.description,
            updatedAt = now
        )

        return agentRepository.save(updated)
    }

    /**
     * Deletes agent.
     *
     * Prevents deletion of builtin agents - logs warning and returns without error.
     * Global and project agents are deleted normally.
     *
     * @param id agent identifier
     */
    @Transactional
    override suspend fun delete(id: Agent.Id) {
        val agent = agentRepository.findById(id)
        if (agent?.type is Agent.Type.Builtin) {
            log.warn("Cannot delete builtin agent: ${agent.name}")
            return
        }
        agentRepository.delete(id)
    }

    /**
     * Returns total agent count.
     *
     * @return number of agents (includes builtin, global, and project agents)
     */
    override suspend fun count(): Int =
        agentRepository.count()
}
