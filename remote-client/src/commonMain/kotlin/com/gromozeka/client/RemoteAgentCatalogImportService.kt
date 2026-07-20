package com.gromozeka.client

import com.gromozeka.domain.model.AgentCatalogImportProposal
import com.gromozeka.domain.model.AgentCatalogImportResult
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.service.AgentCatalogImportService
import com.gromozeka.remote.protocol.AgentCatalogImportProposalsResponse
import com.gromozeka.remote.protocol.AgentCatalogImportResultResponse
import com.gromozeka.remote.protocol.FindPendingAgentCatalogImportsRequest
import com.gromozeka.remote.protocol.ImportAgentCatalogRequest
import com.gromozeka.remote.protocol.SavedResponse
import com.gromozeka.remote.protocol.SkipAgentCatalogImportRequest

internal class RemoteAgentCatalogImportService(
    private val client: GromozekaWsClient,
) : AgentCatalogImportService {
    override suspend fun findPending(projectId: Project.Id): List<AgentCatalogImportProposal> =
        client.requestTyped<FindPendingAgentCatalogImportsRequest, AgentCatalogImportProposalsResponse>(
            FindPendingAgentCatalogImportsRequest(projectId)
        ).proposals

    override suspend fun import(
        workspaceId: Workspace.Id,
        workerId: String,
        catalogHash: String,
    ): AgentCatalogImportResult =
        client.requestTyped<ImportAgentCatalogRequest, AgentCatalogImportResultResponse>(
            ImportAgentCatalogRequest(workspaceId, workerId, catalogHash)
        ).result

    override suspend fun skip(
        workspaceId: Workspace.Id,
        workerId: String,
        catalogHash: String,
    ) {
        client.requestTyped<SkipAgentCatalogImportRequest, SavedResponse>(
            SkipAgentCatalogImportRequest(workspaceId, workerId, catalogHash)
        )
    }

}
