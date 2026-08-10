package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSecretMutation
import com.gromozeka.domain.model.ai.AiCatalogSecretSlot
import com.gromozeka.domain.model.ai.AiCatalogSecretState
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiReasoningCapabilities
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningDisplay
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiReasoningMode
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiSubscriptionConnection
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaPacingPolicy
import com.gromozeka.domain.model.ai.AiWebToolConfiguration
import com.gromozeka.domain.model.ai.apiKeyOrNull
import com.gromozeka.domain.service.AiConfigurationService
import com.gromozeka.domain.service.CurrentUserAiCredentialService
import com.gromozeka.domain.service.RuntimeCatalogTemplateService
import com.gromozeka.domain.service.WorkerCatalogEntry
import com.gromozeka.domain.service.WorkerCatalogService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private enum class AiCatalogSection(val title: String) {
    Runtime("Runtime"),
    Models("Models"),
    Connections("Connections"),
    Credentials("My access"),
    Specs("Specs"),
}

private enum class AutoCompactionEditorMode(val title: String) {
    None("Disabled"),
    Percent("Percent"),
    Absolute("Token threshold"),
}

private data class AiCatalogDraft(
    val connections: List<AiConnection>,
    val modelSpecs: List<AiModelSpec>,
    val modelConfigurations: List<AiModelConfiguration>,
    val runtimeAssignments: List<AiRuntimeAssignment>,
    val defaultAgentId: com.gromozeka.domain.model.AgentDefinition.Id,
    val webTools: AiWebToolConfiguration,
    val secretMutations: List<AiCatalogSecretMutation> = emptyList(),
) {
    fun toCatalog(): AiCatalog = AiCatalog(
        connections = connections,
        modelSpecs = modelSpecs,
        modelConfigurations = modelConfigurations,
        runtimeAssignments = runtimeAssignments,
        defaultAgentId = defaultAgentId,
        webTools = webTools,
    )

    fun withSecretMutation(mutation: AiCatalogSecretMutation?): AiCatalogDraft {
        if (mutation == null) return this
        return copy(
            secretMutations = secretMutations.filterNot { it.slot == mutation.slot } + mutation
        )
    }

    companion object {
        fun from(catalog: AiCatalog): AiCatalogDraft = AiCatalogDraft(
            connections = catalog.connections,
            modelSpecs = catalog.modelSpecs,
            modelConfigurations = catalog.modelConfigurations,
            runtimeAssignments = catalog.runtimeAssignments,
            defaultAgentId = catalog.defaultAgentId,
            webTools = catalog.webTools,
        )
    }
}

@Composable
fun AiCatalogSettings(
    aiConfigurationService: AiConfigurationService,
    runtimeCatalogTemplateService: RuntimeCatalogTemplateService,
    workerCatalogService: WorkerCatalogService,
    aiUserCredentialService: CurrentUserAiCredentialService,
    canManageCatalog: Boolean,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val snapshot by aiConfigurationService.snapshotFlow.collectAsState()
    val availableSections = if (canManageCatalog) {
        AiCatalogSection.entries
    } else {
        listOf(AiCatalogSection.Credentials)
    }
    var selectedSection by remember(canManageCatalog) {
        mutableStateOf(if (canManageCatalog) AiCatalogSection.Runtime else AiCatalogSection.Credentials)
    }
    var draft by remember { mutableStateOf(snapshot?.catalog?.let(AiCatalogDraft::from)) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var workers by remember { mutableStateOf(emptyList<WorkerCatalogEntry>()) }
    val templates = remember { runtimeCatalogTemplateService.getTemplates() }

    LaunchedEffect(workerCatalogService) {
        runCatching { workerCatalogService.listWorkers() }
            .onSuccess { workers = it }
            .onFailure { error = it.message ?: it::class.simpleName }
    }

    LaunchedEffect(snapshot?.revision) {
        draft = snapshot?.catalog?.let(AiCatalogDraft::from)
        isSaving = false
        error = null
    }

    val currentSnapshot = snapshot
    val currentDraft = draft
    if (currentSnapshot == null || currentDraft == null) {
        Box(
            modifier = modifier.fillMaxWidth().height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val isDirty = runCatching {
        currentDraft.toCatalog() != currentSnapshot.catalog ||
            currentDraft.secretMutations.isNotEmpty()
    }.getOrDefault(true)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("AI runtime catalog", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Server database · revision ${currentSnapshot.revision}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                runCatching { aiConfigurationService.reload() }
                                    .onFailure { error = it.message }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload AI catalog")
                    }
                    if (canManageCatalog) {
                        OutlinedButton(
                            onClick = {
                                draft = AiCatalogDraft.from(currentSnapshot.catalog)
                                error = null
                            },
                            enabled = isDirty && !isSaving,
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Revert")
                        }
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isSaving = true
                                    error = null
                                    runCatching {
                                        aiConfigurationService.replaceCatalog(
                                            currentDraft.toCatalog(),
                                            currentSnapshot.revision,
                                            currentDraft.secretMutations,
                                        )
                                    }.onFailure {
                                        error = it.message ?: it::class.simpleName
                                        isSaving = false
                                    }
                                }
                            },
                            enabled = isDirty && !isSaving,
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(18.dp).height(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("Save")
                            }
                        }
                    }
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (availableSections.size > 1) {
                SecondaryTabRow(selectedTabIndex = availableSections.indexOf(selectedSection)) {
                    availableSections.forEach { section ->
                        Tab(
                            selected = selectedSection == section,
                            onClick = { selectedSection = section },
                            text = { Text(section.title) },
                        )
                    }
                }
            }

            when (selectedSection) {
                AiCatalogSection.Runtime -> RuntimeAssignmentsEditor(
                    draft = currentDraft,
                    runtimeEnabledConnectionIds = currentSnapshot.runtimeEnabledConnectionIds,
                    onChange = { draft = it },
                )

                AiCatalogSection.Models -> ModelConfigurationsEditor(
                    draft = currentDraft,
                    templateCatalog = templates.aiCatalog,
                    onChange = { draft = it },
                    onError = { error = it },
                )

                AiCatalogSection.Connections -> ConnectionsEditor(
                    draft = currentDraft,
                    templateCatalog = templates.aiCatalog,
                    workers = workers,
                    secretStates = currentSnapshot.secretStates,
                    onChange = { draft = it },
                    onError = { error = it },
                )

                AiCatalogSection.Credentials -> GitHubCopilotCredentialSettings(
                    connections = currentDraft.connections.filterIsInstance<AiConnection.GitHubCopilot>(),
                    service = aiUserCredentialService,
                    coroutineScope = coroutineScope,
                )

                AiCatalogSection.Specs -> ModelSpecsEditor(
                    draft = currentDraft,
                    templateCatalog = templates.aiCatalog,
                    onChange = { draft = it },
                    onError = { error = it },
                )
            }
        }
    }
}

