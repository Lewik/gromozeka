package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import kotlinx.coroutines.flow.StateFlow

interface AiConfigurationProvider {
    val snapshotFlow: StateFlow<AiCatalogSnapshot?>
    val snapshot: AiCatalogSnapshot
    val catalog: AiCatalog
        get() = snapshot.catalog

    fun runtimeSelectionFor(purpose: AiRuntimeAssignment.Purpose): AiRuntimeSelection =
        catalog.runtimeSelectionFor(purpose)
            ?: error("AI runtime assignment not found: ${purpose.name}")

    fun resolveAiRuntime(purpose: AiRuntimeAssignment.Purpose): ResolvedAiRuntime =
        resolveAiRuntime(runtimeSelectionFor(purpose))

    fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime
}

interface AiConfigurationService : AiConfigurationProvider {
    suspend fun replaceCatalog(
        catalog: AiCatalog,
        expectedRevision: Long = snapshot.revision,
    ): AiCatalogSnapshot

    suspend fun reload(): AiCatalogSnapshot

    suspend fun refreshIfChanged()
}

interface AiCatalogManagementService {
    suspend fun upsertConnection(
        connection: AiConnection,
        expectedRevision: Long,
        preserveExistingSecret: Boolean = true,
    ): AiCatalogSnapshot

    suspend fun deleteConnection(
        connectionId: AiConnection.Id,
        expectedRevision: Long,
    ): AiCatalogSnapshot

    suspend fun upsertModelSpec(
        modelSpec: AiModelSpec,
        expectedRevision: Long,
    ): AiCatalogSnapshot

    suspend fun deleteModelSpec(
        provider: AiProvider,
        modelId: String,
        expectedRevision: Long,
    ): AiCatalogSnapshot

    suspend fun upsertModelConfiguration(
        configuration: AiModelConfiguration,
        expectedRevision: Long,
    ): AiCatalogSnapshot

    suspend fun deleteModelConfiguration(
        configurationId: AiModelConfiguration.Id,
        expectedRevision: Long,
    ): AiCatalogSnapshot

    suspend fun setRuntimeAssignment(
        assignment: AiRuntimeAssignment,
        expectedRevision: Long,
    ): AiCatalogSnapshot

    suspend fun setDefaultAgent(
        agentId: AgentDefinition.Id,
        expectedRevision: Long,
    ): AiCatalogSnapshot
}

data class ResolvedAiRuntime(
    val connection: AiConnection,
    val modelConfiguration: AiModelConfiguration,
)
