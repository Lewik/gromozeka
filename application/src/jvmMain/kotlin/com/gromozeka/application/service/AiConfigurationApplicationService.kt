package com.gromozeka.application.service

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSecretMutation
import com.gromozeka.domain.model.ai.AiCatalogSecretSlot
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiWebToolConfiguration
import com.gromozeka.domain.model.ai.withApiKey
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.domain.repository.AiCatalogRepository
import com.gromozeka.domain.repository.AiModelSpecRepository
import com.gromozeka.domain.service.AiCatalogManagementService
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
) : AiConfigurationService, AiCatalogManagementService, AiModelSpecRepository {
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
        secretMutations: List<AiCatalogSecretMutation>,
    ): AiCatalogSnapshot {
        val resolvedCatalog = catalog.resolveSecrets(
            existing = snapshot.catalog,
            mutations = secretMutations,
        )
        validateSecretRequirements(resolvedCatalog)
        validateAgentReferences(resolvedCatalog)
        val updated = repository.replace(expectedRevision, resolvedCatalog).withRuntimeEnvironment()
        mutableSnapshotFlow.value = updated
        return updated
    }

    override suspend fun reload(): AiCatalogSnapshot {
        val loaded = checkNotNull(repository.find()) {
            "Runtime configuration catalog is not initialized"
        }.withRuntimeEnvironment()
        validateSecretRequirements(loaded.catalog)
        mutableSnapshotFlow.value = loaded
        return loaded
    }

    override suspend fun refreshIfChanged() {
        val current = snapshot
        if (repository.findRevision() != current.revision) {
            reload()
        }
    }

    override suspend fun upsertConnection(
        connection: AiConnection,
        expectedRevision: Long,
        preserveExistingSecret: Boolean,
    ): AiCatalogSnapshot = mutateCatalog(expectedRevision) { catalog ->
        val existing = catalog.connections.firstOrNull { it.id == connection.id }
        catalog.copy(
            connections = catalog.connections.upsertBy(
                connection.preserveSecretFrom(existing, preserveExistingSecret),
                AiConnection::id,
            )
        )
    }

    override suspend fun deleteConnection(
        connectionId: AiConnection.Id,
        expectedRevision: Long,
    ): AiCatalogSnapshot = mutateCatalog(expectedRevision) { catalog ->
        require(catalog.connections.any { it.id == connectionId }) {
            "AI connection not found: ${connectionId.value}"
        }
        catalog.copy(connections = catalog.connections.filterNot { it.id == connectionId })
    }

    override suspend fun upsertModelSpec(
        modelSpec: AiModelSpec,
        expectedRevision: Long,
    ): AiCatalogSnapshot = mutateCatalog(expectedRevision) { catalog ->
        catalog.copy(
            modelSpecs = catalog.modelSpecs.upsertBy(modelSpec) { it.provider to it.id }
        )
    }

    override suspend fun deleteModelSpec(
        provider: AiProvider,
        modelId: String,
        expectedRevision: Long,
    ): AiCatalogSnapshot = mutateCatalog(expectedRevision) { catalog ->
        require(catalog.modelSpecs.any { it.provider == provider && it.id == modelId }) {
            "AI model spec not found: $provider/$modelId"
        }
        catalog.copy(
            modelSpecs = catalog.modelSpecs.filterNot { it.provider == provider && it.id == modelId }
        )
    }

    override suspend fun upsertModelConfiguration(
        configuration: AiModelConfiguration,
        expectedRevision: Long,
    ): AiCatalogSnapshot = mutateCatalog(expectedRevision) { catalog ->
        catalog.copy(
            modelConfigurations = catalog.modelConfigurations.upsertBy(
                configuration,
                AiModelConfiguration::id,
            )
        )
    }

    override suspend fun deleteModelConfiguration(
        configurationId: AiModelConfiguration.Id,
        expectedRevision: Long,
    ): AiCatalogSnapshot = mutateCatalog(expectedRevision) { catalog ->
        require(catalog.modelConfigurations.any { it.id == configurationId }) {
            "AI model configuration not found: ${configurationId.value}"
        }
        catalog.copy(
            modelConfigurations = catalog.modelConfigurations.filterNot { it.id == configurationId }
        )
    }

    override suspend fun setRuntimeAssignment(
        assignment: AiRuntimeAssignment,
        expectedRevision: Long,
    ): AiCatalogSnapshot = mutateCatalog(expectedRevision) { catalog ->
        catalog.copy(
            runtimeAssignments = catalog.runtimeAssignments.upsertBy(
                assignment,
                AiRuntimeAssignment::purpose,
            )
        )
    }

    override suspend fun setDefaultAgent(
        agentId: AgentDefinition.Id,
        expectedRevision: Long,
    ): AiCatalogSnapshot = mutateCatalog(expectedRevision) { catalog ->
        catalog.copy(defaultAgentId = agentId)
    }

    override suspend fun setWebToolConfiguration(
        configuration: AiWebToolConfiguration,
        expectedRevision: Long,
        preserveExistingSecrets: Boolean,
    ): AiCatalogSnapshot = mutateCatalog(expectedRevision) { catalog ->
        catalog.copy(
            webTools = if (preserveExistingSecrets) {
                configuration.preserveSecretsFrom(catalog.webTools)
            } else {
                configuration
            }
        )
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

    private suspend fun mutateCatalog(
        expectedRevision: Long,
        transform: (AiCatalog) -> AiCatalog,
    ): AiCatalogSnapshot {
        refreshIfChanged()
        val current = snapshot
        require(current.revision == expectedRevision) {
            "AI catalog revision conflict: expected $expectedRevision, actual ${current.revision}"
        }
        return replaceCatalog(transform(current.catalog), expectedRevision)
    }

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

    private fun validateSecretRequirements(catalog: AiCatalog) {
        require(!catalog.webTools.braveSearch.enabled || catalog.webTools.braveSearch.apiKey != null) {
            "Brave Search requires an API key when enabled"
        }
        require(!catalog.webTools.jinaReader.enabled || catalog.webTools.jinaReader.apiKey != null) {
            "Jina Reader requires an API key when enabled"
        }
    }

    private fun AiCatalogSnapshot.withRuntimeEnvironment(): AiCatalogSnapshot =
        copy(runtimeEnabledConnectionIds = settingsProvider.runtimeEnabledAiConnectionIds)
}

