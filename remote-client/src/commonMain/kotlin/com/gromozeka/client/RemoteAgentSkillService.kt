package com.gromozeka.client

import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.remote.protocol.AgentSkillPackageResponse
import com.gromozeka.remote.protocol.AgentSkillResponse
import com.gromozeka.remote.protocol.AgentSkillsResponse
import com.gromozeka.remote.protocol.DeleteAgentSkillRequest
import com.gromozeka.remote.protocol.ExportAgentSkillRequest
import com.gromozeka.remote.protocol.FindAgentSkillsRequest
import com.gromozeka.remote.protocol.FindAgentSkillRequest
import com.gromozeka.remote.protocol.ImportAgentSkillRequest
import com.gromozeka.remote.protocol.SavedResponse

internal class RemoteAgentSkillService(
    private val client: GromozekaWsClient,
) : AgentSkillDomainService {
    override suspend fun importPackage(
        projectId: Project.Id,
        source: AgentSkillPackageSource,
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

    override suspend fun exportPackage(id: AgentSkill.Id): AgentSkillPackage? =
        client.requestTyped<ExportAgentSkillRequest, AgentSkillPackageResponse>(
            ExportAgentSkillRequest(id)
        ).skillPackage

    override suspend fun delete(id: AgentSkill.Id) {
        client.requestTyped<DeleteAgentSkillRequest, SavedResponse>(DeleteAgentSkillRequest(id))
    }
}
