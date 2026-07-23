package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ai.AiRuntimeSelection

/**
 * Domain service for managing AI agent definitions.
 *
 * Coordinates agent lifecycle and enforces business rules:
 * - Builtin agents cannot be deleted (protected system agents)
 * - Prompts and descriptions can be updated independently
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
     * @param tools list of tool names available to this agent (default: empty)
     * @param description optional human-readable agent description
     * @return created agent with assigned ID
     */
    suspend fun createAgent(
        projectId: Project.Id,
        name: String,
        prompts: List<Prompt.Id>,
        runtimeSelection: AiRuntimeSelection,
        tools: List<String> = emptyList(),
        description: String? = null,
        skills: List<AgentSkill.Id> = emptyList(),
    ): AgentDefinition

    /**
     * Creates an independent project copy of a builtin agent.
     *
     * Selected builtin prompts are copied into the project. Existing prompts
     * from the target project and the runtime `env` prompt remain references.
     */
    suspend fun copyBuiltinAgent(
        projectId: Project.Id,
        sourceAgentId: AgentDefinition.Id,
        name: String,
        prompts: List<Prompt.Id>,
        description: String? = null,
        skills: List<AgentSkill.Id> = emptyList(),
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
        id: AgentDefinition.Id,
        prompts: List<Prompt.Id>? = null,
        description: String? = null,
        skills: List<AgentSkill.Id>? = null,
    ): AgentDefinition?

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
