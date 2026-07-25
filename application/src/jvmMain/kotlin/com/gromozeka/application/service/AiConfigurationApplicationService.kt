package com.gromozeka.application.service

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.domain.repository.AiCatalogRepository
import com.gromozeka.domain.repository.AiModelSpecRepository
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.SettingsProvider
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service

@Service
@DependsOn("runtimeCatalogBootstrapService")
class AiConfigurationApplicationService(
    private val repository: AiCatalogRepository,
    private val agentRepository: AgentRepository,
    private val settingsProvider: SettingsProvider,
) : AiConfigurationService, AiModelSpecRepository {
    private val mutableSnapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(null)

    override val snapshotFlow: StateFlow<AiCatalogSnapshot?> = mutableSnapshotFlow.asStateFlow()
    override val snapshot: AiCatalogSnapshot
        get() = checkNotNull(mutableSnapshotFlow.value) { "AI configuration catalog is not initialized" }

    @PostConstruct
    fun initialize() {
        runBlocking { reload() }
    }

    override suspend fun replaceCatalog(
        catalog: AiCatalog,
        expectedRevision: Long,
    ): AiCatalogSnapshot {
        validateAgentReferences(catalog)
        val updated = repository.replace(expectedRevision, catalog).withRuntimeEnvironment()
        mutableSnapshotFlow.value = updated
        return updated
    }

    override suspend fun reload(): AiCatalogSnapshot {
        val loaded = checkNotNull(repository.find()) {
            "Runtime configuration catalog is not initialized"
        }.withRuntimeEnvironment()
        mutableSnapshotFlow.value = loaded
        return loaded
    }

    override suspend fun refreshIfChanged() {
        val current = snapshot
        if (repository.findRevision() != current.revision) {
            reload()
        }
    }

    override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime {
        val modelConfiguration = catalog.modelConfigurations.firstOrNull {
            it.id == selection.modelConfigurationId
        } ?: error("AI model configuration not found: ${selection.modelConfigurationId.value}")
        val connection = catalog.connections.firstOrNull { it.id == modelConfiguration.connectionId }
            ?: error("AI connection not found: ${modelConfiguration.connectionId.value}")
        require(connection.enabled || connection.id in snapshot.runtimeEnabledConnectionIds) {
            "AI connection is disabled: ${connection.id.value}"
        }
        require(modelConfiguration.enabled) {
            "AI model configuration is disabled: ${modelConfiguration.id.value}"
        }
        return ResolvedAiRuntime(connection, modelConfiguration)
    }

    override suspend fun find(provider: AiProvider, modelId: String): AiModelSpec? =
        catalog.modelSpecs.firstOrNull { it.provider == provider && it.id == modelId }

    override suspend fun findAll(): List<AiModelSpec> = catalog.modelSpecs

    private suspend fun validateAgentReferences(catalog: AiCatalog) {
        val agents = agentRepository.findAll()
        val defaultAgent = agents.firstOrNull { it.id == catalog.defaultAgentId }
        require(defaultAgent != null) {
            "Default agent not found: ${catalog.defaultAgentId.value}"
        }
        require(defaultAgent.type is com.gromozeka.domain.model.AgentDefinition.Type.Global) {
            "Default agent must be global: ${catalog.defaultAgentId.value}"
        }

        val candidateSnapshot = AiCatalogSnapshot(
            catalog = catalog,
            revision = snapshot.revision,
            runtimeEnabledConnectionIds = settingsProvider.runtimeEnabledAiConnectionIds,
        )
        val invalidAgents = agents.filter { agent ->
            val configuration = catalog.modelConfigurations.firstOrNull {
                it.id == agent.runtimeSelection.modelConfigurationId
            }
            configuration == null ||
                !candidateSnapshot.supportsPurpose(configuration, AiRuntimeAssignment.Purpose.DEFAULT_CHAT)
        }
        require(invalidAgents.isEmpty()) {
            "AI catalog update would leave agents without an enabled text-generation model: " +
                invalidAgents.joinToString { "${it.name} (${it.id.value})" }
        }
    }

    private fun AiCatalogSnapshot.withRuntimeEnvironment(): AiCatalogSnapshot =
        copy(runtimeEnabledConnectionIds = settingsProvider.runtimeEnabledAiConnectionIds)
}
