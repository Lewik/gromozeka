package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiRuntimeOverrides
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.domain.repository.AgentSkillRepository
import com.gromozeka.domain.repository.PromptRepository
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentPromptAssemblyService
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.DeclarativeStateChangePublisher
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.domain.service.NoOpDeclarativeStateChangePublisher
import com.gromozeka.domain.service.PromptAssemblyService
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AgentApplicationService(
    private val agentRepository: AgentRepository,
    private val promptRepository: PromptRepository,
    private val skillRepository: AgentSkillRepository,
    private val promptAssemblyService: PromptAssemblyService,
    private val aiConfigurationProvider: AiConfigurationProvider,
    private val stateChanges: DeclarativeStateChangePublisher = NoOpDeclarativeStateChangePublisher,
) : AgentDomainService, AgentPromptAssemblyService {
    @Transactional
    override suspend fun createAgent(
        projectId: Project.Id?,
        name: String,
        prompts: List<Prompt.Id>,
        runtimeSelection: AiRuntimeSelection,
        runtimeOverrides: AiRuntimeOverrides,
        tools: List<String>,
        description: String?,
        skills: List<AgentSkill.Id>,
    ): AgentDefinition {
        validateDefinition(projectId, name, prompts, skills, runtimeSelection, tools)
        val now = Clock.System.now()
        return agentRepository.save(
            AgentDefinition(
                id = AgentDefinition.Id(
                    if (projectId == null) {
                        "global:agent:${uuid7()}"
                    } else {
                        "project:${projectId.value}:agent:${uuid7()}"
                    }
                ),
                projectId = projectId,
                name = name.trim(),
                prompts = prompts,
                skills = skills,
                runtimeSelection = runtimeSelection,
                runtimeOverrides = runtimeOverrides,
                tools = tools,
                description = description?.trim()?.takeIf(String::isNotBlank),
                type = if (projectId == null) AgentDefinition.Type.Global else AgentDefinition.Type.Project,
                createdAt = now,
                updatedAt = now,
            )
        ).also { stateChanges.publish(DeclarativeStateKey.agents) }
    }

    override suspend fun duplicateAgent(
        projectId: Project.Id?,
        sourceAgentId: AgentDefinition.Id,
        name: String,
    ): AgentDefinition {
        val source = agentRepository.findById(sourceAgentId)
            ?: error("Agent not found: ${sourceAgentId.value}")
        val skills = if (projectId != null && projectId == source.projectId) source.skills else emptyList()
        return createAgent(
            projectId = projectId,
            name = name,
            prompts = source.prompts,
            runtimeSelection = source.runtimeSelection,
            runtimeOverrides = source.runtimeOverrides,
            tools = source.tools,
            description = source.description,
            skills = skills,
        )
    }

    override suspend fun assembleSystemPrompt(
        agent: AgentDefinition,
        runtimeContext: RuntimeEnvironmentContext,
    ): List<String> =
        promptAssemblyService.assembleSystemPrompt(agent.prompts, runtimeContext)

    override suspend fun findById(id: AgentDefinition.Id): AgentDefinition? =
        agentRepository.findById(id)

    override suspend fun findAll(): List<AgentDefinition> =
        agentRepository.findAll()

    override suspend fun findByProject(projectId: Project.Id): List<AgentDefinition> =
        agentRepository.findByProject(projectId)

    @Transactional
    override suspend fun update(
        id: AgentDefinition.Id,
        name: String,
        prompts: List<Prompt.Id>,
        description: String?,
        skills: List<AgentSkill.Id>,
        runtimeSelection: AiRuntimeSelection,
        runtimeOverrides: AiRuntimeOverrides,
        tools: List<String>,
    ): AgentDefinition? {
        val current = agentRepository.findById(id) ?: return null
        val updated = current.copy(
            name = name.trim(),
            prompts = prompts,
            description = description?.trim()?.takeIf(String::isNotBlank),
            skills = skills,
            runtimeSelection = runtimeSelection,
            runtimeOverrides = runtimeOverrides,
            tools = tools,
            updatedAt = Clock.System.now(),
        )
        validateDefinition(
            projectId = updated.projectId,
            name = updated.name,
            prompts = updated.prompts,
            skills = updated.skills,
            runtimeSelection = updated.runtimeSelection,
            tools = updated.tools,
        )
        return agentRepository.save(updated).also {
            stateChanges.publish(DeclarativeStateKey.agents)
        }
    }

    @Transactional
    override suspend fun delete(id: AgentDefinition.Id) {
        require(id != aiConfigurationProvider.catalog.defaultAgentId) {
            "Default agent cannot be deleted"
        }
        agentRepository.delete(id)
        stateChanges.publish(DeclarativeStateKey.agents)
    }

    override suspend fun count(): Int = agentRepository.count()

    private suspend fun validateDefinition(
        projectId: Project.Id?,
        name: String,
        prompts: List<Prompt.Id>,
        skills: List<AgentSkill.Id>,
        runtimeSelection: AiRuntimeSelection,
        tools: List<String>,
    ) {
        require(name.isNotBlank()) { "Agent name must not be blank" }
        require(prompts.isNotEmpty()) { "Agent must contain at least one prompt or runtime environment context" }
        require(prompts.distinct().size == prompts.size) { "Agent prompts must not contain duplicates" }
        require(tools.none(String::isBlank)) { "Agent tool names must not be blank" }
        require(tools.distinct().size == tools.size) { "Agent tools must not contain duplicates" }
        val runtime = aiConfigurationProvider.resolveAiRuntime(runtimeSelection)
        val modelSpec = aiConfigurationProvider.catalog.modelSpecFor(runtime.modelConfiguration)
            ?: error("AI model spec not found: ${runtime.modelConfiguration.providerModelId}")
        require(AiModelCapability.TEXT_GENERATION in modelSpec.capabilities) {
            "Agent model must support text generation: ${runtime.modelConfiguration.id.value}"
        }

        val staticPromptIds = prompts.filterNot { it.value == ENV_PROMPT_ID }
        val resolvedPrompts = staticPromptIds.map { promptId ->
            promptRepository.findById(promptId)
                ?: error("Prompt not found: ${promptId.value}")
        }
        require(resolvedPrompts.all { prompt ->
            prompt.type is Prompt.Type.Global || prompt.projectId == projectId
        }) {
            "Agent may reference only global prompts and prompts from its own project"
        }
        if (projectId == null) {
            require(resolvedPrompts.all { it.type is Prompt.Type.Global }) {
                "Global agent may reference only global prompts"
            }
        }

        validateSkills(projectId, skills)
    }

    private suspend fun validateSkills(
        projectId: Project.Id?,
        skillIds: List<AgentSkill.Id>,
    ) {
        require(skillIds.distinct().size == skillIds.size) {
            "Agent skills must not contain duplicates"
        }
        if (projectId == null) {
            require(skillIds.isEmpty()) { "Global agents cannot reference project skills" }
            return
        }
        val skills = skillRepository.findByIds(skillIds)
        require(skills.size == skillIds.size) {
            val foundIds = skills.mapTo(mutableSetOf()) { it.id }
            val missing = skillIds.filterNot { it in foundIds }
            "Agent skills not found: ${missing.joinToString { it.value }}"
        }
        require(skills.all { it.projectId == projectId }) {
            "Agent skills must belong to project ${projectId.value}"
        }
    }

    private companion object {
        const val ENV_PROMPT_ID = "env"
    }
}
