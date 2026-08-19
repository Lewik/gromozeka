package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkspaceMount
import kotlinx.serialization.Serializable

@Serializable
data class AgentSkillPackageRequest(
    val projectId: Project.Id,
    val agentDefinitionId: AgentDefinition.Id,
    val workspaceMountId: WorkspaceMount.Id? = null,
    val skillId: AgentSkill.Id,
    val contentHash: String,
) {
    init {
        require(contentHash.matches(CONTENT_HASH_PATTERN)) {
            "Agent Skill content hash must be a lowercase SHA-256 value"
        }
    }

    private companion object {
        val CONTENT_HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}

@Serializable
data class AgentSkillDirectoryImportRequest(
    val projectId: Project.Id,
    val agentDefinitionId: AgentDefinition.Id,
    val actorUserId: User.Id,
    val source: AgentSkillPackageSource,
    val expectedContentHash: String? = null,
) {
    init {
        require(expectedContentHash == null || expectedContentHash.matches(CONTENT_HASH_PATTERN)) {
            "Expected Agent Skill content hash must be a lowercase SHA-256 value"
        }
    }

    private companion object {
        val CONTENT_HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}

fun interface AgentSkillDirectoryImportClient {
    suspend fun importPackage(request: AgentSkillDirectoryImportRequest): AgentSkill
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
