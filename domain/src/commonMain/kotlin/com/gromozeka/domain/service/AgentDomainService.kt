package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiRuntimeOverrides

/**
 * Domain service for managing AI agent definitions.
 *
 * Coordinates agent lifecycle and enforces business rules:
 * - Global agents are available across projects
 * - Project agents may use global and same-project catalog entries
 * - All agents use ordered list of prompts for behavior definition
 *
 * @see AgentDefinition for domain model
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
     * @param runtimeSelection model binding selected for this agent
     * @param tools tool names that are always loaded for this agent (default: empty)
     * @param description optional human-readable agent description
     * @return created agent with assigned ID
     */
    suspend fun createAgent(
        projectId: Project.Id?,
        name: String,
        prompts: List<Prompt.Id>,
        runtimeSelection: AiRuntimeSelection,
        runtimeOverrides: AiRuntimeOverrides = AiRuntimeOverrides(),
        tools: List<String> = emptyList(),
        description: String? = null,
        skills: List<AgentSkill.Id> = emptyList(),
    ): AgentDefinition

    suspend fun duplicateAgent(
        projectId: Project.Id?,
        sourceAgentId: AgentDefinition.Id,
        name: String,
    ): AgentDefinition

    /**
     * Finds agent by unique identifier.
     *
     * @param id agent identifier
     * @return agent if found, null otherwise
     */
    suspend fun findById(id: AgentDefinition.Id): AgentDefinition?

    /**
     * Retrieves all centrally available agents.
     *
     * @return all centrally available agents
     */
    suspend fun findAll(): List<AgentDefinition>

    suspend fun findByProject(projectId: Project.Id): List<AgentDefinition>

    /**
     * Replaces the mutable fields of an existing agent.
     */
    suspend fun update(
        id: AgentDefinition.Id,
        name: String,
        prompts: List<Prompt.Id>,
        description: String? = null,
        skills: List<AgentSkill.Id>,
        runtimeSelection: AiRuntimeSelection,
        runtimeOverrides: AiRuntimeOverrides,
        tools: List<String>,
    ): AgentDefinition?

    /**
     * Deletes agent definition.
     *
     * @param id agent to delete
     */
    suspend fun delete(id: AgentDefinition.Id)

    /**
     * Counts total number of agents.
     *
     * @return count of all agents (builtin and user-created)
     */
    suspend fun count(): Int
}

/**
 * [SPECIFICATION] Resolves an agent's prompts for the current runtime context.
 */
interface AgentPromptAssemblyService {
    suspend fun assembleSystemPrompt(
        agent: AgentDefinition,
        runtimeContext: com.gromozeka.domain.model.RuntimeEnvironmentContext,
    ): List<String>
}