@Composable
private fun RuntimeAssignmentsEditor(
    draft: AiCatalogDraft,
    runtimeEnabledConnectionIds: Set<AiConnection.Id>,
    onChange: (AiCatalogDraft) -> Unit,
) {
    var showAdvanced by remember { mutableStateOf(false) }
    val primary = AiRuntimeAssignment.Purpose.entries.filter { it.requiresExplicitAssignment }
    val advanced = AiRuntimeAssignment.Purpose.entries.filterNot { it.requiresExplicitAssignment }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Models selected for Gromozeka workflows. Optional stage overrides inherit from their parent workflow.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        primary.forEach { purpose ->
            RuntimeAssignmentRow(
                purpose = purpose,
                draft = draft,
                runtimeEnabledConnectionIds = runtimeEnabledConnectionIds,
                onChange = onChange,
            )
        }

        HorizontalDivider()
        OutlinedButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "Hide stage overrides" else "Show stage overrides (${advanced.size})")
        }
        if (showAdvanced) {
            advanced.forEach { purpose ->
                RuntimeAssignmentRow(
                    purpose = purpose,
                    draft = draft,
                    runtimeEnabledConnectionIds = runtimeEnabledConnectionIds,
                    onChange = onChange,
                )
            }
        }
    }
}

@Composable
private fun RuntimeAssignmentRow(
    purpose: AiRuntimeAssignment.Purpose,
    draft: AiCatalogDraft,
    runtimeEnabledConnectionIds: Set<AiConnection.Id>,
    onChange: (AiCatalogDraft) -> Unit,
) {
    val directAssignment = draft.runtimeAssignments.firstOrNull { it.purpose == purpose }
    val options = draft.modelConfigurations.filter {
        draft.supportsPurpose(it, purpose, runtimeEnabledConnectionIds)
    }
    val fallbackLabel = purpose.fallbackPurpose?.let { "Inherit ${it.displayName}" }
    val selectedId = directAssignment?.selection?.modelConfigurationId
    val selectedConfiguration = selectedId?.let { id ->
        draft.modelConfigurations.firstOrNull { it.id == id }
    }
    val selectedAvailable = selectedConfiguration?.let {
        draft.supportsPurpose(it, purpose, runtimeEnabledConnectionIds)
    } ?: (directAssignment == null && fallbackLabel != null)
    val selectedLabel = selectedConfiguration?.displayName
        ?.let { if (selectedAvailable) it else "$it · unavailable" }
        ?: fallbackLabel
        ?: "Not configured"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(purpose.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        purpose.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CatalogDropdown(
                    label = selectedLabel,
                    options = buildList {
                        if (fallbackLabel != null) add(null to fallbackLabel)
                        options.forEach { add(it.id to "${it.displayName} · ${it.providerModelId}") }
                    },
                    onSelect = { modelId ->
                        val assignments = draft.runtimeAssignments.filterNot { it.purpose == purpose } +
                            listOfNotNull(
                                modelId?.let {
                                    AiRuntimeAssignment(purpose, AiRuntimeSelection(it))
                                }
                            )
                        onChange(draft.copy(runtimeAssignments = assignments.sortedBy { it.purpose.ordinal }))
                    },
                )
            }
            if (options.isEmpty()) {
                Text(
                    "No enabled model supports ${purpose.requiredCapabilities.joinToString()}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (directAssignment != null && !selectedAvailable) {
                Text(
                    if (fallbackLabel == null) {
                        "This capability is paused while its assigned connection is unavailable."
                    } else {
                        "This override is unavailable; runtime falls back to ${purpose.fallbackPurpose?.displayName}."
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ModelConfigurationsEditor(
    draft: AiCatalogDraft,
    templateCatalog: AiCatalog,
    onChange: (AiCatalogDraft) -> Unit,
    onError: (String?) -> Unit,
) {
    var editing by remember { mutableStateOf<AiModelConfiguration?>(null) }
    var creating by remember { mutableStateOf(false) }
    val missingTemplates = templateCatalog.modelConfigurations.filter { template ->
        draft.modelConfigurations.none { it.id == template.id }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CatalogSectionActions(
            addLabel = "New model",
            missingTemplateCount = missingTemplates.size,
            onAdd = { creating = true },
            onAddTemplates = {
                onChange(
                    draft.copy(
                        modelConfigurations = draft.modelConfigurations + missingTemplates,
                        connections = draft.connections + templateCatalog.connections.filter { candidate ->
                            draft.connections.none { it.id == candidate.id } &&
                                missingTemplates.any { it.connectionId == candidate.id }
                        },
                        modelSpecs = draft.modelSpecs + templateCatalog.modelSpecs.filter { candidate ->
                            draft.modelSpecs.none { it.provider == candidate.provider && it.id == candidate.id } &&
                                missingTemplates.any { configuration ->
                                    templateCatalog.connectionFor(configuration)?.kind?.provider == candidate.provider &&
                                        configuration.providerModelId == candidate.id
                                }
                        },
                    )
                )
            },
        )

        draft.connections.forEach { connection ->
            val models = draft.modelConfigurations.filter { it.connectionId == connection.id }
            if (models.isNotEmpty()) {
                Text(connection.displayName, style = MaterialTheme.typography.titleMedium)
                models.forEach { configuration ->
                    val assignments = draft.runtimeAssignments.filter {
                        it.selection.modelConfigurationId == configuration.id
                    }
                    CatalogEntityCard(
                        title = configuration.displayName,
                        subtitle = "${configuration.providerModelId} · ${configuration.id.value}",
                        badges = buildList {
                            add(if (configuration.enabled) "enabled" else "disabled")
                            draft.modelSpecs.firstOrNull {
                                it.id == configuration.providerModelId &&
                                    it.provider == connection.kind.provider
                            }?.capabilities?.forEach { add(it.name.lowercase()) }
                            configuration.requestedEmbeddingDimensions?.let {
                                add("embedding ${it}d override")
                            }
                            if (assignments.isNotEmpty()) add("${assignments.size} assignments")
                        },
                        onEdit = { editing = configuration },
                        onDelete = {
                            if (assignments.isNotEmpty()) {
                                onError("Model ${configuration.displayName} is used by runtime assignments")
                            } else {
                                onChange(
                                    draft.copy(
                                        modelConfigurations = draft.modelConfigurations.filterNot {
                                            it.id == configuration.id
                                        }
                                    )
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        ModelConfigurationDialog(
            existing = editing,
            connections = draft.connections,
            modelSpecs = draft.modelSpecs,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { configuration ->
                onChange(
                    draft.copy(
                        modelConfigurations = draft.modelConfigurations
                            .filterNot { it.id == configuration.id } + configuration
                    )
                )
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun ConnectionsEditor(
    draft: AiCatalogDraft,
    templateCatalog: AiCatalog,
    workers: List<WorkerCatalogEntry>,
    secretStates: List<AiCatalogSecretState>,
    onChange: (AiCatalogDraft) -> Unit,
    onError: (String?) -> Unit,
) {
    var editing by remember { mutableStateOf<AiConnection?>(null) }
    var creating by remember { mutableStateOf(false) }
    val missingTemplates = templateCatalog.connections.filter { template ->
        draft.connections.none { it.id == template.id }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CatalogSectionActions(
            addLabel = "New connection",
            missingTemplateCount = missingTemplates.size,
            onAdd = { creating = true },
            onAddTemplates = {
                onChange(draft.copy(connections = draft.connections + missingTemplates))
            },
        )

        draft.connections.forEach { connection ->
            val modelCount = draft.modelConfigurations.count { it.connectionId == connection.id }
            CatalogEntityCard(
                title = connection.displayName,
                subtitle = "${connection.kind.name} · ${connection.id.value}",
                badges = listOf(
                    if (connection.enabled) "enabled" else "disabled",
                    "$modelCount models",
                    connection.executionTarget.displayLabel(workers),
                ) + connection.openAiWebSearchBadge(),
                onEdit = { editing = connection },
                onDelete = {
                    if (modelCount > 0) {
                        onError("Connection ${connection.displayName} still has $modelCount model configurations")
                    } else {
                        val slot = AiCatalogSecretSlot.ConnectionApiKey(connection.id)
                        onChange(
                            draft.copy(
                                connections = draft.connections.filterNot { it.id == connection.id },
                                secretMutations = draft.secretMutations.filterNot { it.slot == slot },
                            )
                        )
                    }
                },
            )
        }
    }

    if (creating || editing != null) {
        ConnectionDialog(
            existing = editing,
            existingSecretState = editing?.let { connection ->
                val slot = AiCatalogSecretSlot.ConnectionApiKey(connection.id)
                secretStates.firstOrNull { it.slot == slot }
            },
            workers = workers,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { connection, secretMutation ->
                onChange(
                    draft.copy(
                        connections = draft.connections.filterNot { it.id == connection.id } + connection
                    ).withSecretMutation(secretMutation)
                )
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun ModelSpecsEditor(
    draft: AiCatalogDraft,
    templateCatalog: AiCatalog,
    onChange: (AiCatalogDraft) -> Unit,
    onError: (String?) -> Unit,
) {
    var editing by remember { mutableStateOf<AiModelSpec?>(null) }
    var creating by remember { mutableStateOf(false) }
    val missingTemplates = templateCatalog.modelSpecs.filter { template ->
        draft.modelSpecs.none { it.provider == template.provider && it.id == template.id }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Specs describe provider capabilities and limits. Model configurations reference them by provider and model id.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CatalogSectionActions(
            addLabel = "New spec",
            missingTemplateCount = missingTemplates.size,
            onAdd = { creating = true },
            onAddTemplates = {
                onChange(draft.copy(modelSpecs = draft.modelSpecs + missingTemplates))
            },
        )

        AiProvider.entries.forEach { provider ->
            val specs = draft.modelSpecs.filter { it.provider == provider }
            if (specs.isNotEmpty()) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                specs.forEach { spec ->
                    val used = draft.modelConfigurations.any { configuration ->
                        draft.connections.firstOrNull { it.id == configuration.connectionId }
                            ?.kind?.provider == provider &&
                            configuration.providerModelId == spec.id
                    }
                    CatalogEntityCard(
                        title = spec.id,
                        subtitle = spec.limits.summary(),
                        badges = spec.capabilities.map { it.name.lowercase() },
                        onEdit = { editing = spec },
                        onDelete = {
                            if (used) {
                                onError("Model spec ${spec.provider}/${spec.id} is used by a configuration")
                            } else {
                                onChange(
                                    draft.copy(
                                        modelSpecs = draft.modelSpecs.filterNot {
                                            it.provider == spec.provider && it.id == spec.id
                                        }
                                    )
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        ModelSpecDialog(
            existing = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { spec ->
                onChange(
                    draft.copy(
                        modelSpecs = draft.modelSpecs.filterNot {
                            it.provider == spec.provider && it.id == spec.id
                        } + spec
                    )
                )
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun CatalogSectionActions(
    addLabel: String,
    missingTemplateCount: Int,
    onAdd: () -> Unit,
    onAddTemplates: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (missingTemplateCount > 0) {
            OutlinedButton(onClick = onAddTemplates) {
                Text("Add $missingTemplateCount from templates")
            }
            Spacer(Modifier.width(8.dp))
        }
        Button(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(addLabel)
        }
    }
}

@Composable
private fun CatalogEntityCard(
    title: String,
    subtitle: String,
    badges: List<String>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (badges.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        badges.forEach { badge ->
                            AssistChip(onClick = {}, label = { Text(badge) }, enabled = false)
                        }
                    }
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit $title")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete $title",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun <T> CatalogDropdown(
    label: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
        ) {
            Text(label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun ConnectionDialog(
    existing: AiConnection?,
    existingSecretState: AiCatalogSecretState?,
    workers: List<WorkerCatalogEntry>,
    onDismiss: () -> Unit,
    onSave: (AiConnection, AiCatalogSecretMutation?) -> Unit,
) {
    var kind by remember { mutableStateOf(existing?.kind ?: AiConnection.Kind.OPENAI_API) }
    var id by remember { mutableStateOf(existing?.id?.value.orEmpty()) }
    var name by remember { mutableStateOf(existing?.displayName.orEmpty()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var webSearchEnabled by remember {
        mutableStateOf(existing.openAiWebSearchEnabledOrDefault())
    }
    var executionTarget by remember {
        mutableStateOf(existing?.executionTarget ?: AiExecutionTarget.Server)
    }
    var baseUrl by remember {
        mutableStateOf((existing as? AiConnection.HttpAiConnection)?.baseUrl.orEmpty())
    }
    var executablePath by remember {
        mutableStateOf((existing as? AiConnection.ClaudeCode)?.executablePath ?: "claude")
    }
    var copilotExecutablePath by remember {
        mutableStateOf((existing as? AiConnection.GitHubCopilot)?.executablePath ?: "copilot")
    }
    var copilotHomePath by remember {
        mutableStateOf((existing as? AiConnection.GitHubCopilot)?.copilotHomePath.orEmpty())
    }
    var copilotAuthMode by remember {
        mutableStateOf(
            (existing as? AiConnection.GitHubCopilot)?.authMode
                ?: AiConnection.GitHubCopilotAuthMode.SERVER_CLI
        )
    }
    var copilotRequestTimeoutSeconds by remember {
        mutableStateOf(
            (existing as? AiConnection.GitHubCopilot)?.requestTimeoutSeconds?.toString()
                ?: AiConnection.GitHubCopilot.DEFAULT_REQUEST_TIMEOUT_SECONDS.toString()
        )
    }
    var copilotSessionIdleTimeoutSeconds by remember {
        mutableStateOf(
            (existing as? AiConnection.GitHubCopilot)?.sessionIdleTimeoutSeconds?.toString()
                ?: AiConnection.GitHubCopilot.DEFAULT_SESSION_IDLE_TIMEOUT_SECONDS.toString()
        )
    }
    var maxCachedProcesses by remember {
        mutableStateOf(
            (existing as? AiConnection.ClaudeCode)?.maxCachedProcesses?.toString()
                ?: AiConnection.ClaudeCode.DEFAULT_MAX_CACHED_PROCESSES.toString()
        )
    }
    var processIdleTtlMinutes by remember {
        mutableStateOf(
            (existing as? AiConnection.ClaudeCode)?.processIdleTtlMinutes?.toString()
                ?: AiConnection.ClaudeCode.DEFAULT_PROCESS_IDLE_TTL_MINUTES.toString()
        )
    }
    var voiceTranscriptionEnabled by remember {
        mutableStateOf((existing as? AiConnection.ClaudeCode)?.voiceTranscriptionEnabled ?: false)
    }
    val existingQuotaPacing = (existing as? AiSubscriptionConnection)?.quotaPacing
        ?: AiSubscriptionQuotaPacingPolicy()
    var quotaPacingEnabled by remember { mutableStateOf(existingQuotaPacing.enabled) }
    var quotaReservePercent by remember { mutableStateOf(existingQuotaPacing.reservePercent.toString()) }
    var quotaMinimumHeadroomPercent by remember {
        mutableStateOf(existingQuotaPacing.minimumHeadroomPercent.toString())
    }
    var quotaRefreshIntervalSeconds by remember {
        mutableStateOf(existingQuotaPacing.refreshIntervalSeconds.toString())
    }
    var awsRegion by remember {
        mutableStateOf((existing as? AiConnection.AwsAiConnection)?.awsRegion.orEmpty())
    }
    var awsProfile by remember {
        mutableStateOf((existing as? AiConnection.AwsAiConnection)?.awsProfile.orEmpty())
    }
    val existingSecret = (existing as? AiConnection.ApiKeyAiConnection)?.apiKey
    var secretMode by remember {
        mutableStateOf(
            if (
                existingSecret is SecretRef.Inline ||
                existingSecretState?.source == AiCatalogSecretState.Source.INLINE
            ) {
                "Inline"
            } else {
                "Environment"
            }
        )
    }
    val secretValueState = remember {
        TextFieldState(
            when (existingSecret) {
                is SecretRef.EnvironmentVariable -> existingSecret.name
                is SecretRef.Inline -> existingSecret.value
                null -> ""
            }
        )
    }
    val secretValue = secretValueState.text.toString()
    var removeConfiguredSecret by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(kind, workers, executionTarget, copilotAuthMode) {
        if (kind == AiConnection.Kind.CLAUDE_CODE && executionTarget !is AiExecutionTarget.Worker) {
            workers.firstOrNull()?.let { worker ->
                executionTarget = AiExecutionTarget.Worker(worker.workerId.value)
            }
        }
        if (
            kind == AiConnection.Kind.GITHUB_COPILOT &&
            executionTarget is AiExecutionTarget.Worker &&
            copilotAuthMode == AiConnection.GitHubCopilotAuthMode.PER_USER_TOKEN
        ) {
            copilotAuthMode = AiConnection.GitHubCopilotAuthMode.SERVER_CLI
        }
    }

    LaunchedEffect(secretValueState) {
        snapshotFlow { secretValueState.text.toString() }.collect { value ->
            if (value.isNotEmpty()) {
                removeConfiguredSecret = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New connection" else "Edit connection") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (existing == null) {
                    LabeledDropdown(
                        label = "Connection kind",
                        value = kind,
                        options = AiConnection.Kind.entries,
                        optionLabel = { it.name },
                        onSelect = { kind = it },
                    )
                }
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("Stable id") },
                    enabled = existing == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Enabled")
                }
                if (kind == AiConnection.Kind.OPENAI_API || kind == AiConnection.Kind.OPENAI_SUBSCRIPTION) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = webSearchEnabled, onCheckedChange = { webSearchEnabled = it })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("OpenAI hosted web search")
                            Text(
                                "Let this connection use OpenAI's native web_search tool.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                val targetOptions = buildList<AiExecutionTarget> {
                    if (kind != AiConnection.Kind.CLAUDE_CODE) {
                        add(AiExecutionTarget.Server)
                    }
                    workers.forEach { add(AiExecutionTarget.Worker(it.workerId.value)) }
                    if (executionTarget is AiExecutionTarget.Worker && executionTarget !in this) {
                        add(executionTarget)
                    }
                }
                LabeledDropdown(
                    label = "Execution target",
                    value = executionTarget,
                    options = targetOptions,
                    optionLabel = { it.displayLabel(workers) },
                    onSelect = { executionTarget = it },
                )
                Text(
                    if (kind == AiConnection.Kind.CLAUDE_CODE) {
                        "Claude Code runs only on the selected Worker, using that machine's installation and credentials."
                    } else if (kind == AiConnection.Kind.GITHUB_COPILOT) {
                        "GitHub Copilot runs on the selected target using that machine's separately installed CLI. " +
                            "Gromozeka does not bundle, reassign, or silently replace it."
                    } else {
                        "Finite LLM, embedding, speech-to-text, and text-to-speech requests use this exact target. " +
                            "Streaming and live voice require Server."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (kind == AiConnection.Kind.CLAUDE_CODE && workers.isEmpty()) {
                    Text(
                        "Enroll a Worker before creating a Claude Code connection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (kind in httpConnectionKinds) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (kind in apiKeyConnectionKinds) {
                    LabeledDropdown(
                        label = "Secret source",
                        value = secretMode,
                        options = listOf("Environment", "Inline"),
                        optionLabel = { it },
                        onSelect = {
                            secretMode = it
                            removeConfiguredSecret = false
                        },
                    )
                    if (secretMode == "Inline") {
                        OutlinedSecretTextField(
                            state = secretValueState,
                            label = { Text("API key") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OutlinedTextField(
                            state = secretValueState,
                            label = { Text("Environment variable") },
                            lineLimits = TextFieldLineLimits.SingleLine,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (existingSecretState?.source == AiCatalogSecretState.Source.INLINE) {
                        Text(
                            text = if (removeConfiguredSecret) {
                                "The stored API key will be removed."
                            } else {
                                "An API key is stored on the Server. Leave this field empty to keep it."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (removeConfiguredSecret) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (existingSecretState != null) {
                        TextButton(
                            onClick = {
                                removeConfiguredSecret = !removeConfiguredSecret
                                if (removeConfiguredSecret) {
                                    secretValueState.clearText()
                                }
                            },
                        ) {
                            Text(
                                if (removeConfiguredSecret) {
                                    "Keep configured API key"
                                } else {
                                    "Remove configured API key"
                                }
                            )
                        }
                    }
                }
                if (kind == AiConnection.Kind.ANTHROPIC_BEDROCK) {
                    OutlinedTextField(
                        value = awsRegion,
                        onValueChange = { awsRegion = it },
                        label = { Text("AWS region") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = awsProfile,
                        onValueChange = { awsProfile = it },
                        label = { Text("AWS profile") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (kind == AiConnection.Kind.CLAUDE_CODE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = voiceTranscriptionEnabled,
                            onCheckedChange = { voiceTranscriptionEnabled = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Experimental Claude Code voice adapter")
                            Text(
                                "Controls a separately installed Claude Code UI. Enable only when your organization and account terms permit automation; consumer Pro/Max is not supported by default.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = executablePath,
                        onValueChange = { executablePath = it },
                        label = { Text("Executable") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = maxCachedProcesses,
                        onValueChange = { maxCachedProcesses = it.filter(Char::isDigit) },
                        label = { Text("Cached process limit") },
                        supportingText = { Text("Maximum Claude Code session processes retained by this connection") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = processIdleTtlMinutes,
                        onValueChange = { processIdleTtlMinutes = it.filter(Char::isDigit) },
                        label = { Text("Idle TTL (minutes)") },
                        supportingText = { Text("Close a cached process after this much idle time") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (kind == AiConnection.Kind.GITHUB_COPILOT) {
                    LabeledDropdown(
                        label = "Authentication",
                        value = copilotAuthMode,
                        options = if (executionTarget is AiExecutionTarget.Worker) {
                            listOf(AiConnection.GitHubCopilotAuthMode.SERVER_CLI)
                        } else {
                            AiConnection.GitHubCopilotAuthMode.entries
                        },
                        optionLabel = {
                            when (it) {
                                AiConnection.GitHubCopilotAuthMode.SERVER_CLI ->
                                    if (executionTarget is AiExecutionTarget.Worker) {
                                        "Worker CLI login"
                                    } else {
                                        "Server CLI login"
                                    }
                                AiConnection.GitHubCopilotAuthMode.PER_USER_TOKEN -> "Per-user token"
                            }
                        },
                        onSelect = { copilotAuthMode = it },
                    )
                    Text(
                        if (copilotAuthMode == AiConnection.GitHubCopilotAuthMode.SERVER_CLI) {
                            when (val target = executionTarget) {
                                AiExecutionTarget.Server ->
                                    "All requests use the GitHub account logged into Copilot CLI on the Server."
                                is AiExecutionTarget.Worker ->
                                    "All requests use the GitHub account logged into Copilot CLI on Worker ${target.workerId}."
                            }
                        } else {
                            "Each user configures their own GitHub token in the My access tab. No shared fallback is used."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = copilotExecutablePath,
                        onValueChange = { copilotExecutablePath = it },
                        label = { Text("Copilot executable") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = copilotHomePath,
                        onValueChange = { copilotHomePath = it },
                        label = { Text("Copilot home override") },
                        supportingText = { Text("Optional. CLI login defaults to ~/.copilot on the execution target.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = copilotRequestTimeoutSeconds,
                        onValueChange = { copilotRequestTimeoutSeconds = it.filter(Char::isDigit) },
                        label = { Text("Request timeout (seconds)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = copilotSessionIdleTimeoutSeconds,
                        onValueChange = { copilotSessionIdleTimeoutSeconds = it.filter(Char::isDigit) },
                        label = { Text("CLI session idle timeout (seconds)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (kind in subscriptionConnectionKinds) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = quotaPacingEnabled,
                            onCheckedChange = { quotaPacingEnabled = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Protect subscription quota from background work")
                            Text(
                                "Applies to memory writes and maintenance. Memory recall and foreground requests bypass it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = quotaReservePercent,
                        onValueChange = { quotaReservePercent = it.filterQuotaNumber() },
                        label = { Text("Protected reserve (%)") },
                        supportingText = { Text("Keep this much subscription capacity for foreground work") },
                        enabled = quotaPacingEnabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = quotaMinimumHeadroomPercent,
                        onValueChange = { quotaMinimumHeadroomPercent = it.filterQuotaNumber() },
                        label = { Text("Per-call headroom (%)") },
                        supportingText = { Text("Required surplus above the time-based spending curve") },
                        enabled = quotaPacingEnabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = quotaRefreshIntervalSeconds,
                        onValueChange = { quotaRefreshIntervalSeconds = it.filter(Char::isDigit) },
                        label = { Text("Quota refresh interval (seconds)") },
                        supportingText = { Text("A fresh quota snapshot is required after every background model call") },
                        enabled = quotaPacingEnabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    runCatching {
                        val connection = createConnection(
                            kind = kind,
                            id = id,
                            name = name,
                            enabled = enabled,
                            webSearchEnabled = webSearchEnabled,
                            baseUrl = baseUrl,
                            secretMode = secretMode,
                            secretValue = secretValue,
                            executablePath = executablePath,
                            copilotExecutablePath = copilotExecutablePath,
                            copilotHomePath = copilotHomePath,
                            copilotAuthMode = copilotAuthMode,
                            copilotRequestTimeoutSeconds = copilotRequestTimeoutSeconds,
                            copilotSessionIdleTimeoutSeconds = copilotSessionIdleTimeoutSeconds,
                            maxCachedProcesses = maxCachedProcesses,
                            processIdleTtlMinutes = processIdleTtlMinutes,
                            voiceTranscriptionEnabled = voiceTranscriptionEnabled,
                            quotaPacingEnabled = quotaPacingEnabled,
                            quotaReservePercent = quotaReservePercent,
                            quotaMinimumHeadroomPercent = quotaMinimumHeadroomPercent,
                            quotaRefreshIntervalSeconds = quotaRefreshIntervalSeconds,
                            awsRegion = awsRegion,
                            awsProfile = awsProfile,
                            executionTarget = executionTarget,
                        )
                        val slot = AiCatalogSecretSlot.ConnectionApiKey(connection.id)
                        val secretMutation = when {
                            removeConfiguredSecret -> AiCatalogSecretMutation.Remove(slot)
                            connection.apiKeyOrNull() != null -> AiCatalogSecretMutation.Set(
                                slot = slot,
                                value = checkNotNull(connection.apiKeyOrNull()),
                            )
                            else -> null
                        }
                        connection to secretMutation
                    }.onSuccess { (connection, secretMutation) ->
                        onSave(connection, secretMutation)
                    }.onFailure { error = it.message }
                },
            ) {
                Text("Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun createConnection(
    kind: AiConnection.Kind,
    id: String,
    name: String,
    enabled: Boolean,
    webSearchEnabled: Boolean,
    baseUrl: String,
    secretMode: String,
    secretValue: String,
    executablePath: String,
    copilotExecutablePath: String,
    copilotHomePath: String,
    copilotAuthMode: AiConnection.GitHubCopilotAuthMode,
    copilotRequestTimeoutSeconds: String,
    copilotSessionIdleTimeoutSeconds: String,
    maxCachedProcesses: String,
    processIdleTtlMinutes: String,
    voiceTranscriptionEnabled: Boolean,
    quotaPacingEnabled: Boolean,
    quotaReservePercent: String,
    quotaMinimumHeadroomPercent: String,
    quotaRefreshIntervalSeconds: String,
    awsRegion: String,
    awsProfile: String,
    executionTarget: AiExecutionTarget,
): AiConnection {
    val connectionId = AiConnection.Id(id.trim())
    val displayName = name.trim()
    val secret = secretValue.trim().ifBlank { null }?.let {
        if (secretMode == "Inline") SecretRef.Inline(it) else SecretRef.EnvironmentVariable(it)
    }
    val quotaPacing = AiSubscriptionQuotaPacingPolicy(
        enabled = quotaPacingEnabled,
        reservePercent = quotaReservePercent.toDoubleOrNull()
            ?: error("Protected reserve must be a number"),
        minimumHeadroomPercent = quotaMinimumHeadroomPercent.toDoubleOrNull()
            ?: error("Per-call headroom must be a number"),
        refreshIntervalSeconds = quotaRefreshIntervalSeconds.toLongOrNull()
            ?: error("Quota refresh interval must be a positive integer"),
    )
    return when (kind) {
        AiConnection.Kind.OPENAI_API -> AiConnection.OpenAiApi(
            id = connectionId,
            displayName = displayName,
            enabled = enabled,
            baseUrl = baseUrl.trim().ifBlank { null },
            apiKey = secret,
            webSearchEnabled = webSearchEnabled,
            executionTarget = executionTarget,
        )
        AiConnection.Kind.OPENAI_SUBSCRIPTION -> AiConnection.OpenAiSubscription(
            id = connectionId,
            displayName = displayName,
            enabled = enabled,
            webSearchEnabled = webSearchEnabled,
            quotaPacing = quotaPacing,
            executionTarget = executionTarget,
        )
        AiConnection.Kind.GITHUB_COPILOT -> AiConnection.GitHubCopilot(
            id = connectionId,
            displayName = displayName,
            enabled = enabled,
            executablePath = copilotExecutablePath.trim(),
            copilotHomePath = copilotHomePath.trim().ifBlank { null },
            authMode = copilotAuthMode,
            requestTimeoutSeconds = copilotRequestTimeoutSeconds.toIntOrNull()
                ?: error("Request timeout must be a positive integer"),
            sessionIdleTimeoutSeconds = copilotSessionIdleTimeoutSeconds.toIntOrNull()
                ?: error("Session idle timeout must be a positive integer"),
            quotaPacing = quotaPacing,
            executionTarget = executionTarget,
        )
        AiConnection.Kind.OPENAI_COMPATIBLE -> AiConnection.OpenAiCompatible(
            id = connectionId,
            displayName = displayName,
            enabled = enabled,
            baseUrl = baseUrl.trim(),
            apiKey = secret,
            executionTarget = executionTarget,
        )
        AiConnection.Kind.ANTHROPIC_API -> AiConnection.AnthropicApi(
            id = connectionId,
            displayName = displayName,
            enabled = enabled,
            baseUrl = baseUrl.trim().ifBlank { null },
            apiKey = secret,
            executionTarget = executionTarget,
        )
        AiConnection.Kind.ANTHROPIC_BEDROCK -> AiConnection.AnthropicBedrock(
            id = connectionId,
            displayName = displayName,
            enabled = enabled,
            baseUrl = baseUrl.trim().ifBlank { null },
            awsRegion = awsRegion.trim().ifBlank { null },
            awsProfile = awsProfile.trim().ifBlank { null },
            executionTarget = executionTarget,
        )
        AiConnection.Kind.CLAUDE_CODE -> AiConnection.ClaudeCode(
            id = connectionId,
            displayName = displayName,
            enabled = enabled,
            executablePath = executablePath.trim(),
            maxCachedProcesses = maxCachedProcesses.toIntOrNull()
                ?: error("Cached process limit must be a positive integer"),
            processIdleTtlMinutes = processIdleTtlMinutes.toIntOrNull()
                ?: error("Idle TTL must be a positive integer"),
            voiceTranscriptionEnabled = voiceTranscriptionEnabled,
            quotaPacing = quotaPacing,
            executionTarget = executionTarget as? AiExecutionTarget.Worker
                ?: error("Claude Code requires a Worker execution target"),
        )
        AiConnection.Kind.GEMINI_API -> AiConnection.GeminiApi(
            id = connectionId,
            displayName = displayName,
            enabled = enabled,
            baseUrl = baseUrl.trim().ifBlank { null },
            apiKey = secret,
            executionTarget = executionTarget,
        )
        AiConnection.Kind.OLLAMA -> AiConnection.Ollama(
            id = connectionId,
            displayName = displayName,
            enabled = enabled,
            baseUrl = baseUrl.trim(),
            executionTarget = executionTarget,
        )
    }
}

private fun String.filterQuotaNumber(): String {
    var decimalSeen = false
    return filter { character ->
        when {
            character.isDigit() -> true
            character == '.' && !decimalSeen -> {
                decimalSeen = true
                true
            }
            else -> false
        }
    }
}

private fun AiExecutionTarget.displayLabel(workers: List<WorkerCatalogEntry>): String =
    when (this) {
        AiExecutionTarget.Server -> "Server"
        is AiExecutionTarget.Worker -> {
            val worker = workers.firstOrNull { it.workerId.value == workerId }
            val status = worker?.status?.name?.lowercase() ?: "unknown"
            "Worker $workerId · $status"
        }
    }

@Composable
private fun ModelConfigurationDialog(
    existing: AiModelConfiguration?,
    connections: List<AiConnection>,
    modelSpecs: List<AiModelSpec>,
    onDismiss: () -> Unit,
    onSave: (AiModelConfiguration) -> Unit,
) {
    var id by remember { mutableStateOf(existing?.id?.value.orEmpty()) }
    var connectionId by remember {
        mutableStateOf(existing?.connectionId ?: connections.firstOrNull()?.id)
    }
    var providerModelId by remember { mutableStateOf(existing?.providerModelId.orEmpty()) }
    var displayName by remember { mutableStateOf(existing?.displayName.orEmpty()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var responseFormat by remember {
        mutableStateOf(
            existing?.assistantResponseFormat ?: AiModelConfiguration.AssistantResponseFormat.JSON_SCHEMA
        )
    }
    var temperature by remember {
        mutableStateOf(existing?.defaultParameters?.temperature?.toString().orEmpty())
    }
    var maxOutputTokens by remember {
        mutableStateOf(existing?.defaultParameters?.maxOutputTokens?.toString().orEmpty())
    }
    var timeoutSeconds by remember {
        mutableStateOf(existing?.defaultParameters?.timeoutSeconds?.toString().orEmpty())
    }
    var requestedEmbeddingDimensions by remember {
        mutableStateOf(existing?.requestedEmbeddingDimensions?.toString().orEmpty())
    }
    var reasoningMode by remember { mutableStateOf(existing?.defaultParameters?.reasoning?.mode) }
    var reasoningEffort by remember { mutableStateOf(existing?.defaultParameters?.reasoning?.effort) }
    var reasoningDisplay by remember { mutableStateOf(existing?.defaultParameters?.reasoning?.display) }
    var reasoningBudget by remember {
        mutableStateOf(existing?.defaultParameters?.reasoning?.budgetTokens?.toString().orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }
    val selectedConnection = connections.firstOrNull { it.id == connectionId }
    val selectedModelSpec = modelSpecs.firstOrNull {
        it.provider == selectedConnection?.kind?.provider &&
            it.id == providerModelId.trim()
    }
    val supportsEmbeddings = selectedModelSpec?.capabilities?.contains(AiModelCapability.EMBEDDINGS) == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New model configuration" else "Edit model configuration") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("Stable id") },
                    enabled = existing == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                connectionId?.let { selected ->
                    LabeledDropdown(
                        label = "Connection",
                        value = selected,
                        options = connections.map { it.id },
                        optionLabel = { candidate ->
                            connections.first { it.id == candidate }.displayName
                        },
                        onSelect = { connectionId = it },
                    )
                }
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = providerModelId,
                    onValueChange = { providerModelId = it },
                    label = { Text("Provider model id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledDropdown(
                    label = "Assistant response format",
                    value = responseFormat,
                    options = AiModelConfiguration.AssistantResponseFormat.entries,
                    optionLabel = { it.name },
                    onSelect = { responseFormat = it },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Enabled")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionalNumberField(
                        value = temperature,
                        onValueChange = { temperature = it },
                        label = "Temperature",
                        modifier = Modifier.weight(1f),
                    )
                    OptionalNumberField(
                        value = maxOutputTokens,
                        onValueChange = { maxOutputTokens = it },
                        label = "Max output",
                        modifier = Modifier.weight(1f),
                    )
                    OptionalNumberField(
                        value = timeoutSeconds,
                        onValueChange = { timeoutSeconds = it },
                        label = "Timeout sec",
                        modifier = Modifier.weight(1f),
                    )
                }
                if (supportsEmbeddings) {
                    OptionalNumberField(
                        value = requestedEmbeddingDimensions,
                        onValueChange = { requestedEmbeddingDimensions = it },
                        label = "Requested embedding dimensions",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Leave empty to use the model default without sending a dimensions parameter.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Default reasoning", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NullableEnumDropdown(
                        label = "Mode",
                        value = reasoningMode,
                        options = AiReasoningMode.entries,
                        onSelect = { reasoningMode = it },
                    )
                    NullableEnumDropdown(
                        label = "Effort",
                        value = reasoningEffort,
                        options = AiReasoningEffort.entries,
                        onSelect = { reasoningEffort = it },
                    )
                    NullableEnumDropdown(
                        label = "Display",
                        value = reasoningDisplay,
                        options = AiReasoningDisplay.entries,
                        onSelect = { reasoningDisplay = it },
                    )
                }
                OptionalNumberField(
                    value = reasoningBudget,
                    onValueChange = { reasoningBudget = it },
                    label = "Reasoning budget tokens",
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    val selectedConnectionId = requireNotNull(connectionId) {
                        "Connection is required"
                    }
                    val reasoning = if (
                        reasoningMode != null ||
                        reasoningEffort != null ||
                        reasoningDisplay != null ||
                        reasoningBudget.isNotBlank()
                    ) {
                        AiReasoningConfig(
                            mode = reasoningMode,
                            effort = reasoningEffort,
                            display = reasoningDisplay,
                            budgetTokens = reasoningBudget.optionalInt("Reasoning budget"),
                        )
                    } else {
                        null
                    }
                    AiModelConfiguration(
                        id = AiModelConfiguration.Id(id.trim()),
                        connectionId = selectedConnectionId,
                        providerModelId = providerModelId.trim(),
                        displayName = displayName.trim(),
                        enabled = enabled,
                        assistantResponseFormat = responseFormat,
                        defaultParameters = AiModelConfiguration.DefaultParameters(
                            temperature = temperature.optionalDouble("Temperature"),
                            maxOutputTokens = maxOutputTokens.optionalInt("Max output tokens"),
                            reasoning = reasoning,
                            timeoutSeconds = timeoutSeconds.optionalInt("Timeout"),
                        ),
                        requestedEmbeddingDimensions = if (supportsEmbeddings) {
                            requestedEmbeddingDimensions.optionalInt("Requested embedding dimensions")
                        } else {
                            null
                        },
                    )
                }.onSuccess(onSave).onFailure { error = it.message }
            }) {
                Text("Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ModelSpecDialog(
    existing: AiModelSpec?,
    onDismiss: () -> Unit,
    onSave: (AiModelSpec) -> Unit,
) {
    var id by remember { mutableStateOf(existing?.id.orEmpty()) }
    var provider by remember { mutableStateOf(existing?.provider ?: AiProvider.OPENAI) }
    var capabilities by remember {
        mutableStateOf(existing?.capabilities ?: setOf(AiModelCapability.TEXT_GENERATION))
    }
    var contextWindow by remember {
        mutableStateOf(existing?.limits?.textGeneration?.contextWindowTokens?.toString().orEmpty())
    }
    var maxOutput by remember {
        mutableStateOf(existing?.limits?.textGeneration?.maxOutputTokens?.toString().orEmpty())
    }
    val initialAutoCompaction = existing?.limits?.textGeneration?.autoCompaction
    var compactionMode by remember {
        mutableStateOf(
            when (initialAutoCompaction) {
                null -> AutoCompactionEditorMode.None
                is AiModelSpec.AutoCompaction.Percent -> AutoCompactionEditorMode.Percent
                is AiModelSpec.AutoCompaction.Absolute -> AutoCompactionEditorMode.Absolute
            }
        )
    }
    var compactionValue by remember {
        mutableStateOf(
            when (initialAutoCompaction) {
                null -> ""
                is AiModelSpec.AutoCompaction.Percent -> initialAutoCompaction.value.toString()
                is AiModelSpec.AutoCompaction.Absolute -> initialAutoCompaction.tokens.toString()
            }
        )
    }
    var embeddingDimensions by remember {
        mutableStateOf(existing?.limits?.embeddings?.dimensions?.toString().orEmpty())
    }
    var embeddingInput by remember {
        mutableStateOf(existing?.limits?.embeddings?.maxInputTokens?.toString().orEmpty())
    }
    var reasoningModes by remember { mutableStateOf(existing?.reasoning?.modes.orEmpty()) }
    var reasoningEfforts by remember { mutableStateOf(existing?.reasoning?.efforts.orEmpty()) }
    var reasoningDisplays by remember { mutableStateOf(existing?.reasoning?.displays.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New model spec" else "Edit model spec") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LabeledDropdown(
                    label = "Provider",
                    value = provider,
                    options = AiProvider.entries,
                    optionLabel = { it.name },
                    onSelect = { provider = it },
                    enabled = existing == null,
                )
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("Provider model id") },
                    enabled = existing == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Capabilities", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AiModelCapability.entries.forEach { capability ->
                        FilterChip(
                            selected = capability in capabilities,
                            onClick = {
                                capabilities = if (capability in capabilities) {
                                    capabilities - capability
                                } else {
                                    capabilities + capability
                                }
                            },
                            label = { Text(capability.name.lowercase()) },
                        )
                    }
                }
                if (AiModelCapability.TEXT_GENERATION in capabilities) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptionalNumberField(
                            contextWindow,
                            { contextWindow = it },
                            "Context window",
                            Modifier.weight(1f),
                        )
                        OptionalNumberField(
                            maxOutput,
                            { maxOutput = it },
                            "Max output",
                            Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledDropdown(
                            label = "Auto compaction",
                            value = compactionMode,
                            options = AutoCompactionEditorMode.entries,
                            optionLabel = { it.title },
                            onSelect = { compactionMode = it },
                            modifier = Modifier.weight(1f),
                        )
                        OptionalNumberField(
                            compactionValue,
                            { compactionValue = it },
                            when (compactionMode) {
                                AutoCompactionEditorMode.None -> "Disabled"
                                AutoCompactionEditorMode.Percent -> "Percent"
                                AutoCompactionEditorMode.Absolute -> "Threshold tokens"
                            },
                            Modifier.weight(1f),
                            enabled = compactionMode != AutoCompactionEditorMode.None,
                        )
                    }
                    EnumSetEditor("Reasoning modes", reasoningModes, AiReasoningMode.entries) {
                        reasoningModes = it
                    }
                    EnumSetEditor("Reasoning efforts", reasoningEfforts, AiReasoningEffort.entries) {
                        reasoningEfforts = it
                    }
                    EnumSetEditor("Reasoning display", reasoningDisplays, AiReasoningDisplay.entries) {
                        reasoningDisplays = it
                    }
                }
                if (AiModelCapability.EMBEDDINGS in capabilities) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptionalNumberField(
                            embeddingDimensions,
                            { embeddingDimensions = it },
                            "Default dimensions",
                            Modifier.weight(1f),
                        )
                        OptionalNumberField(
                            embeddingInput,
                            { embeddingInput = it },
                            "Max input",
                            Modifier.weight(1f),
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    val textLimits = if (AiModelCapability.TEXT_GENERATION in capabilities) {
                        val autoCompaction = when (compactionMode) {
                            AutoCompactionEditorMode.None -> null
                            AutoCompactionEditorMode.Percent -> AiModelSpec.AutoCompaction.Percent(
                                compactionValue.requiredInt("Compaction percent")
                            )
                            AutoCompactionEditorMode.Absolute -> AiModelSpec.AutoCompaction.Absolute(
                                compactionValue.requiredInt("Compaction threshold")
                            )
                        }
                        AiModelSpec.Limits.TextGeneration(
                            contextWindowTokens = contextWindow.requiredInt("Context window"),
                            maxOutputTokens = maxOutput.optionalInt("Max output"),
                            autoCompaction = autoCompaction,
                        )
                    } else {
                        null
                    }
                    val embeddingLimits = if (AiModelCapability.EMBEDDINGS in capabilities) {
                        AiModelSpec.Limits.Embeddings(
                            dimensions = embeddingDimensions.optionalInt("Embedding dimensions"),
                            maxInputTokens = embeddingInput.optionalInt("Embedding max input"),
                        )
                    } else {
                        null
                    }
                    val reasoning = if (
                        reasoningModes.isNotEmpty() ||
                        reasoningEfforts.isNotEmpty() ||
                        reasoningDisplays.isNotEmpty()
                    ) {
                        AiReasoningCapabilities(
                            modes = reasoningModes,
                            efforts = reasoningEfforts,
                            displays = reasoningDisplays,
                        )
                    } else {
                        null
                    }
                    AiModelSpec(
                        id = id.trim(),
                        provider = provider,
                        capabilities = capabilities,
                        limits = AiModelSpec.Limits(textLimits, embeddingLimits),
                        reasoning = reasoning,
                    )
                }.onSuccess(onSave).onFailure { error = it.message }
            }) {
                Text("Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun <T : Enum<T>> EnumSetEditor(
    label: String,
    values: Set<T>,
    options: List<T>,
    onChange: (Set<T>) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option in values,
                    onClick = {
                        onChange(if (option in values) values - option else values + option)
                    },
                    label = { Text(option.name.lowercase()) },
                )
            }
        }
    }
}

@Composable
private fun <T> LabeledDropdown(
    label: String,
    value: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        CatalogDropdown(
            label = optionLabel(value),
            options = options.map { it to optionLabel(it) },
            onSelect = onSelect,
            enabled = enabled,
        )
    }
}

@Composable
private fun <T : Enum<T>> NullableEnumDropdown(
    label: String,
    value: T?,
    options: List<T>,
    onSelect: (T?) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        CatalogDropdown(
            label = value?.name ?: "Default",
            options = listOf(null to "Default") + options.map { it to it.name },
            onSelect = onSelect,
        )
    }
}

@Composable
private fun OptionalNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = modifier,
    )
}

private fun AiCatalogDraft.supportsPurpose(
    configuration: AiModelConfiguration,
    purpose: AiRuntimeAssignment.Purpose,
    runtimeEnabledConnectionIds: Set<AiConnection.Id>,
): Boolean {
    if (!configuration.enabled) return false
    val connection = connections.firstOrNull { it.id == configuration.connectionId } ?: return false
    if (!connection.enabled && connection.id !in runtimeEnabledConnectionIds) return false
    val spec = modelSpecs.firstOrNull {
        it.provider == connection.kind.provider && it.id == configuration.providerModelId
    } ?: return false
    return spec.capabilities.containsAll(purpose.requiredCapabilities)
}

private fun AiModelSpec.Limits.summary(): String = buildList {
    textGeneration?.let {
        add("${it.contextWindowTokens} context")
        it.maxOutputTokens?.let { max -> add("$max output") }
    }
    embeddings?.let {
        it.dimensions?.let { dimensions -> add("$dimensions dimensions") }
        it.maxInputTokens?.let { max -> add("$max input") }
    }
}.ifEmpty { listOf("No token limits") }.joinToString(" · ")

private fun String.requiredInt(label: String): Int =
    trim().toIntOrNull() ?: error("$label must be an integer")

private fun String.optionalInt(label: String): Int? =
    trim().ifBlank { null }?.toIntOrNull() ?: if (isBlank()) null else error("$label must be an integer")

private fun String.optionalDouble(label: String): Double? =
    trim().ifBlank { null }?.toDoubleOrNull() ?: if (isBlank()) null else error("$label must be a number")

private fun AiConnection?.openAiWebSearchEnabledOrDefault(): Boolean =
    when (this) {
        is AiConnection.OpenAiApi -> webSearchEnabled
        is AiConnection.OpenAiSubscription -> webSearchEnabled
        else -> true
    }

private fun AiConnection.openAiWebSearchBadge(): List<String> =
    when (this) {
        is AiConnection.OpenAiApi -> listOf(if (webSearchEnabled) "web search" else "web search off")
        is AiConnection.OpenAiSubscription -> listOf(if (webSearchEnabled) "web search" else "web search off")
        else -> emptyList()
    }

private val httpConnectionKinds = setOf(
    AiConnection.Kind.OPENAI_API,
    AiConnection.Kind.OPENAI_COMPATIBLE,
    AiConnection.Kind.ANTHROPIC_API,
    AiConnection.Kind.ANTHROPIC_BEDROCK,
    AiConnection.Kind.GEMINI_API,
    AiConnection.Kind.OLLAMA,
)

private val apiKeyConnectionKinds = setOf(
    AiConnection.Kind.OPENAI_API,
    AiConnection.Kind.OPENAI_COMPATIBLE,
    AiConnection.Kind.ANTHROPIC_API,
    AiConnection.Kind.GEMINI_API,
)

private val subscriptionConnectionKinds = setOf(
    AiConnection.Kind.OPENAI_SUBSCRIPTION,
    AiConnection.Kind.GITHUB_COPILOT,
    AiConnection.Kind.CLAUDE_CODE,
)
