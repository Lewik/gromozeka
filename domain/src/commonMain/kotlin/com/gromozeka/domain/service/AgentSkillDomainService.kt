package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.domain.model.Project

interface AgentSkillDomainService {
    suspend fun importPackage(
        projectId: Project.Id,
        source: AgentSkillPackageSource,
    ): AgentSkill

    suspend fun findById(id: AgentSkill.Id): AgentSkill?

    suspend fun findByProject(projectId: Project.Id): List<AgentSkill>

    suspend fun exportPackage(id: AgentSkill.Id): AgentSkillPackage?

    suspend fun delete(id: AgentSkill.Id)
}
