package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface AgentSkillDomainService {
    suspend fun importPackage(
        projectId: Project.Id,
        source: AgentSkillPackageSource,
        actorUserId: User.Id? = null,
    ): AgentSkill

    suspend fun findById(id: AgentSkill.Id): AgentSkill?

    suspend fun findByProject(projectId: Project.Id): List<AgentSkill>

    fun observeByProject(projectId: Project.Id): Flow<List<AgentSkill>> = flow {
        emit(findByProject(projectId))
    }

    suspend fun exportPackage(id: AgentSkill.Id): AgentSkillPackage?

    suspend fun reanalyzeMaterialization(
        id: AgentSkill.Id,
        actorUserId: User.Id? = null,
    ): AgentSkill

    suspend fun setMaterializationPlan(
        id: AgentSkill.Id,
        policy: AgentSkill.MaterializationPlan.Policy,
        reason: String,
    ): AgentSkill

    suspend fun delete(id: AgentSkill.Id)
}
