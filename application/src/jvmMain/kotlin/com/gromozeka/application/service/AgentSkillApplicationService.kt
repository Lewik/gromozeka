package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.domain.repository.AgentSkillRepository
import com.gromozeka.domain.repository.ProjectRepository
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.domain.service.DeclarativeStateChangePublisher
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.domain.service.NoOpDeclarativeStateChangePublisher
import com.gromozeka.shared.uuid.uuid7
import kotlin.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AgentSkillApplicationService(
    private val skillRepository: AgentSkillRepository,
    private val agentRepository: AgentRepository,
    private val projectRepository: ProjectRepository,
    private val materializationPlanAnalyzer: AgentSkillMaterializationPlanAnalyzer,
    private val stateChanges: DeclarativeStateChangePublisher = NoOpDeclarativeStateChangePublisher,
) : AgentSkillDomainService {
    private val parser = AgentSkillPackageParser()

    override suspend fun importPackage(
        projectId: Project.Id,
        source: AgentSkillPackageSource,
        actorUserId: User.Id?,
    ): AgentSkill {
        require(projectRepository.exists(projectId)) {
            "Project not found: ${projectId.value}"
        }
        val parsed = parser.parse(source)
        val existing = skillRepository.findByName(projectId, parsed.name)
        if (existing?.contentHash == parsed.contentHash) {
            return existing
        }
        val materializationPlan = materializationPlanAnalyzer.analyze(parsed, actorUserId)
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
            materializationPlan = materializationPlan,
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
        ).skill.also {
            stateChanges.publish(DeclarativeStateKey.projectAgentSkills(projectId))
        }
    }

    override suspend fun findById(id: AgentSkill.Id): AgentSkill? =
        skillRepository.findById(id)

    override suspend fun findByProject(projectId: Project.Id): List<AgentSkill> =
        skillRepository.findByProject(projectId)

    override suspend fun exportPackage(id: AgentSkill.Id): AgentSkillPackage? =
        skillRepository.findPackage(id)

    override suspend fun reanalyzeMaterialization(
        id: AgentSkill.Id,
        actorUserId: User.Id?,
    ): AgentSkill {
        val current = skillRepository.findPackage(id)
            ?: throw IllegalArgumentException("Agent skill not found: ${id.value}")
        val parsed = parser.parse(
            AgentSkillPackageSource(
                directoryName = current.skill.name,
                files = current.files,
            )
        )
        val plan = materializationPlanAnalyzer.analyze(parsed, actorUserId)
        return saveMaterializationPlan(current, plan)
    }

    override suspend fun setMaterializationPlan(
        id: AgentSkill.Id,
        policy: AgentSkill.MaterializationPlan.Policy,
        reason: String,
    ): AgentSkill {
        require(reason.isNotBlank()) { "Agent Skill materialization reason must not be blank" }
        val current = skillRepository.findPackage(id)
            ?: throw IllegalArgumentException("Agent skill not found: ${id.value}")
        return saveMaterializationPlan(
            current,
            AgentSkill.MaterializationPlan(
                policy = policy,
                reason = reason.trim(),
            ),
        )
    }

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
        stateChanges.publish(DeclarativeStateKey.projectAgentSkills(skill.projectId))
    }

    private suspend fun saveMaterializationPlan(
        current: AgentSkillPackage,
        plan: AgentSkill.MaterializationPlan,
    ): AgentSkill = skillRepository.savePackage(
        current.copy(
            skill = current.skill.copy(
                materializationPlan = plan,
                updatedAt = Clock.System.now(),
            )
        )
    ).skill.also {
        stateChanges.publish(DeclarativeStateKey.projectAgentSkills(it.projectId))
    }

    private companion object {
        const val MAX_PERSISTED_ID_LENGTH = 255
    }
}
