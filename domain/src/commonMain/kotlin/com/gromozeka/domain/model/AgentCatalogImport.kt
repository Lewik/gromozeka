package com.gromozeka.domain.model

import com.gromozeka.domain.model.ai.AiRuntimeOverrides
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class AgentCatalogSourcePrompt(
    val sourcePath: String,
    val name: String,
    val content: String,
)

@Serializable
data class AgentCatalogSourceAgent(
    val sourcePath: String,
    val name: String,
    val prompts: List<String>,
    val runtimeSelection: AiRuntimeSelection,
    val runtimeOverrides: AiRuntimeOverrides = AiRuntimeOverrides(),
    val tools: List<String> = emptyList(),
    val description: String? = null,
)

@Serializable
data class AgentCatalogSnapshot(
    val projectId: Project.Id,
    val workspaceId: Workspace.Id,
    val workspaceName: String,
    val workerId: String,
    val catalogHash: String,
    val prompts: List<AgentCatalogSourcePrompt>,
    val agents: List<AgentCatalogSourceAgent>,
    val scannerError: String? = null,
    val detectedAt: Instant,
)

@Serializable
data class AgentCatalogImportProposal(
    val projectId: Project.Id,
    val workspaceId: Workspace.Id,
    val workspaceName: String,
    val workerId: String,
    val catalogHash: String,
    val prompts: List<AgentCatalogSourcePrompt>,
    val agents: List<AgentCatalogSourceAgent>,
    val validationError: String? = null,
    val status: Status,
    val detectedAt: Instant,
    val decidedAt: Instant? = null,
) {
    @Serializable
    enum class Status {
        PENDING,
        IMPORTED,
        SKIPPED,
    }
}

@Serializable
data class AgentCatalogImportResult(
    val importedPrompts: Int,
    val importedAgents: Int,
)
