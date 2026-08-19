package com.gromozeka.client

import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.remote.protocol.AgentSkillPackageResponse
import com.gromozeka.remote.protocol.AgentSkillResponse
import com.gromozeka.remote.protocol.AgentSkillsResponse
import com.gromozeka.remote.protocol.DeleteAgentSkillRequest
import com.gromozeka.remote.protocol.ExportAgentSkillRequest
import com.gromozeka.remote.protocol.FindAgentSkillsRequest
import com.gromozeka.remote.protocol.FindAgentSkillRequest
import com.gromozeka.remote.protocol.ImportAgentSkillRequest
import com.gromozeka.remote.protocol.ReanalyzeAgentSkillMaterializationRequest
import com.gromozeka.remote.protocol.SavedResponse
import com.gromozeka.remote.protocol.SetAgentSkillMaterializationPlanRequest
import com.gromozeka.remote.protocol.RemoteDeclarativeStateResource
import kotlinx.coroutines.flow.Flow

internal class RemoteAgentSkillService(
    private val client: GromozekaWsClient,
) : AgentSkillDomainService {
    override suspend fun importPackage(
        projectId: Project.Id,
        source: AgentSkillPackageSource,
        actorUserId: User.Id?,
    ): AgentSkill =
        client.requestTyped<ImportAgentSkillRequest, AgentSkillResponse>(
            ImportAgentSkillRequest(projectId, source)
        ).skill ?: error("Server returned null Agent Skill after import")

    override suspend fun findById(id: AgentSkill.Id): AgentSkill? =
        client.requestTyped<FindAgentSkillRequest, AgentSkillResponse>(
            FindAgentSkillRequest(id)
        ).skill

    override suspend fun findByProject(projectId: Project.Id): List<AgentSkill> =
        client.requestTyped<FindAgentSkillsRequest, AgentSkillsResponse>(
            FindAgentSkillsRequest(projectId)
        ).skills

    override fun observeByProject(projectId: Project.Id): Flow<List<AgentSkill>> =
        client.observeDeclarativeState(
            RemoteDeclarativeStateResource.PROJECT_AGENT_SKILLS,
            projectId.value,
        ) { findByProject(projectId) }

    override suspend fun exportPackage(id: AgentSkill.Id): AgentSkillPackage? =
        client.requestTyped<ExportAgentSkillRequest, AgentSkillPackageResponse>(
            ExportAgentSkillRequest(id)
        ).skillPackage

    override suspend fun reanalyzeMaterialization(
        id: AgentSkill.Id,
        actorUserId: User.Id?,
    ): AgentSkill =
        client.requestTyped<ReanalyzeAgentSkillMaterializationRequest, AgentSkillResponse>(
            ReanalyzeAgentSkillMaterializationRequest(id)
        ).skill ?: error("Server returned null Agent Skill after materialization analysis")

    override suspend fun setMaterializationPlan(
        id: AgentSkill.Id,
        policy: AgentSkill.MaterializationPlan.Policy,
        reason: String,
    ): AgentSkill =
        client.requestTyped<SetAgentSkillMaterializationPlanRequest, AgentSkillResponse>(
            SetAgentSkillMaterializationPlanRequest(id, policy, reason)
        ).skill ?: error("Server returned null Agent Skill after materialization override")

    override suspend fun delete(id: AgentSkill.Id) {
        client.requestTyped<DeleteAgentSkillRequest, SavedResponse>(DeleteAgentSkillRequest(id))
    }
}
