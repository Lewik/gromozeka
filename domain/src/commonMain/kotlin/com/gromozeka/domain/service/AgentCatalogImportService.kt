package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentCatalogImportProposal
import com.gromozeka.domain.model.AgentCatalogImportResult
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace

interface AgentCatalogImportService {
    suspend fun findPending(projectId: Project.Id): List<AgentCatalogImportProposal>

    suspend fun import(
        workspaceId: Workspace.Id,
        workerId: String,
        catalogHash: String,
    ): AgentCatalogImportResult

    suspend fun skip(
        workspaceId: Workspace.Id,
        workerId: String,
        catalogHash: String,
    )
}
