package com.gromozeka.domain.repository

import com.gromozeka.domain.model.AgentCatalogImportProposal
import com.gromozeka.domain.model.AgentCatalogImportResult
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.Workspace
import kotlinx.datetime.Instant

interface AgentCatalogImportRepository {
    suspend fun report(proposal: AgentCatalogImportProposal): AgentCatalogImportProposal

    suspend fun find(
        workspaceId: Workspace.Id,
        workerId: String,
    ): AgentCatalogImportProposal?

    suspend fun findPending(projectId: Project.Id): List<AgentCatalogImportProposal>

    suspend fun import(
        workspaceId: Workspace.Id,
        workerId: String,
        catalogHash: String,
        prompts: List<Prompt>,
        agents: List<AgentDefinition>,
        decidedAt: Instant,
    ): AgentCatalogImportResult

    suspend fun skip(
        workspaceId: Workspace.Id,
        workerId: String,
        catalogHash: String,
        decidedAt: Instant,
    )

    suspend fun acknowledge(
        workspaceId: Workspace.Id,
        workerId: String,
        catalogHash: String,
        status: AgentCatalogImportProposal.Status,
    )

    suspend fun withdraw(
        workspaceId: Workspace.Id,
        workerId: String,
    )
}
