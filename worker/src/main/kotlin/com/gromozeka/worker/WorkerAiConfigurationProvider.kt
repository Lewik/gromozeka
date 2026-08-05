package com.gromozeka.worker

import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

@Service
@Primary
class WorkerAiConfigurationProvider : AiConfigurationProvider {
    private val mutableSnapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(null)

    override val snapshotFlow: StateFlow<AiCatalogSnapshot?> = mutableSnapshotFlow.asStateFlow()

    override val snapshot: AiCatalogSnapshot
        get() = checkNotNull(mutableSnapshotFlow.value) {
            "Worker AI catalog has not been synchronized by the Server"
        }

    fun synchronize(candidate: AiCatalogSnapshot) {
        val current = mutableSnapshotFlow.value
        require(current == null || candidate.revision >= current.revision) {
            "Worker AI catalog revision cannot move backwards: ${current?.revision} -> ${candidate.revision}"
        }
        mutableSnapshotFlow.value = candidate
    }

    override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime {
        return catalog.resolveRuntime(selection, snapshot.runtimeEnabledConnectionIds)
    }
}
