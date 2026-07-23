package com.gromozeka.domain.repository

import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.Project

interface AgentSkillRepository {
    suspend fun savePackage(skillPackage: AgentSkillPackage): AgentSkillPackage

    suspend fun findById(id: AgentSkill.Id): AgentSkill?

    suspend fun findByIds(ids: List<AgentSkill.Id>): List<AgentSkill>

    suspend fun findByName(projectId: Project.Id, name: String): AgentSkill?

    suspend fun findByProject(projectId: Project.Id): List<AgentSkill>

    suspend fun findPackage(id: AgentSkill.Id): AgentSkillPackage?

    suspend fun delete(id: AgentSkill.Id)
}