private fun AiCatalog.resolveSecrets(
    existing: AiCatalog,
    mutations: List<AiCatalogSecretMutation>,
): AiCatalog {
    require(mutations.map(AiCatalogSecretMutation::slot).distinct().size == mutations.size) {
        "AI catalog secret mutations must be unique by slot"
    }
    val withPreservedSecrets = copy(
        connections = connections.map { connection ->
            connection.preserveCompatibleApiKey(
                existing.connections.firstOrNull { it.id == connection.id }
            )
        },
        webTools = webTools.preserveSecretsFrom(existing.webTools),
    )
    return mutations.fold(withPreservedSecrets, AiCatalog::applySecretMutation)
}

private fun AiCatalog.applySecretMutation(
    mutation: AiCatalogSecretMutation,
): AiCatalog {
    val secret = when (mutation) {
        is AiCatalogSecretMutation.Set -> mutation.value
        is AiCatalogSecretMutation.Remove -> null
    }
    return when (val slot = mutation.slot) {
        is AiCatalogSecretSlot.ConnectionApiKey -> {
            val index = connections.indexOfFirst { it.id == slot.connectionId }
            require(index >= 0) {
                "AI connection not found for secret mutation: ${slot.connectionId.value}"
            }
            val connection = connections[index]
            require(connection is AiConnection.ApiKeyAiConnection) {
                "AI connection does not support API keys: ${slot.connectionId.value}"
            }
            copy(
                connections = connections.toMutableList().apply {
                    this[index] = connection.withApiKey(secret)
                }
            )
        }
        AiCatalogSecretSlot.BraveSearchApiKey -> copy(
            webTools = webTools.copy(
                braveSearch = webTools.braveSearch.copy(apiKey = secret)
            )
        )
        AiCatalogSecretSlot.JinaReaderApiKey -> copy(
            webTools = webTools.copy(
                jinaReader = webTools.jinaReader.copy(apiKey = secret)
            )
        )
    }
}

private fun AiConnection.preserveCompatibleApiKey(existing: AiConnection?): AiConnection =
    when (this) {
        is AiConnection.OpenAiApi -> copy(
            apiKey = apiKey ?: (existing as? AiConnection.OpenAiApi)?.apiKey
        )
        is AiConnection.OpenAiCompatible -> copy(
            apiKey = apiKey ?: (existing as? AiConnection.OpenAiCompatible)?.apiKey
        )
        is AiConnection.AnthropicApi -> copy(
            apiKey = apiKey ?: (existing as? AiConnection.AnthropicApi)?.apiKey
        )
        is AiConnection.GeminiApi -> copy(
            apiKey = apiKey ?: (existing as? AiConnection.GeminiApi)?.apiKey
        )
        else -> this
    }

private fun AiConnection.preserveSecretFrom(
    existing: AiConnection?,
    preserveExistingSecret: Boolean,
): AiConnection {
    if (!preserveExistingSecret || existing == null) return this
    return when {
        this is AiConnection.OpenAiApi && existing is AiConnection.OpenAiApi ->
            copy(apiKey = apiKey ?: existing.apiKey)
        this is AiConnection.OpenAiCompatible && existing is AiConnection.OpenAiCompatible ->
            copy(apiKey = apiKey ?: existing.apiKey)
        this is AiConnection.AnthropicApi && existing is AiConnection.AnthropicApi ->
            copy(apiKey = apiKey ?: existing.apiKey)
        this is AiConnection.GeminiApi && existing is AiConnection.GeminiApi ->
            copy(apiKey = apiKey ?: existing.apiKey)
        else -> this
    }
}

private fun AiWebToolConfiguration.preserveSecretsFrom(
    existing: AiWebToolConfiguration,
): AiWebToolConfiguration =
    copy(
        braveSearch = braveSearch.copy(apiKey = braveSearch.apiKey ?: existing.braveSearch.apiKey),
        jinaReader = jinaReader.copy(apiKey = jinaReader.apiKey ?: existing.jinaReader.apiKey),
    )

private fun <T, K> List<T>.upsertBy(value: T, key: (T) -> K): List<T> {
    val targetKey = key(value)
    val index = indexOfFirst { key(it) == targetKey }
    if (index < 0) return this + value
    return toMutableList().apply { this[index] = value }
}
