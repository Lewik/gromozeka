package com.gromozeka.application.service

import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.RuntimeEnvironmentContext
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.PromptAssemblyService
import com.gromozeka.domain.repository.PromptRepository
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service

@Service
class PromptApplicationService(
    private val promptRepository: PromptRepository,
    private val systemPromptBuilder: SystemPromptBuilder,
) : PromptDomainService, PromptAssemblyService {

    override suspend fun assembleSystemPrompt(
        promptIds: List<Prompt.Id>,
        runtimeContext: RuntimeEnvironmentContext,
    ): List<String> {
        return promptIds.map { id ->
            when {
                id.value == "env" -> {
                    systemPromptBuilder.buildEnvironmentInfo(runtimeContext)
                }

                else -> {
                    val prompt = promptRepository.findById(id)
                    checkNotNull(prompt) {
                        "Required prompt '${id.value}' is unavailable on worker ${runtimeContext.workerId}"
                    }
                    require(prompt.type is Prompt.Type.Builtin || prompt.projectId == runtimeContext.projectId()) {
                        "Prompt '${id.value}' does not belong to the runtime project"
                    }
                    prompt.content
                }
            }
        }
    }

    override suspend fun findById(id: Prompt.Id): Prompt? =
        promptRepository.findById(id)

    override suspend fun findAll(): List<Prompt> {
        return promptRepository.findAll()
    }

    override suspend fun findByProject(projectId: Project.Id): List<Prompt> =
        promptRepository.findByProject(projectId)

    override suspend fun createProjectPrompt(projectId: Project.Id, name: String, content: String): Prompt {
        val now = Clock.System.now()

        val prompt = Prompt(
            id = Prompt.Id("project:${projectId.value}:${uuid7()}"),
            projectId = projectId,
            name = name,
            content = content,
            type = Prompt.Type.Project,
            createdAt = now,
            updatedAt = now
        )

        return promptRepository.save(prompt)
    }
}

private fun RuntimeEnvironmentContext.projectId(): Project.Id? =
    when (this) {
        is RuntimeEnvironmentContext.Standalone -> null
        is RuntimeEnvironmentContext.ProjectBound -> project.id
        is RuntimeEnvironmentContext.WorkspaceBound -> project.id
    }
