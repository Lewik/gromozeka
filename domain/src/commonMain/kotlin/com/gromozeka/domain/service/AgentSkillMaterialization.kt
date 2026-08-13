package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.WorkspaceMount
import kotlinx.serialization.Serializable

@Serializable
data class AgentSkillPackageRequest(
    val projectId: Project.Id,
    val agentDefinitionId: AgentDefinition.Id,
    val workspaceMountId: WorkspaceMount.Id,
    val skillName: String,
) {
    init {
        require(skillName.isNotBlank()) { "Agent Skill name must not be blank" }
    }
}

fun interface AgentSkillPackageClient {
    suspend fun fetch(request: AgentSkillPackageRequest): AgentSkillPackage
}

data class AgentSkillMaterializationResult(
    val skill: AgentSkill,
    val directoryPath: String,
    val fileCount: Int,
    val sizeBytes: Long,
    val alreadyPresent: Boolean,
)

interface AgentSkillMaterializationService {
    suspend fun materialize(
        request: AgentSkillPackageRequest,
        workspaceRootPath: String,
    ): AgentSkillMaterializationResult
}
