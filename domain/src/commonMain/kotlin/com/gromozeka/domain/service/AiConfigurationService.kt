package com.gromozeka.domain.service

import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
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

data class ResolvedAiRuntime(
    val connection: AiConnection,
    val modelConfiguration: AiModelConfiguration,
)
