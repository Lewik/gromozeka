package com.gromozeka.client

import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.remote.protocol.AiCatalogResponse
import com.gromozeka.remote.protocol.GetAiCatalogRequest
import com.gromozeka.remote.protocol.SaveAiCatalogRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class RemoteAiConfigurationService(
    private val client: GromozekaWsClient,
) : AiConfigurationService {
    private val mutableSnapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(null)

    override val snapshotFlow: StateFlow<AiCatalogSnapshot?> = mutableSnapshotFlow.asStateFlow()
    override val snapshot: AiCatalogSnapshot
        get() = checkNotNull(mutableSnapshotFlow.value) { "AI configuration catalog is not loaded" }

    override suspend fun replaceCatalog(
        catalog: AiCatalog,
        expectedRevision: Long,
    ): AiCatalogSnapshot =
        client.requestTyped<SaveAiCatalogRequest, AiCatalogResponse>(
            SaveAiCatalogRequest(catalog, expectedRevision)
        ).snapshot.also { mutableSnapshotFlow.value = it }

    override suspend fun reload(): AiCatalogSnapshot =
        client.requestTyped<GetAiCatalogRequest, AiCatalogResponse>(GetAiCatalogRequest)
            .snapshot
            .also { mutableSnapshotFlow.value = it }

    override suspend fun refreshIfChanged() {
        reload()
    }

    override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime {
        val configuration = catalog.modelConfigurations.firstOrNull {
            it.id == selection.modelConfigurationId
        } ?: error("AI model configuration not found: ${selection.modelConfigurationId.value}")
        val connection = catalog.connections.firstOrNull { it.id == configuration.connectionId }
            ?: error("AI connection not found: ${configuration.connectionId.value}")
        require(connection.enabled || connection.id in snapshot.runtimeEnabledConnectionIds) {
            "AI connection is disabled: ${connection.id.value}"
        }
        require(configuration.enabled) {
            "AI model configuration is disabled: ${configuration.id.value}"
        }
        return ResolvedAiRuntime(connection, configuration)
    }
}
