package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryScrollableTabRow
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
import com.gromozeka.presentation.services.translation.data.Translation
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private enum class AiCatalogSection(val titleKey: String) {
    Runtime("ai.catalog.section.runtime"),
    Models("ai.catalog.section.models"),
    Connections("ai.catalog.section.connections"),
    Credentials("ai.catalog.section.credentials"),
    Specs("ai.catalog.section.specs"),
}

private enum class AutoCompactionEditorMode(val titleKey: String) {
    None("ai.compaction.disabled"),
    Percent("ai.compaction.percent"),
    Absolute("ai.compaction.token_threshold"),
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
    val translation = LocalTranslation.current
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
    var error by remember(translation) { mutableStateOf<String?>(null) }
    var workers by remember { mutableStateOf(emptyList<WorkerCatalogEntry>()) }
    val templates = remember { runtimeCatalogTemplateService.getTemplates() }

    LaunchedEffect(workerCatalogService) {
        workerCatalogService.observeWorkers()
            .catch { failure -> error = failure.message ?: failure::class.simpleName }
            .collect { workers = it }
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
            AdaptiveCatalogRow(
                description = {
                    Column {
                        Text(translation.text("ai.catalog.title"), style = MaterialTheme.typography.titleLarge)
                        Text(
                            translation.text("ai.catalog.revision", "revision" to currentSnapshot.revision),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                controls = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    runCatching { aiConfigurationService.reload() }
                                        .onFailure { error = it.message }
                                }
                            },
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = translation.text("ai.catalog.reload"))
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
                                Text(translation.text("ai.action.revert"))
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
                                    Text(translation.text("ai.action.save"))
                                }
                            }
                        }
                    }
                },
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (availableSections.size > 1) {
                SecondaryScrollableTabRow(selectedTabIndex = availableSections.indexOf(selectedSection), edgePadding = 0.dp) {
                    availableSections.forEach { section ->
                        Tab(
                            selected = selectedSection == section,
                            onClick = { selectedSection = section },
                            text = { Text(translation.text(section.titleKey)) },
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
private fun AdaptiveCatalogRow(
    description: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        if (availableWidth < 600.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                description()
                controls()
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) { description() }
                Box(Modifier.widthIn(max = availableWidth / 2)) { controls() }
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
    val translation = LocalTranslation.current
    var showAdvanced by remember { mutableStateOf(false) }
    val primary = AiRuntimeAssignment.Purpose.entries.filter { it.requiresExplicitAssignment }
    val advanced = AiRuntimeAssignment.Purpose.entries.filterNot { it.requiresExplicitAssignment }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            translation.text("ai.runtime.description"),
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
            Text(if (showAdvanced) translation.text("ai.runtime.hide_overrides") else translation.plural("ai.runtime.show_overrides", advanced.size.toLong()))
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
    val translation = LocalTranslation.current
    val directAssignment = draft.runtimeAssignments.firstOrNull { it.purpose == purpose }
    val options = draft.modelConfigurations.filter {
        draft.supportsPurpose(it, purpose, runtimeEnabledConnectionIds)
    }
    val fallbackLabel = purpose.fallbackPurpose?.let { translation.text("ai.runtime.inherit", "purpose" to it.aiLabel(translation)) }
    val selectedId = directAssignment?.selection?.modelConfigurationId
    val selectedConfiguration = selectedId?.let { id ->
        draft.modelConfigurations.firstOrNull { it.id == id }
    }
    val selectedAvailable = selectedConfiguration?.let {
        draft.supportsPurpose(it, purpose, runtimeEnabledConnectionIds)
    } ?: (directAssignment == null && fallbackLabel != null)
    val selectedLabel = selectedConfiguration?.displayName
        ?.let { if (selectedAvailable) it else translation.text("ai.runtime.unavailable_model", "name" to it) }
        ?: fallbackLabel
        ?: translation.text("ai.runtime.not_configured")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AdaptiveCatalogRow(
                description = {
                    Column {
                        Text(purpose.aiLabel(translation), fontWeight = FontWeight.SemiBold)
                        Text(
                            purpose.aiDescription(translation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                controls = {
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
                },
            )
            if (options.isEmpty()) {
                Text(
                    translation.text("ai.runtime.unsupported_capabilities", "capabilities" to purpose.requiredCapabilities.joinToString { it.aiLabel(translation) }),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (directAssignment != null && !selectedAvailable) {
                Text(
                    if (fallbackLabel == null) {
                        translation.text("ai.runtime.paused_connection")
                    } else {
                        translation.text("ai.runtime.unavailable_override", "purpose" to purpose.fallbackPurpose?.aiLabel(translation).orEmpty())
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
    val translation = LocalTranslation.current
    var editing by remember { mutableStateOf<AiModelConfiguration?>(null) }
    var creating by remember { mutableStateOf(false) }
    val missingTemplates = templateCatalog.modelConfigurations.filter { template ->
        draft.modelConfigurations.none { it.id == template.id }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CatalogSectionActions(
            addLabel = translation.text("ai.model.new"),
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
                            add(if (configuration.enabled) translation.text("ai.badge.enabled") else translation.text("ai.badge.disabled"))
                            draft.modelSpecs.firstOrNull {
                                it.id == configuration.providerModelId &&
                                    it.provider == connection.kind.provider
                            }?.capabilities?.forEach { add(it.aiLabel(translation)) }
                            configuration.requestedEmbeddingDimensions?.let {
                                add(translation.text("ai.model.embedding_override", "dimensions" to it))
                            }
                            if (assignments.isNotEmpty()) add(translation.plural("ai.model.assignments", assignments.size.toLong()))
                        },
                        onEdit = { editing = configuration },
                        onDelete = {
                            if (assignments.isNotEmpty()) {
                                onError(translation.text("ai.model.in_use", "name" to configuration.displayName))
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
    val translation = LocalTranslation.current
    var editing by remember { mutableStateOf<AiConnection?>(null) }
    var creating by remember { mutableStateOf(false) }
    val missingTemplates = templateCatalog.connections.filter { template ->
        draft.connections.none { it.id == template.id }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CatalogSectionActions(
            addLabel = translation.text("ai.connection.new"),
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
                subtitle = "${connection.kind.aiLabel(translation)} · ${connection.id.value}",
                badges = listOf(
                    if (connection.enabled) translation.text("ai.badge.enabled") else translation.text("ai.badge.disabled"),
                    translation.plural("ai.connection.models", modelCount.toLong()),
                    connection.executionTarget.displayLabel(workers, translation),
                ) + connection.openAiWebSearchBadge(translation),
                onEdit = { editing = connection },
                onDelete = {
                    if (modelCount > 0) {
                        onError(translation.plural("ai.connection.in_use", modelCount.toLong(), "name" to connection.displayName))
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
    val translation = LocalTranslation.current
    var editing by remember { mutableStateOf<AiModelSpec?>(null) }
    var creating by remember { mutableStateOf(false) }
    val missingTemplates = templateCatalog.modelSpecs.filter { template ->
        draft.modelSpecs.none { it.provider == template.provider && it.id == template.id }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            translation.text("ai.spec.description"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CatalogSectionActions(
            addLabel = translation.text("ai.spec.new"),
            missingTemplateCount = missingTemplates.size,
            onAdd = { creating = true },
            onAddTemplates = {
                onChange(draft.copy(modelSpecs = draft.modelSpecs + missingTemplates))
            },
        )

        AiProvider.entries.forEach { provider ->
            val specs = draft.modelSpecs.filter { it.provider == provider }
            if (specs.isNotEmpty()) {
                Text(provider.aiLabel(translation), style = MaterialTheme.typography.titleMedium)
                specs.forEach { spec ->
                    val used = draft.modelConfigurations.any { configuration ->
                        draft.connections.firstOrNull { it.id == configuration.connectionId }
                            ?.kind?.provider == provider &&
                            configuration.providerModelId == spec.id
                    }
                    CatalogEntityCard(
                        title = spec.id,
                        subtitle = spec.limits.summary(translation),
                        badges = spec.capabilities.map { it.aiLabel(translation) },
                        onEdit = { editing = spec },
                        onDelete = {
                            if (used) {
                                onError(translation.text("ai.spec.in_use", "provider" to spec.provider.aiLabel(translation), "modelId" to spec.id))
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
    val translation = LocalTranslation.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (missingTemplateCount > 0) {
            OutlinedButton(onClick = onAddTemplates) {
                Text(translation.text("ai.catalog.add_templates", "count" to missingTemplateCount))
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
    val translation = LocalTranslation.current
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
                Icon(Icons.Default.Edit, contentDescription = translation.text("ai.catalog.edit_entity", "title" to title))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = translation.text("ai.catalog.delete_entity", "title" to title),
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
    val translation = LocalTranslation.current
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
    var error by remember(translation) { mutableStateOf<String?>(null) }

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
        title = { Text(if (existing == null) translation.text("ai.connection.new") else translation.text("ai.connection.edit")) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (existing == null) {
                    LabeledDropdown(
                        label = translation.text("ai.connection.kind"),
                        value = kind,
                        options = AiConnection.Kind.entries,
                        optionLabel = { it.aiLabel(translation) },
                        onSelect = { kind = it },
                    )
                }
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text(translation.text("ai.field.stable_id")) },
                    enabled = existing == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(translation.text("ai.field.display_name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text(translation.text("ai.field.enabled"))
                }
                if (kind == AiConnection.Kind.OPENAI_API || kind == AiConnection.Kind.OPENAI_SUBSCRIPTION) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = webSearchEnabled, onCheckedChange = { webSearchEnabled = it })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(translation.text("ai.connection.web_search"))
                            Text(
                                translation.text("ai.connection.web_search_description"),
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
                    label = translation.text("ai.connection.execution_target"),
                    value = executionTarget,
                    options = targetOptions,
                    optionLabel = { it.displayLabel(workers, translation) },
                    onSelect = { executionTarget = it },
                )
                Text(
                    if (kind == AiConnection.Kind.CLAUDE_CODE) {
                        translation.text("ai.connection.claude_target_description")
                    } else if (kind == AiConnection.Kind.GITHUB_COPILOT) {
                        translation.text("ai.connection.copilot_target_description")
                    } else {
                        translation.text("ai.connection.request_target_description")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (kind == AiConnection.Kind.CLAUDE_CODE && workers.isEmpty()) {
                    Text(
                        translation.text("ai.connection.worker_required"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (kind in httpConnectionKinds) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(translation.text("ai.connection.base_url")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (kind in apiKeyConnectionKinds) {
                    LabeledDropdown(
                        label = translation.text("ai.secret.source"),
                        value = secretMode,
                        options = listOf("Environment", "Inline"),
                        optionLabel = {
                            translation.text(if (it == "Inline") "ai.secret.source.inline" else "ai.secret.source.environment")
                        },
                        onSelect = {
                            secretMode = it
                            removeConfiguredSecret = false
                        },
                    )
                    if (secretMode == "Inline") {
                        OutlinedSecretTextField(
                            state = secretValueState,
                            label = { Text(translation.text("ai.secret.api_key")) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OutlinedTextField(
                            state = secretValueState,
                            label = { Text(translation.text("ai.secret.environment_variable")) },
                            lineLimits = TextFieldLineLimits.SingleLine,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (existingSecretState?.source == AiCatalogSecretState.Source.INLINE) {
                        Text(
                            text = if (removeConfiguredSecret) {
                                translation.text("ai.secret.key_will_be_removed")
                            } else {
                                translation.text("ai.secret.keep_existing_hint")
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
                                    translation.text("ai.secret.keep_key")
                                } else {
                                    translation.text("ai.secret.remove_key")
                                }
                            )
                        }
                    }
                }
                if (kind == AiConnection.Kind.ANTHROPIC_BEDROCK) {
                    OutlinedTextField(
                        value = awsRegion,
                        onValueChange = { awsRegion = it },
                        label = { Text(translation.text("ai.connection.aws_region")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = awsProfile,
                        onValueChange = { awsProfile = it },
                        label = { Text(translation.text("ai.connection.aws_profile")) },
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
                            Text(translation.text("ai.connection.claude_voice"))
                            Text(
                                translation.text("ai.connection.claude_voice_description"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = executablePath,
                        onValueChange = { executablePath = it },
                        label = { Text(translation.text("ai.connection.executable")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = maxCachedProcesses,
                        onValueChange = { maxCachedProcesses = it.filter(Char::isDigit) },
                        label = { Text(translation.text("ai.connection.cached_process_limit")) },
                        supportingText = { Text(translation.text("ai.connection.cached_process_limit_hint")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = processIdleTtlMinutes,
                        onValueChange = { processIdleTtlMinutes = it.filter(Char::isDigit) },
                        label = { Text(translation.text("ai.connection.idle_ttl")) },
                        supportingText = { Text(translation.text("ai.connection.idle_ttl_hint")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (kind == AiConnection.Kind.GITHUB_COPILOT) {
                    LabeledDropdown(
                        label = translation.text("ai.connection.authentication"),
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
                                        translation.text("ai.connection.authentication.worker_cli")
                                    } else {
                                        translation.text("ai.connection.authentication.server_cli")
                                    }
                                AiConnection.GitHubCopilotAuthMode.PER_USER_TOKEN -> translation.text("ai.connection.authentication.per_user_token")
                            }
                        },
                        onSelect = { copilotAuthMode = it },
                    )
                    Text(
                        if (copilotAuthMode == AiConnection.GitHubCopilotAuthMode.SERVER_CLI) {
                            when (val target = executionTarget) {
                                AiExecutionTarget.Server ->
                                    translation.text("ai.connection.authentication.server_cli_hint")
                                is AiExecutionTarget.Worker ->
                                    translation.text("ai.connection.authentication.worker_cli_hint", "workerId" to target.workerId)
                            }
                        } else {
                            translation.text("ai.connection.authentication.per_user_hint")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = copilotExecutablePath,
                        onValueChange = { copilotExecutablePath = it },
                        label = { Text(translation.text("ai.connection.copilot_executable")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = copilotHomePath,
                        onValueChange = { copilotHomePath = it },
                        label = { Text(translation.text("ai.connection.copilot_home")) },
                        supportingText = { Text(translation.text("ai.connection.copilot_home_hint")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = copilotRequestTimeoutSeconds,
                        onValueChange = { copilotRequestTimeoutSeconds = it.filter(Char::isDigit) },
                        label = { Text(translation.text("ai.connection.request_timeout")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = copilotSessionIdleTimeoutSeconds,
                        onValueChange = { copilotSessionIdleTimeoutSeconds = it.filter(Char::isDigit) },
                        label = { Text(translation.text("ai.connection.session_idle_timeout")) },
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
                            Text(translation.text("ai.quota.protect"))
                            Text(
                                translation.text("ai.quota.protect_description"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = quotaReservePercent,
                        onValueChange = { quotaReservePercent = it.filterQuotaNumber() },
                        label = { Text(translation.text("ai.quota.reserve")) },
                        supportingText = { Text(translation.text("ai.quota.reserve_hint")) },
                        enabled = quotaPacingEnabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = quotaMinimumHeadroomPercent,
                        onValueChange = { quotaMinimumHeadroomPercent = it.filterQuotaNumber() },
                        label = { Text(translation.text("ai.quota.headroom")) },
                        supportingText = { Text(translation.text("ai.quota.headroom_hint")) },
                        enabled = quotaPacingEnabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = quotaRefreshIntervalSeconds,
                        onValueChange = { quotaRefreshIntervalSeconds = it.filter(Char::isDigit) },
                        label = { Text(translation.text("ai.quota.refresh_interval")) },
                        supportingText = { Text(translation.text("ai.quota.refresh_interval_hint")) },
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
                            translation = translation,
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
                Text(translation.text("ai.action.apply"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(translation.text("ai.action.cancel")) } },
    )
}

private fun createConnection(
    translation: Translation,
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
            ?: error(translation.text("ai.validation.reserve_number")),
        minimumHeadroomPercent = quotaMinimumHeadroomPercent.toDoubleOrNull()
            ?: error(translation.text("ai.validation.headroom_number")),
        refreshIntervalSeconds = quotaRefreshIntervalSeconds.toLongOrNull()
            ?: error(translation.text("ai.validation.refresh_positive_integer")),
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
                ?: error(translation.text("ai.validation.request_timeout_positive_integer")),
            sessionIdleTimeoutSeconds = copilotSessionIdleTimeoutSeconds.toIntOrNull()
                ?: error(translation.text("ai.validation.session_timeout_positive_integer")),
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
                ?: error(translation.text("ai.validation.cached_process_limit_positive_integer")),
            processIdleTtlMinutes = processIdleTtlMinutes.toIntOrNull()
                ?: error(translation.text("ai.validation.idle_ttl_positive_integer")),
            voiceTranscriptionEnabled = voiceTranscriptionEnabled,
            quotaPacing = quotaPacing,
            executionTarget = executionTarget as? AiExecutionTarget.Worker
                ?: error(translation.text("ai.validation.claude_worker_target")),
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

private fun AiExecutionTarget.displayLabel(workers: List<WorkerCatalogEntry>, translation: Translation): String =
    when (this) {
        AiExecutionTarget.Server -> translation.text("ai.target.server")
        is AiExecutionTarget.Worker -> {
            val worker = workers.firstOrNull { it.workerId.value == workerId }
            val status = worker?.status?.aiLabel(translation) ?: translation.text("ai.worker.status.unknown")
            translation.text("ai.target.worker", "workerId" to workerId, "status" to status)
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
    val translation = LocalTranslation.current
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
    var error by remember(translation) { mutableStateOf<String?>(null) }
    val selectedConnection = connections.firstOrNull { it.id == connectionId }
    val selectedModelSpec = modelSpecs.firstOrNull {
        it.provider == selectedConnection?.kind?.provider &&
            it.id == providerModelId.trim()
    }
    val supportsEmbeddings = selectedModelSpec?.capabilities?.contains(AiModelCapability.EMBEDDINGS) == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) translation.text("ai.model.configuration.new") else translation.text("ai.model.configuration.edit")) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text(translation.text("ai.field.stable_id")) },
                    enabled = existing == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                connectionId?.let { selected ->
                    LabeledDropdown(
                        label = translation.text("ai.model.connection"),
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
                    label = { Text(translation.text("ai.field.display_name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = providerModelId,
                    onValueChange = { providerModelId = it },
                    label = { Text(translation.text("ai.model.provider_model_id")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledDropdown(
                    label = translation.text("ai.model.response_format"),
                    value = responseFormat,
                    options = AiModelConfiguration.AssistantResponseFormat.entries,
                    optionLabel = { it.aiLabel(translation) },
                    onSelect = { responseFormat = it },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text(translation.text("ai.field.enabled"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionalNumberField(
                        value = temperature,
                        onValueChange = { temperature = it },
                        label = translation.text("ai.model.temperature"),
                        modifier = Modifier.weight(1f),
                    )
                    OptionalNumberField(
                        value = maxOutputTokens,
                        onValueChange = { maxOutputTokens = it },
                        label = translation.text("ai.model.max_output"),
                        modifier = Modifier.weight(1f),
                    )
                    OptionalNumberField(
                        value = timeoutSeconds,
                        onValueChange = { timeoutSeconds = it },
                        label = translation.text("ai.model.timeout_seconds"),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (supportsEmbeddings) {
                    OptionalNumberField(
                        value = requestedEmbeddingDimensions,
                        onValueChange = { requestedEmbeddingDimensions = it },
                        label = translation.text("ai.model.requested_embedding_dimensions"),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        translation.text("ai.model.embedding_dimensions_hint"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(translation.text("ai.reasoning.default"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NullableEnumDropdown(
                        label = translation.text("ai.reasoning.mode"),
                        value = reasoningMode,
                        options = AiReasoningMode.entries,
                        optionLabel = { it.aiLabel(translation) },
                        onSelect = { reasoningMode = it },
                    )
                    NullableEnumDropdown(
                        label = translation.text("ai.reasoning.effort"),
                        value = reasoningEffort,
                        options = AiReasoningEffort.entries,
                        optionLabel = { it.aiLabel(translation) },
                        onSelect = { reasoningEffort = it },
                    )
                    NullableEnumDropdown(
                        label = translation.text("ai.reasoning.display"),
                        value = reasoningDisplay,
                        options = AiReasoningDisplay.entries,
                        optionLabel = { it.aiLabel(translation) },
                        onSelect = { reasoningDisplay = it },
                    )
                }
                OptionalNumberField(
                    value = reasoningBudget,
                    onValueChange = { reasoningBudget = it },
                    label = translation.text("ai.reasoning.budget_tokens"),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    val selectedConnectionId = requireNotNull(connectionId) {
                        translation.text("ai.validation.connection_required")
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
                            budgetTokens = reasoningBudget.optionalInt(translation.text("ai.reasoning.budget"), translation),
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
                            temperature = temperature.optionalDouble(translation.text("ai.model.temperature"), translation),
                            maxOutputTokens = maxOutputTokens.optionalInt(translation.text("ai.model.max_output_tokens"), translation),
                            reasoning = reasoning,
                            timeoutSeconds = timeoutSeconds.optionalInt(translation.text("ai.model.timeout"), translation),
                        ),
                        requestedEmbeddingDimensions = if (supportsEmbeddings) {
                            requestedEmbeddingDimensions.optionalInt(translation.text("ai.model.requested_embedding_dimensions"), translation)
                        } else {
                            null
                        },
                    )
                }.onSuccess(onSave).onFailure { error = it.message }
            }) {
                Text(translation.text("ai.action.apply"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(translation.text("ai.action.cancel")) } },
    )
}

@Composable
private fun ModelSpecDialog(
    existing: AiModelSpec?,
    onDismiss: () -> Unit,
    onSave: (AiModelSpec) -> Unit,
) {
    val translation = LocalTranslation.current
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
    var error by remember(translation) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) translation.text("ai.spec.model_new") else translation.text("ai.spec.model_edit")) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LabeledDropdown(
                    label = translation.text("ai.spec.provider"),
                    value = provider,
                    options = AiProvider.entries,
                    optionLabel = { it.aiLabel(translation) },
                    onSelect = { provider = it },
                    enabled = existing == null,
                )
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text(translation.text("ai.model.provider_model_id")) },
                    enabled = existing == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(translation.text("ai.spec.capabilities"), style = MaterialTheme.typography.titleSmall)
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
                            label = { Text(capability.aiLabel(translation)) },
                        )
                    }
                }
                if (AiModelCapability.TEXT_GENERATION in capabilities) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptionalNumberField(
                            contextWindow,
                            { contextWindow = it },
                            translation.text("ai.spec.context_window"),
                            Modifier.weight(1f),
                        )
                        OptionalNumberField(
                            maxOutput,
                            { maxOutput = it },
                            translation.text("ai.model.max_output"),
                            Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledDropdown(
                            label = translation.text("ai.spec.auto_compaction"),
                            value = compactionMode,
                            options = AutoCompactionEditorMode.entries,
                            optionLabel = { translation.text(it.titleKey) },
                            onSelect = { compactionMode = it },
                            modifier = Modifier.weight(1f),
                        )
                        OptionalNumberField(
                            compactionValue,
                            { compactionValue = it },
                            when (compactionMode) {
                                AutoCompactionEditorMode.None -> translation.text("ai.compaction.disabled")
                                AutoCompactionEditorMode.Percent -> translation.text("ai.compaction.percent")
                                AutoCompactionEditorMode.Absolute -> translation.text("ai.compaction.threshold_tokens")
                            },
                            Modifier.weight(1f),
                            enabled = compactionMode != AutoCompactionEditorMode.None,
                        )
                    }
                    EnumSetEditor(translation.text("ai.spec.reasoning_modes"), reasoningModes, AiReasoningMode.entries, { it.aiLabel(translation) }) {
                        reasoningModes = it
                    }
                    EnumSetEditor(translation.text("ai.spec.reasoning_efforts"), reasoningEfforts, AiReasoningEffort.entries, { it.aiLabel(translation) }) {
                        reasoningEfforts = it
                    }
                    EnumSetEditor(translation.text("ai.spec.reasoning_display"), reasoningDisplays, AiReasoningDisplay.entries, { it.aiLabel(translation) }) {
                        reasoningDisplays = it
                    }
                }
                if (AiModelCapability.EMBEDDINGS in capabilities) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptionalNumberField(
                            embeddingDimensions,
                            { embeddingDimensions = it },
                            translation.text("ai.spec.default_dimensions"),
                            Modifier.weight(1f),
                        )
                        OptionalNumberField(
                            embeddingInput,
                            { embeddingInput = it },
                            translation.text("ai.spec.max_input"),
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
                                compactionValue.requiredInt(translation.text("ai.compaction.percent_field"), translation)
                            )
                            AutoCompactionEditorMode.Absolute -> AiModelSpec.AutoCompaction.Absolute(
                                compactionValue.requiredInt(translation.text("ai.compaction.threshold_field"), translation)
                            )
                        }
                        AiModelSpec.Limits.TextGeneration(
                            contextWindowTokens = contextWindow.requiredInt(translation.text("ai.spec.context_window"), translation),
                            maxOutputTokens = maxOutput.optionalInt(translation.text("ai.model.max_output"), translation),
                            autoCompaction = autoCompaction,
                        )
                    } else {
                        null
                    }
                    val embeddingLimits = if (AiModelCapability.EMBEDDINGS in capabilities) {
                        AiModelSpec.Limits.Embeddings(
                            dimensions = embeddingDimensions.optionalInt(translation.text("ai.spec.embedding_dimensions"), translation),
                            maxInputTokens = embeddingInput.optionalInt(translation.text("ai.spec.embedding_max_input"), translation),
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
                Text(translation.text("ai.action.apply"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(translation.text("ai.action.cancel")) } },
    )
}

@Composable
private fun <T : Enum<T>> EnumSetEditor(
    label: String,
    values: Set<T>,
    options: List<T>,
    optionLabel: (T) -> String,
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
                    label = { Text(optionLabel(option)) },
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
    optionLabel: (T) -> String,
    onSelect: (T?) -> Unit,
) {
    val translation = LocalTranslation.current
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        CatalogDropdown(
            label = value?.let(optionLabel) ?: translation.text("ai.option.default"),
            options = listOf(null to translation.text("ai.option.default")) + options.map { it to optionLabel(it) },
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

private fun AiModelSpec.Limits.summary(translation: Translation): String = buildList {
    textGeneration?.let {
        add(translation.text("ai.spec.limit.context", "count" to it.contextWindowTokens))
        it.maxOutputTokens?.let { max -> add(translation.text("ai.spec.limit.output", "count" to max)) }
    }
    embeddings?.let {
        it.dimensions?.let { dimensions -> add(translation.plural("ai.spec.limit.dimensions", dimensions.toLong())) }
        it.maxInputTokens?.let { max -> add(translation.text("ai.spec.limit.input", "count" to max)) }
    }
}.ifEmpty { listOf(translation.text("ai.spec.limit.none")) }.joinToString(" · ")

private fun String.requiredInt(label: String, translation: Translation): Int =
    trim().toIntOrNull() ?: error(translation.text("ai.validation.integer", "label" to label))

private fun String.optionalInt(label: String, translation: Translation): Int? =
    trim().ifBlank { null }?.toIntOrNull() ?: if (isBlank()) null else error(translation.text("ai.validation.integer", "label" to label))

private fun String.optionalDouble(label: String, translation: Translation): Double? =
    trim().ifBlank { null }?.toDoubleOrNull() ?: if (isBlank()) null else error(translation.text("ai.validation.number", "label" to label))

private fun AiConnection?.openAiWebSearchEnabledOrDefault(): Boolean =
    when (this) {
        is AiConnection.OpenAiApi -> webSearchEnabled
        is AiConnection.OpenAiSubscription -> webSearchEnabled
        else -> true
    }

private fun AiConnection.openAiWebSearchBadge(translation: Translation): List<String> =
    when (this) {
        is AiConnection.OpenAiApi -> listOf(if (webSearchEnabled) translation.text("ai.badge.web_search_on") else translation.text("ai.badge.web_search_off"))
        is AiConnection.OpenAiSubscription -> listOf(if (webSearchEnabled) translation.text("ai.badge.web_search_on") else translation.text("ai.badge.web_search_off"))
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
