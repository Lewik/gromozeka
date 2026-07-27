package com.gromozeka.domain.model.ai

import com.gromozeka.domain.model.AgentDefinition
import kotlinx.serialization.Serializable

@Serializable
data class AiCatalog(
    val connections: List<AiConnection>,
    val modelSpecs: List<AiModelSpec>,
    val modelConfigurations: List<AiModelConfiguration>,
    val runtimeAssignments: List<AiRuntimeAssignment>,
    val defaultAgentId: AgentDefinition.Id,
    val webTools: AiWebToolConfiguration = AiWebToolConfiguration(),
) {
    init {
        require(connections.map { it.id }.distinct().size == connections.size) {
            "AI connection ids must be unique"
        }
        require(modelSpecs.map { it.provider to it.id }.distinct().size == modelSpecs.size) {
            "AI model specs must be unique by provider and model id"
        }
        require(modelConfigurations.map { it.id }.distinct().size == modelConfigurations.size) {
            "AI model configuration ids must be unique"
        }
        require(modelConfigurations.all { configuration ->
            connections.any { it.id == configuration.connectionId }
        }) {
            "Every AI model configuration must reference an existing connection"
        }
        require(runtimeAssignments.map { it.purpose }.distinct().size == runtimeAssignments.size) {
            "AI runtime assignment purposes must be unique"
        }
        require(!webTools.braveSearch.enabled || webTools.braveSearch.apiKey != null) {
            "Brave Search requires an API key when enabled"
        }
        require(!webTools.jinaReader.enabled || webTools.jinaReader.apiKey != null) {
            "Jina Reader requires an API key when enabled"
        }

        val assignedPurposes = runtimeAssignments.map { it.purpose }
        val requiredPurposes = AiRuntimeAssignment.Purpose.entries.filter { it.requiresExplicitAssignment }
        require(assignedPurposes.containsAll(requiredPurposes)) {
            "Every primary AI runtime purpose must have an assignment"
        }

        modelConfigurations.filter { it.enabled }.forEach { configuration ->
            require(modelSpecFor(configuration) != null) {
                "Enabled AI model configuration ${configuration.id.value} must have a model spec for " +
                    configuration.providerModelId
            }
        }
        runtimeAssignments.forEach { assignment ->
            val configuration = modelConfigurations.firstOrNull {
                it.id == assignment.selection.modelConfigurationId
            } ?: error(
                "AI runtime assignment ${assignment.purpose.name} references missing model configuration " +
                    assignment.selection.modelConfigurationId.value
            )
            require(configuration.enabled) {
                "AI runtime assignment ${assignment.purpose.name} references disabled model configuration " +
                    configuration.id.value
            }
            val spec = modelSpecFor(configuration) ?: error(
                "AI runtime assignment ${assignment.purpose.name} references model configuration " +
                    "${configuration.id.value} without a matching model spec"
            )
            require(spec.capabilities.containsAll(assignment.purpose.requiredCapabilities)) {
                "AI runtime assignment ${assignment.purpose.name} requires capabilities " +
                    "${assignment.purpose.requiredCapabilities}, but ${configuration.id.value} has ${spec.capabilities}"
            }
        }

        val claudeCodeWebTools = webTools.claudeCode
        if (claudeCodeWebTools.searchEnabled || claudeCodeWebTools.fetchEnabled) {
            val configuration = modelConfigurations.singleOrNull {
                it.id == claudeCodeWebTools.modelConfigurationId
            } ?: error(
                "Claude Code web tools reference missing model configuration " +
                    claudeCodeWebTools.modelConfigurationId?.value
            )
            require(configuration.enabled) {
                "Claude Code web tools reference disabled model configuration ${configuration.id.value}"
            }
            val connection = connectionFor(configuration)
                ?: error("Claude Code web tools reference a model without a connection")
            require(connection is AiConnection.ClaudeCode && connection.enabled) {
                "Claude Code web tools require an enabled Claude Code connection"
            }
        }
    }

    fun connectionFor(configuration: AiModelConfiguration): AiConnection? =
        connections.firstOrNull { it.id == configuration.connectionId }

    fun modelSpecFor(configuration: AiModelConfiguration): AiModelSpec? =
        connectionFor(configuration)?.let { connection ->
            modelSpecs.firstOrNull {
                it.provider == connection.kind.provider && it.id == configuration.providerModelId
            }
        }

    fun runtimeSelectionFor(purpose: AiRuntimeAssignment.Purpose): AiRuntimeSelection? {
        var currentPurpose: AiRuntimeAssignment.Purpose? = purpose
        while (currentPurpose != null) {
            val purposeToCheck = currentPurpose
            runtimeAssignments.firstOrNull { it.purpose == purposeToCheck }
                ?.selection
                ?.let { return it }
            currentPurpose = purposeToCheck.fallbackPurpose
        }
        return null
    }

    fun supportsPurpose(
        configuration: AiModelConfiguration,
        purpose: AiRuntimeAssignment.Purpose,
    ): Boolean {
        if (!configuration.enabled) return false
        val connection = connectionFor(configuration) ?: return false
        if (!connection.enabled) return false
        val spec = modelSpecFor(configuration) ?: return false
        return spec.capabilities.containsAll(purpose.requiredCapabilities)
    }
}

@Serializable
data class AiCatalogSnapshot(
    val catalog: AiCatalog,
    val revision: Long,
    val runtimeEnabledConnectionIds: Set<AiConnection.Id> = emptySet(),
) {
    init {
        require(revision >= 0) { "AI catalog revision must not be negative" }
    }

    fun supportsPurpose(
        configuration: AiModelConfiguration,
        purpose: AiRuntimeAssignment.Purpose,
    ): Boolean {
        if (!configuration.enabled) return false
        val connection = catalog.connectionFor(configuration) ?: return false
        if (!connection.enabled && connection.id !in runtimeEnabledConnectionIds) return false
        val spec = catalog.modelSpecFor(configuration) ?: return false
        return spec.capabilities.containsAll(purpose.requiredCapabilities)
    }
}
