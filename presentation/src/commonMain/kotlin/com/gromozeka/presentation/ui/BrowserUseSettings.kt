package com.gromozeka.presentation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.client.RemoteMcpServerService
import com.gromozeka.client.RemoteDistributionService
import com.gromozeka.domain.model.mcp.BrowserUseMcpPreset
import com.gromozeka.domain.model.mcp.McpServerConfig
import com.gromozeka.domain.model.mcp.McpServerId
import com.gromozeka.domain.model.mcp.McpServerTransport
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerCatalogEntry
import com.gromozeka.remote.protocol.BrowserUseProbeResponse
import com.gromozeka.remote.protocol.DistributionComponent
import com.gromozeka.remote.protocol.RemoteMcpServerView
import com.gromozeka.presentation.services.translation.data.Translation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch

@Composable
internal fun BrowserUseSettings(
    service: RemoteMcpServerService,
    distributionService: RemoteDistributionService,
    workers: List<WorkerCatalogEntry>,
    canManage: Boolean,
) {
    val translation by rememberUpdatedState(LocalTranslation.current)
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var reloadKey by remember { mutableIntStateOf(0) }
    var loadState by remember { mutableStateOf<BrowserUseLoadState>(BrowserUseLoadState.Loading) }
    var mutationInProgress by remember { mutableStateOf(false) }
    var mutationError by remember(translation) { mutableStateOf<String?>(null) }
    var probeState by remember { mutableStateOf<BrowserUseProbeState?>(null) }
    var bridgeDownloadUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(distributionService) {
        bridgeDownloadUrl = runCatching { distributionService.getManifest() }
            .getOrNull()
            ?.artifacts
            ?.singleOrNull { it.component == DistributionComponent.BROWSER_BRIDGE }
            ?.downloadUrl
    }

    LaunchedEffect(service, reloadKey, canManage) {
        loadState = if (!canManage) {
            BrowserUseLoadState.Ready(emptyList())
        } else {
            service.observe()
                .catch { failure ->
                    loadState = BrowserUseLoadState.Failed(
                        failure.message
                    )
                }
                .collect { servers ->
                    loadState = BrowserUseLoadState.Ready(
                        servers.filter { BrowserUseMcpPreset.isConnection(it.server) }
                    )
                }
            loadState
        }
    }

    fun mutate(block: suspend () -> Unit) {
        if (mutationInProgress || probeState is BrowserUseProbeState.Loading) return
        mutationInProgress = true
        mutationError = null
        probeState = null
        scope.launch {
            runCatching { block() }
                .onFailure { mutationError = it.message ?: translation.text("browser.error.configuration") }
            mutationInProgress = false
        }
    }

    fun testBrowserUse(serverId: McpServerId) {
        if (mutationInProgress || probeState is BrowserUseProbeState.Loading) return
        mutationError = null
        probeState = BrowserUseProbeState.Loading(serverId)
        scope.launch {
            probeState = runCatching { service.testBrowserUse(serverId) }
                .fold(
                    onSuccess = { BrowserUseProbeState.Ready(serverId, it) },
                    onFailure = {
                        BrowserUseProbeState.Failed(
                            serverId = serverId,
                            message = it.message,
                        )
                    },
                )
        }
    }

    SettingsGroup(title = translation.text("browser.title")) {
        Text(
            text = translation.text("browser.description"),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = translation.text("browser.worker_boundary"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!canManage) {
            Text(
                text = translation.text("browser.owner_only"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsGroup
        }

        when (val state = loadState) {
            BrowserUseLoadState.Loading -> CircularProgressIndicator()

            is BrowserUseLoadState.Failed -> {
                Text(state.message ?: translation.text("browser.error.load_connections"), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { reloadKey++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(translation.text("browser.retry"))
                }
            }

            is BrowserUseLoadState.Ready -> {
                val operationInProgress = mutationInProgress ||
                    probeState is BrowserUseProbeState.Loading
                val connectedWorkerIds = state.connections
                    .mapTo(mutableSetOf()) { it.server.config.workerId }
                state.connections.forEach { connection ->
                    BrowserConnectionCard(
                        connection = connection,
                        worker = workers.firstOrNull {
                            it.workerId == connection.server.config.workerId
                        },
                        enabled = !operationInProgress,
                        probeState = probeState?.takeIf {
                            it.serverId == connection.server.config.id
                        },
                        onApplyPreset = {
                            mutate {
                                service.update(
                                    config = connection.server.config.copy(
                                        transport = BrowserUseMcpPreset.transport()
                                    ),
                                    expectedRevision = connection.server.revision,
                                )
                            }
                        },
                        onSaveExtensionToken = { token ->
                            mutate {
                                service.update(
                                    config = connection.server.config.withExtensionToken(token, translation),
                                    expectedRevision = connection.server.revision,
                                )
                            }
                        },
                        onRemoveExtensionToken = {
                            mutate {
                                service.update(
                                    config = connection.server.config,
                                    expectedRevision = connection.server.revision,
                                    removeEnvironmentVariables = setOf(
                                        BrowserUseMcpPreset.EXTENSION_TOKEN_ENV
                                    ),
                                )
                            }
                        },
                        onRefresh = {
                            mutate {
                                service.refresh(
                                    serverId = connection.server.config.id,
                                    expectedRevision = connection.server.revision,
                                )
                            }
                        },
                        onTest = {
                            testBrowserUse(connection.server.config.id)
                        },
                        onDelete = {
                            mutate {
                                service.delete(
                                    serverId = connection.server.config.id,
                                    expectedRevision = connection.server.revision,
                                )
                            }
                        },
                    )
                    HorizontalDivider()
                }

                BrowserConnectionCreator(
                    workers = workers.filterNot { it.workerId in connectedWorkerIds },
                    hasRegisteredWorkers = workers.isNotEmpty(),
                    enabled = !operationInProgress,
                    onCreate = { worker, extensionToken ->
                        mutate {
                            service.create(
                                BrowserUseMcpPreset.config(worker.workerId, extensionToken)
                            )
                        }
                    },
                )
            }
        }

        mutationError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }

        TextButton(
            onClick = { bridgeDownloadUrl?.let(uriHandler::openUri) },
            enabled = bridgeDownloadUrl != null,
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(translation.text("browser.download_bridge"))
        }
    }
}

@Composable
private fun BrowserConnectionCreator(
    workers: List<WorkerCatalogEntry>,
    hasRegisteredWorkers: Boolean,
    enabled: Boolean,
    onCreate: (WorkerCatalogEntry, String?) -> Unit,
) {
    val translation = LocalTranslation.current
    if (workers.isEmpty()) {
        Text(
            text = if (hasRegisteredWorkers) {
                translation.text("browser.all_workers_connected")
            } else {
                translation.text("browser.no_workers")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    var selectedWorkerId by remember(workers) {
        mutableStateOf(workers.firstOrNull()?.workerId)
    }
    val extensionTokenState = remember { TextFieldState() }
    val selectedWorker = workers.firstOrNull { it.workerId == selectedWorkerId }

    Text(
        text = translation.text("browser.add_connection"),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    BrowserWorkerPicker(
        workers = workers,
        selectedWorkerId = selectedWorkerId,
        onSelected = { selectedWorkerId = it },
    )
    BrowserExtensionExplanation()
    ExtensionTokenField(
        state = extensionTokenState,
        configured = false,
        enabled = enabled,
    )
    Button(
        onClick = {
            selectedWorker?.let { worker ->
                onCreate(
                    worker,
                    extensionTokenState.text.toString().trim().ifBlank { null },
                )
            }
        },
        enabled = enabled && selectedWorker?.status == WorkerCatalogEntry.Status.ONLINE,
    ) {
        Text(if (selectedWorker?.status == WorkerCatalogEntry.Status.OFFLINE) translation.text("browser.worker_offline") else translation.text("browser.connect"))
    }
}

@Composable
private fun BrowserConnectionCard(
    connection: RemoteMcpServerView,
    worker: WorkerCatalogEntry?,
    enabled: Boolean,
    probeState: BrowserUseProbeState?,
    onApplyPreset: () -> Unit,
    onSaveExtensionToken: (String) -> Unit,
    onRemoveExtensionToken: () -> Unit,
    onRefresh: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val translation = LocalTranslation.current
    val server = connection.server
    val presetUpdateAvailable = server.config.transport != BrowserUseMcpPreset.transport()
    val extensionTokenState = remember(server.revision) { TextFieldState() }
    val extensionTokenConfigured =
        BrowserUseMcpPreset.EXTENSION_TOKEN_ENV in connection.configuredEnvironmentVariables

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = server.config.displayName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = translation.plural(
                if (server.refreshAvailable) "browser.connection_summary_refresh" else "browser.connection_summary",
                server.snapshot.tools.size.toLong(),
                "workerId" to server.config.workerId.value,
                "status" to (worker?.status?.aiLabel(translation) ?: translation.text("browser.status.unavailable")),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BrowserExtensionExplanation()
        if (presetUpdateAvailable) {
            Text(
                text = translation.text("browser.preset_differs"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        ExtensionTokenField(
            state = extensionTokenState,
            configured = extensionTokenConfigured,
            enabled = enabled,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    onSaveExtensionToken(extensionTokenState.text.toString().trim())
                    extensionTokenState.clearText()
                },
                enabled = enabled &&
                    worker?.status == WorkerCatalogEntry.Status.ONLINE &&
                    extensionTokenState.text.isNotBlank(),
            ) {
                Text(if (extensionTokenConfigured) translation.text("browser.token.replace") else translation.text("browser.token.save"))
            }
            if (extensionTokenConfigured) {
                TextButton(
                    onClick = onRemoveExtensionToken,
                    enabled = enabled && worker?.status == WorkerCatalogEntry.Status.ONLINE,
                ) {
                    Text(translation.text("browser.token.remove"))
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (presetUpdateAvailable) {
                Button(
                    onClick = onApplyPreset,
                    enabled = enabled && worker?.status == WorkerCatalogEntry.Status.ONLINE,
                ) {
                    Text(translation.text("browser.apply_update"))
                }
            }
            OutlinedButton(
                onClick = onTest,
                enabled = enabled && worker?.status == WorkerCatalogEntry.Status.ONLINE,
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(translation.text("browser.test"))
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = enabled && worker?.status == WorkerCatalogEntry.Status.ONLINE,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(translation.text("browser.refresh"))
            }
            TextButton(
                onClick = onDelete,
                enabled = enabled && worker?.status == WorkerCatalogEntry.Status.ONLINE,
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(translation.text("browser.remove"))
            }
        }

        when (probeState) {
            is BrowserUseProbeState.Loading -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(translation.text("browser.screenshot.capturing"))
            }

            is BrowserUseProbeState.Ready -> BrowserUseProbePreview(probeState.response)

            is BrowserUseProbeState.Failed -> Text(
                text = probeState.message ?: translation.text("browser.error.test"),
                color = MaterialTheme.colorScheme.error,
            )

            null -> Unit
        }
    }
}

@Composable
private fun BrowserUseProbePreview(response: BrowserUseProbeResponse) {
    val translation = LocalTranslation.current
    val bitmap = remember(response.screenshot) {
        runCatching { response.screenshot.decodeToImageBitmap() }.getOrNull()
    }
    if (bitmap == null) {
        Text(translation.text("browser.screenshot.unreadable", "mediaType" to response.mediaType))
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = response.fileName ?: translation.text("browser.screenshot.description"),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun ExtensionTokenField(
    state: TextFieldState,
    configured: Boolean,
    enabled: Boolean,
) {
    val translation = LocalTranslation.current
    OutlinedSecretTextField(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(translation.text("browser.token.label")) },
        enabled = enabled,
        supportingText = {
            Text(
                if (configured) {
                    translation.text("browser.token.keep_hint")
                } else {
                    translation.text("browser.token.optional_hint")
                }
            )
        },
    )
}

@Composable
private fun BrowserWorkerPicker(
    workers: List<WorkerCatalogEntry>,
    selectedWorkerId: ConversationRuntimeWorkerId?,
    onSelected: (ConversationRuntimeWorkerId) -> Unit,
) {
    val translation = LocalTranslation.current
    var expanded by remember { mutableStateOf(false) }
    val selected = workers.firstOrNull { it.workerId == selectedWorkerId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selected?.browserLabel(translation) ?: translation.text("browser.worker.select"),
            onValueChange = {},
            readOnly = true,
            label = { Text(translation.text("browser.worker.label")) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            workers.forEach { worker ->
                DropdownMenuItem(
                    text = { Text(worker.browserLabel(translation)) },
                    onClick = {
                        onSelected(worker.workerId)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BrowserExtensionExplanation() {
    val translation = LocalTranslation.current
    Text(
        text = translation.text("browser.bridge.instructions"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun McpServerConfig.withExtensionToken(token: String, translation: Translation): McpServerConfig {
    val stdio = transport as? McpServerTransport.BundledStdio
        ?: error(translation.text("browser.validation.bundled_runtime"))
    val normalizedToken = BrowserUseMcpPreset.normalizeExtensionToken(token)
    require(normalizedToken.isNotBlank()) { translation.text("browser.validation.token_required") }
    return copy(
        transport = stdio.copy(
            environment = stdio.environment +
                (BrowserUseMcpPreset.EXTENSION_TOKEN_ENV to normalizedToken)
        )
    )
}

private fun WorkerCatalogEntry.browserLabel(translation: Translation): String =
    "${workerId.value} · ${environmentProfile.operatingSystem.name} · ${status.aiLabel(translation)}"

private sealed interface BrowserUseLoadState {
    data object Loading : BrowserUseLoadState
    data class Ready(val connections: List<RemoteMcpServerView>) : BrowserUseLoadState
    data class Failed(val message: String?) : BrowserUseLoadState
}

private sealed interface BrowserUseProbeState {
    val serverId: McpServerId

    data class Loading(override val serverId: McpServerId) : BrowserUseProbeState
    data class Ready(
        override val serverId: McpServerId,
        val response: BrowserUseProbeResponse,
    ) : BrowserUseProbeState

    data class Failed(
        override val serverId: McpServerId,
        val message: String?,
    ) : BrowserUseProbeState
}
