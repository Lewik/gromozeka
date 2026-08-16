package com.gromozeka.client

import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSecretMutation
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.remote.protocol.AiCatalogResponse
import com.gromozeka.remote.protocol.GetAiCatalogRequest
import com.gromozeka.remote.protocol.SaveAiCatalogRequest
import com.gromozeka.remote.protocol.RemoteDeclarativeStateResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class RemoteAiConfigurationService(
    private val client: GromozekaWsClient,
    private val scope: CoroutineScope,
) : AiConfigurationService {
    private val mutableSnapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(null)
    private var syncJob: Job? = null

    override val snapshotFlow: StateFlow<AiCatalogSnapshot?> = mutableSnapshotFlow.asStateFlow()
    override val snapshot: AiCatalogSnapshot
        get() = checkNotNull(mutableSnapshotFlow.value) { "AI configuration catalog is not loaded" }

    override suspend fun replaceCatalog(
        catalog: AiCatalog,
        expectedRevision: Long,
        secretMutations: List<AiCatalogSecretMutation>,
    ): AiCatalogSnapshot =
        client.requestTyped<SaveAiCatalogRequest, AiCatalogResponse>(
            SaveAiCatalogRequest(catalog, expectedRevision, secretMutations)
        ).snapshot.toDomainSnapshot().also { mutableSnapshotFlow.value = it }

    override suspend fun reload(): AiCatalogSnapshot =
        client.requestTyped<GetAiCatalogRequest, AiCatalogResponse>(GetAiCatalogRequest)
            .snapshot
            .toDomainSnapshot()
            .also { mutableSnapshotFlow.value = it }

    override suspend fun refreshIfChanged() {
        reload()
    }

    fun startSync() {
        if (syncJob != null) return
        syncJob = scope.launch {
            client.observeDeclarativeState(RemoteDeclarativeStateResource.AI_CATALOG) {
                client.requestTyped<GetAiCatalogRequest, AiCatalogResponse>(GetAiCatalogRequest)
                    .snapshot
                    .toDomainSnapshot()
            }.collect { mutableSnapshotFlow.value = it }
        }
    }

    override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime {
        return catalog.resolveRuntime(selection, snapshot.runtimeEnabledConnectionIds)
    }
}
