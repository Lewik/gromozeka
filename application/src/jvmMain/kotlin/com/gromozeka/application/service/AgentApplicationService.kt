package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.repository.PromptRepository
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentPromptAssemblyService
import com.gromozeka.domain.service.PromptAssemblyService
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
    private val promptRepository: PromptRepository,
    private val promptAssemblyService: PromptAssemblyService,
) : AgentDomainService, AgentPromptAssemblyService {
    private val log = KLoggers.logger(this)

    /**
     * Creates new agent with specified configuration.
     *
     * Generates unique ID and sets initial timestamps.
     *
     * @param name agent role or display name (e.g., "Code Reviewer")
     * @param prompts ordered list of prompt IDs defining agent behavior
     * @param runtimeSelection model binding selected for this agent
     * @param tools list of tool names available to this agent
     * @param description optional human-readable description of capabilities
     * @return created agent
     */
    @Transactional
    override suspend fun createAgent(
        projectId: Project.Id,
        name: String,
        prompts: List<com.gromozeka.domain.model.Prompt.Id>,
        runtimeSelection: AiRuntimeSelection,
        tools: List<String>,
        description: String?,
    ): AgentDefinition {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        val agent = AgentDefinition(
            id = AgentDefinition.Id("project:${projectId.value}:agent:${uuid7()}"),
            projectId = projectId,
            name = name,
            prompts = prompts,
            runtimeSelection = runtimeSelection,
            tools = tools,
            description = description,
            type = AgentDefinition.Type.Project,
            createdAt = now,
            updatedAt = now
        )

        return agentRepository.save(agent)
    }

    override suspend fun copyBuiltinAgent(
        projectId: Project.Id,
        sourceAgentId: AgentDefinition.Id,
        name: String,
        prompts: List<Prompt.Id>,
        description: String?,
    ): AgentDefinition {
        require(name.isNotBlank()) { "Agent name must not be blank" }
        require(prompts.isNotEmpty()) { "Agent must contain at least one prompt" }
        require(prompts.distinct().size == prompts.size) { "Agent prompts must not contain duplicates" }

        val source = agentRepository.findById(sourceAgentId)
            ?: error("Builtin agent not found: ${sourceAgentId.value}")
        require(source.type is AgentDefinition.Type.Builtin) {
            "Only a builtin agent can be copied into a project"
        }

        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val copiedPrompts = mutableListOf<Prompt>()
        val resolvedPromptIds = prompts.map { promptId ->
            if (promptId.value == ENV_PROMPT_ID) {
                promptId
            } else {
                val prompt = promptRepository.findById(promptId)
                    ?: error("Prompt not found: ${promptId.value}")
                when (prompt.type) {
                    is Prompt.Type.Builtin -> {
                        val copy = prompt.copy(
                            id = Prompt.Id("project:${projectId.value}:prompt:${uuid7()}"),
                            projectId = projectId,
                            type = Prompt.Type.Project,
                            createdAt = now,
                            updatedAt = now,
                        )
                        copiedPrompts += copy
                        copy.id
                    }

                    is Prompt.Type.Project -> {
                        require(prompt.projectId == projectId) {
                            "Prompt ${prompt.id.value} belongs to another project"
                        }
                        prompt.id
                    }
                }
            }
        }

        val copy = source.copy(
            id = AgentDefinition.Id("project:${projectId.value}:agent:${uuid7()}"),
            projectId = projectId,
            name = name.trim(),
            prompts = resolvedPromptIds,
            description = description,
            type = AgentDefinition.Type.Project,
            createdAt = now,
            updatedAt = now,
        )
        return agentRepository.createWithPrompts(copy, copiedPrompts)
    }

    override suspend fun assembleSystemPrompt(
        agent: AgentDefinition,
        runtimeContext: RuntimeEnvironmentContext,
    ): List<String> {
        return promptAssemblyService.assembleSystemPrompt(agent.prompts, runtimeContext)
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
     * @return list of all agents
     */
    override suspend fun findAll(): List<AgentDefinition> =
        agentRepository.findAll()

    override suspend fun findByProject(projectId: Project.Id): List<AgentDefinition> =
        agentRepository.findByProject(projectId)

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
     * Project agents are deleted normally.
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
     * @return number of agents (includes builtin and project agents)
     */
    override suspend fun count(): Int =
        agentRepository.count()

    private companion object {
        const val ENV_PROMPT_ID = "env"
    }
}
