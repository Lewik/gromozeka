package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
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
 * Each agent has a specific role and can be used across multiple conversations.
 */
@Service
class AgentApplicationService(
    private val agentRepository: AgentRepository,
    private val promptDomainService: com.gromozeka.domain.service.PromptDomainService,
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
        type: AgentDefinition.Type,
    ): AgentDefinition {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        val agent = AgentDefinition(
            id = AgentDefinition.Id(uuid7()),
            name = name,
            prompts = prompts,
            aiProvider = "ANTHROPIC",  // TODO: make configurable
            modelName = "claude-haiku-4-5-20251001",  // TODO: make configurable
            tools = emptyList(),  // TODO: make configurable
            description = description,
            type = type,
            createdAt = now,
            updatedAt = now
        )

        return agentRepository.save(agent)
    }

    override suspend fun assembleSystemPrompt(agent: AgentDefinition, project: Project): List<String> {
        return promptDomainService.assembleSystemPrompt(agent.prompts, project)
    }

    /**
     * Finds agent by unique identifier.
     *
     * @param id agent identifier
     * @return agent if found, null otherwise
     */
    override suspend fun findById(id: AgentDefinition.Id): AgentDefinition? =
        agentRepository.findById(id)

    /**
     * Retrieves all agents, including built-in and user-created.
     *
     * @param projectPath path to the current project (for loading PROJECT agents), null for global context
     * @return list of all agents
     */
    override suspend fun findAll(projectPath: String?): List<AgentDefinition> =
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
        id: AgentDefinition.Id,
        prompts: List<com.gromozeka.domain.model.Prompt.Id>?,
        description: String?,
    ): AgentDefinition? {
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
    override suspend fun delete(id: AgentDefinition.Id) {
        val agent = agentRepository.findById(id)
        if (agent?.type is AgentDefinition.Type.Builtin) {
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
