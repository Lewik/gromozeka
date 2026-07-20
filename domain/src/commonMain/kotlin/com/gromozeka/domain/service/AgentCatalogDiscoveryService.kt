package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentCatalogImportProposal
import com.gromozeka.domain.model.AgentCatalogSnapshot
import com.gromozeka.domain.model.Workspace

interface AgentCatalogDiscoveryService {
    suspend fun report(snapshot: AgentCatalogSnapshot): AgentCatalogImportProposal

    suspend fun find(
        workspaceId: Workspace.Id,
        workerId: String,
    ): AgentCatalogImportProposal?

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
