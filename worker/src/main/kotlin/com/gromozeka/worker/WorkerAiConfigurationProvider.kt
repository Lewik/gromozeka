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
        val modelConfiguration = catalog.modelConfigurations.firstOrNull {
            it.id == selection.modelConfigurationId
        } ?: error("AI model configuration not found: ${selection.modelConfigurationId.value}")
        val connection = catalog.connections.firstOrNull {
            it.id == modelConfiguration.connectionId
        } ?: error("AI connection not found: ${modelConfiguration.connectionId.value}")
        require(connection.enabled || connection.id in snapshot.runtimeEnabledConnectionIds) {
            "AI connection is disabled: ${connection.id.value}"
        }
        require(modelConfiguration.enabled) {
            "AI model configuration is disabled: ${modelConfiguration.id.value}"
        }
        return ResolvedAiRuntime(connection, modelConfiguration)
    }
}
