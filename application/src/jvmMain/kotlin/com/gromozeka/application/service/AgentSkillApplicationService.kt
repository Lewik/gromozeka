package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.domain.repository.AgentSkillRepository
import com.gromozeka.domain.repository.ProjectRepository
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.shared.uuid.uuid7
import kotlinx.datetime.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AgentSkillApplicationService(
    private val skillRepository: AgentSkillRepository,
    private val agentRepository: AgentRepository,
    private val projectRepository: ProjectRepository,
) : AgentSkillDomainService {
    private val parser = AgentSkillPackageParser()

    @Transactional
    override suspend fun importPackage(
        projectId: Project.Id,
        source: AgentSkillPackageSource,
    ): AgentSkill {
        require(projectRepository.exists(projectId)) {
            "Project not found: ${projectId.value}"
        }
        val parsed = parser.parse(source)
        val existing = skillRepository.findByName(projectId, parsed.name)
        if (existing?.contentHash == parsed.contentHash) {
            return existing
        }
        val now = Clock.System.now()
        val skill = AgentSkill(
            id = existing?.id ?: AgentSkill.Id("project:${projectId.value}:skill:${uuid7()}"),
            projectId = projectId,
            name = parsed.name,
            description = parsed.description,
            instructions = parsed.instructions,
            license = parsed.license,
            compatibility = parsed.compatibility,
            metadata = parsed.metadata,
            allowedTools = parsed.allowedTools,
            contentHash = parsed.contentHash,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        require(skill.id.value.length <= MAX_PERSISTED_ID_LENGTH) {
            "Generated Agent Skill id is too long: ${skill.id.value}"
        }
        return skillRepository.savePackage(
            AgentSkillPackage(
                skill = skill,
                files = parsed.files,
            )
        ).skill
    }

    override suspend fun findById(id: AgentSkill.Id): AgentSkill? =
        skillRepository.findById(id)

    override suspend fun findByProject(projectId: Project.Id): List<AgentSkill> =
        skillRepository.findByProject(projectId)

    override suspend fun exportPackage(id: AgentSkill.Id): AgentSkillPackage? =
        skillRepository.findPackage(id)

    @Transactional
    override suspend fun delete(id: AgentSkill.Id) {
        val skill = skillRepository.findById(id)
            ?: throw IllegalArgumentException("Agent skill not found: ${id.value}")
        val assignedAgents = agentRepository.findByProject(skill.projectId)
            .filter { id in it.skills }
        require(assignedAgents.isEmpty()) {
            "Agent Skill '${skill.name}' is assigned to: ${assignedAgents.joinToString { it.name }}"
        }
        skillRepository.delete(id)
    }

    private companion object {
        const val MAX_PERSISTED_ID_LENGTH = 255
    }
}
