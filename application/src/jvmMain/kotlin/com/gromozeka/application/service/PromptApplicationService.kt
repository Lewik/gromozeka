package com.gromozeka.application.service

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.domain.repository.PromptRepository
import com.gromozeka.domain.service.PromptAssemblyService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.DeclarativeStateChangePublisher
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.domain.service.NoOpDeclarativeStateChangePublisher
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Clock
import org.springframework.stereotype.Service

@Service
class PromptApplicationService(
    private val promptRepository: PromptRepository,
    private val agentRepository: AgentRepository,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val stateChanges: DeclarativeStateChangePublisher = NoOpDeclarativeStateChangePublisher,
) : PromptDomainService, PromptAssemblyService {
    override suspend fun assembleSystemPrompt(
        promptIds: List<Prompt.Id>,
        runtimeContext: RuntimeEnvironmentContext,
    ): List<String> =
        promptIds.map { id ->
            if (id.value == ENV_PROMPT_ID) {
                systemPromptBuilder.buildEnvironmentInfo(runtimeContext)
            } else {
                val prompt = promptRepository.findById(id)
                    ?: error("Required prompt '${id.value}' is unavailable")
                require(prompt.type is Prompt.Type.Global || prompt.projectId == runtimeContext.projectId()) {
                    "Prompt '${id.value}' does not belong to the runtime project"
                }
                prompt.content
            }
        }

    override suspend fun findById(id: Prompt.Id): Prompt? =
        promptRepository.findById(id)

    override suspend fun findAll(): List<Prompt> =
        promptRepository.findAll()

    override suspend fun findByProject(projectId: Project.Id): List<Prompt> =
        promptRepository.findByProject(projectId)

    override suspend fun createPrompt(
        projectId: Project.Id?,
        name: String,
        content: String,
    ): Prompt {
        validate(name, content)
        val now = Clock.System.now()
        return promptRepository.save(
            Prompt(
                id = Prompt.Id(
                    if (projectId == null) {
                        "global:prompt:${uuid7()}"
                    } else {
                        "project:${projectId.value}:prompt:${uuid7()}"
                    }
                ),
                projectId = projectId,
                name = name.trim(),
                content = content,
                type = if (projectId == null) Prompt.Type.Global else Prompt.Type.Project,
                createdAt = now,
                updatedAt = now,
            )
        ).also { stateChanges.publish(DeclarativeStateKey.prompts) }
    }

    override suspend fun updatePrompt(
        id: Prompt.Id,
        name: String,
        content: String,
    ): Prompt? {
        validate(name, content)
        val current = promptRepository.findById(id) ?: return null
        return promptRepository.save(
            current.copy(
                name = name.trim(),
                content = content,
                updatedAt = Clock.System.now(),
            )
        ).also { stateChanges.publish(DeclarativeStateKey.prompts) }
    }

    override suspend fun deletePrompt(id: Prompt.Id) {
        val referencingAgents = agentRepository.findAll().filter { id in it.prompts }
        require(referencingAgents.isEmpty()) {
            "Prompt is used by agents: ${referencingAgents.joinToString { it.name }}"
        }
        promptRepository.delete(id)
        stateChanges.publish(DeclarativeStateKey.prompts)
    }

    private fun validate(name: String, content: String) {
        require(name.isNotBlank()) { "Prompt name must not be blank" }
        require(content.isNotBlank()) { "Prompt content must not be blank" }
    }

    private companion object {
        const val ENV_PROMPT_ID = "env"
    }
}

private fun RuntimeEnvironmentContext.projectId(): Project.Id? =
    when (this) {
        is RuntimeEnvironmentContext.Standalone -> null
        is RuntimeEnvironmentContext.ProjectBound -> project.id
        is RuntimeEnvironmentContext.WorkspaceBound -> project.id
    }
