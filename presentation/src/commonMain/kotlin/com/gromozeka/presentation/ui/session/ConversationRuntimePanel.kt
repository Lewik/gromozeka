package com.gromozeka.presentation.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.client.RemoteConnectionState
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiSubscriptionConnection
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaObservation
import com.gromozeka.domain.model.memory.MemoryRun
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiSubscriptionQuotaService
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeMemoryOperation
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.presentation.services.PttState
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.RemoteConnectionStatus
import com.gromozeka.presentation.ui.TokenStatisticsTable
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.viewmodel.PendingUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

@Composable
fun ConversationRuntimePanel(
    isVisible: Boolean,
    agentService: AgentDomainService,
    aiConfigurationProvider: AiConfigurationProvider,
    aiSubscriptionQuotaService: AiSubscriptionQuotaService,
    tokenStats: TokenUsageStatistics.ThreadTotals?,
    isWaitingForResponse: Boolean,
    executionPauseRequested: Boolean,
    pttState: PttState,
    pttStatusMessage: String?,
    pendingMessages: List<PendingUserMessage>,
    runtimeSnapshot: ConversationRuntimeSnapshot?,
    activeGeneration: ActiveGenerationSnapshot?,
    remoteConnectionState: RemoteConnectionState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onCancelCommandTask: (CommandTask.Id) -> Unit,
    onCancelCommandMonitor: (CommandMonitor.Id) -> Unit,
    onSendInCurrentTurn: (String) -> Unit,
    onEditPendingMessage: (String) -> Unit,
    onCancelPendingMessage: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
    slideFromRight: Boolean = false,
) {
    val translation = LocalTranslation.current.runtime
    val aiCatalogSnapshot by aiConfigurationProvider.snapshotFlow.collectAsState()
    val activeAgentId = runtimeSnapshot?.activeTask?.payload?.agentDefinitionIdOrNull()
    val agentLookupKey = runtimeSnapshot?.conversationId to activeAgentId
    val currentAgent by produceState<AgentDefinition?>(null, isVisible, agentLookupKey) {
        value = if (isVisible && activeAgentId != null) {
            runCatching { agentService.findById(activeAgentId) }.getOrNull()
        } else {
            null
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = if (slideFromRight) slideInHorizontally(initialOffsetX = { it }) else expandHorizontally(),
        exit = if (slideFromRight) slideOutHorizontally(targetOffsetX = { it }) else shrinkHorizontally(),
        modifier = modifier,
    ) {
        Surface(
            modifier = if (fullScreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier.width(533.dp).fillMaxHeight()
            },
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = translation.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = translation.closePanelDescription)
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    currentAgent?.let { agent ->
                        RuntimeConfigurationCard(
                            agent = agent,
                            aiCatalog = aiCatalogSnapshot?.catalog ?: aiConfigurationProvider.catalog,
                        )
                        RuntimeUsageCard(
                            isVisible = isVisible,
                            agent = agent,
                            aiCatalog = aiCatalogSnapshot?.catalog ?: aiConfigurationProvider.catalog,
                            tokenStats = tokenStats,
                            quotaService = aiSubscriptionQuotaService,
                        )
                    }
                    TokenStatisticsTable(
                        tokenStats = tokenStats,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                RuntimeMemorySection(runtimeSnapshot)

                RuntimeTasksSection(
                    runtimeSnapshot = runtimeSnapshot,
                    onCancelCommandTask = onCancelCommandTask,
                    onCancelCommandMonitor = onCancelCommandMonitor,
                )

                PendingMessagesSection(
                    isWaitingForResponse = isWaitingForResponse,
                    pendingMessages = pendingMessages,
                    onSendInCurrentTurn = onSendInCurrentTurn,
                    onEdit = onEditPendingMessage,
                    onCancel = onCancelPendingMessage,
                )

                RuntimeStatusFooter(
                    agentName = currentAgent?.name,
                    isWaitingForResponse = isWaitingForResponse,
                    executionPauseRequested = executionPauseRequested,
                    pttState = pttState,
                    pttStatusMessage = pttStatusMessage,
                    pendingMessages = pendingMessages,
                    runtimeSnapshot = runtimeSnapshot,
                    activeGeneration = activeGeneration,
                    remoteConnectionState = remoteConnectionState,
                    onPause = onPause,
                    onResume = onResume,
                    onStop = onStop,
                )
            }
        }
    }
}

@Composable
private fun RuntimeMemorySection(runtimeSnapshot: ConversationRuntimeSnapshot?) {
    val operations = runtimeSnapshot?.memoryOperations.orEmpty()
    if (operations.isEmpty()) return

    val translation = LocalTranslation.current.runtime
    val visibleOperations = operations
        .sortedWith(
            compareByDescending<ConversationRuntimeMemoryOperation> {
                it.status == MemoryRun.Status.QUEUED || it.status == MemoryRun.Status.RUNNING
            }.thenByDescending { it.updatedAt }
        )
        .take(4)

    Spacer(modifier = Modifier.height(12.dp))
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                translation.memoryTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visibleOperations.forEach { operation ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = operation.operation.replace('_', ' '),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = operation.status.runtimeMemoryStatusLabel(translation),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        operation.progress?.let { progress ->
                            if (progress.totalUnits > 0 &&
                                (operation.status == MemoryRun.Status.QUEUED ||
                                    operation.status == MemoryRun.Status.RUNNING)
                            ) {
                                LinearProgressIndicator(
                                    progress = {
                                        (progress.completedUnits.toFloat() / progress.totalUnits).coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        Text(
                            text = operation.summary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeConfigurationCard(
    agent: AgentDefinition,
    aiCatalog: AiCatalog,
) {
    val configuration = aiCatalog.modelConfigurations.firstOrNull {
        it.id == agent.runtimeSelection.modelConfigurationId
    }
    val connection = configuration?.let(aiCatalog::connectionFor)
    val modelSpec = configuration?.let(aiCatalog::modelSpecFor)
    val reasoning = agent.runtimeOverrides.reasoning ?: configuration?.defaultParameters?.reasoning
    val maxOutputTokens = agent.runtimeOverrides.maxOutputTokens ?: configuration?.defaultParameters?.maxOutputTokens
    val parameters = buildList {
        reasoning?.mode?.let { add("mode=${it.name.lowercase()}") }
        reasoning?.effort?.let { add("effort=${it.name.lowercase()}") }
        reasoning?.display?.let { add("thinking=${it.name.lowercase()}") }
        reasoning?.budgetTokens?.let { add("budget=${it.formatWithCommas()}") }
        maxOutputTokens?.let { add("max output=${it.formatWithCommas()}") }
        configuration?.defaultParameters?.temperature?.let { add("temperature=$it") }
        configuration?.defaultParameters?.timeoutSeconds?.let { add("timeout=${it}s") }
        configuration?.assistantResponseFormat?.let { add("format=${it.name.lowercase()}") }
        runtimeAutoCompactionLabel(connection?.kind, modelSpec?.autoCompactionThresholdTokens)?.let(::add)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(agent.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = configuration?.displayName ?: agent.runtimeSelection.modelConfigurationId.value,
                style = MaterialTheme.typography.bodyMedium,
            )
            val configuredRuntime = listOfNotNull(
                connection?.kind?.provider?.name,
                configuration?.providerModelId,
            ).joinToString(" · ")
            if (configuredRuntime.isNotBlank()) {
                Text(
                    text = configuredRuntime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (parameters.isNotEmpty()) {
                Text(
                    text = parameters.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RuntimeUsageCard(
    isVisible: Boolean,
    agent: AgentDefinition,
    aiCatalog: AiCatalog,
    tokenStats: TokenUsageStatistics.ThreadTotals?,
    quotaService: AiSubscriptionQuotaService,
) {
    val translation = LocalTranslation.current.runtime
    val targets = remember(agent.runtimeSelection, aiCatalog) {
        runtimeQuotaModelConfigurations(agent, aiCatalog)
    }
    val targetIds = targets.map(AiModelConfiguration::id)
    val latestCallId = tokenStats?.recentCalls?.maxByOrNull { it.timestamp }?.id?.value
    var observations by remember(targetIds) {
        mutableStateOf<Map<AiModelConfiguration.Id, AiSubscriptionQuotaObservation>>(emptyMap())
    }
    var isLoading by remember(targetIds) { mutableStateOf(false) }
    var refreshGeneration by remember(targetIds) { mutableIntStateOf(0) }
    var handledRefreshGeneration by remember(targetIds) { mutableIntStateOf(0) }
    var previousCallId by remember(targetIds) { mutableStateOf<String?>(null) }
    var hasObservedCallId by remember(targetIds) { mutableStateOf(false) }
    var refreshFailed by remember(targetIds) { mutableStateOf(false) }

    LaunchedEffect(isVisible, targetIds, latestCallId, refreshGeneration) {
        if (!isVisible || targets.isEmpty()) return@LaunchedEffect
        val callChanged = hasObservedCallId && previousCallId != latestCallId
        val manuallyRefreshed = refreshGeneration != handledRefreshGeneration
        isLoading = true
        refreshFailed = false
        try {
            val reads = coroutineScope {
                targets.map { target ->
                    async {
                        val result = runCatching {
                            quotaService.read(
                                modelConfigurationId = target.id,
                                forceRefresh = callChanged || manuallyRefreshed,
                            )
                        }
                        result.exceptionOrNull()?.let { error ->
                            if (error is CancellationException) throw error
                        }
                        target.id to result
                    }
                }.awaitAll()
            }
            val updated = observations.toMutableMap()
            reads.forEach { (targetId, result) ->
                result.onSuccess { updated[targetId] = it }
                if (result.isFailure) refreshFailed = true
            }
            observations = updated
        } finally {
            previousCallId = latestCallId
            hasObservedCallId = true
            handledRefreshGeneration = refreshGeneration
            isLoading = false
        }
    }

    val backgroundPolicies = remember(aiCatalog) { runtimeBackgroundQuotaPolicies(aiCatalog) }
    var showPolicies by remember { mutableStateOf(false) }
    if (tokenStats == null && targets.isEmpty() && backgroundPolicies.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    translation.usageTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (targets.isNotEmpty()) {
                    IconButton(
                        onClick = { refreshGeneration++ },
                        enabled = !isLoading,
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = translation.refreshUsageDescription)
                        }
                    }
                }
            }

            RuntimeContextUsage(tokenStats)
            RuntimeTokenUsageSummary(tokenStats)

            observations.values.forEach { observation ->
                RuntimeQuotaObservation(observation)
            }
            if (refreshFailed) {
                Text(
                    if (observations.isEmpty()) {
                        translation.quotaUnavailableLabel
                    } else {
                        translation.quotaStaleLabel
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (observations.isEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }

            if (backgroundPolicies.isNotEmpty()) {
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPolicies = !showPolicies },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        translation.backgroundQuotaPolicyLabel,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Icon(
                        if (showPolicies) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showPolicies) {
                            translation.collapseDescription
                        } else {
                            translation.expandDescription
                        },
                    )
                }
                if (showPolicies) {
                    backgroundPolicies.forEach { (connection, policy) ->
                        Text(
                            text = if (policy.enabled) {
                                "${connection.displayName} · reserve=${policy.reservePercent.runtimePercent()}% · " +
                                    "headroom=${policy.minimumHeadroomPercent.runtimePercent()}% · " +
                                    "refresh=${policy.refreshIntervalSeconds}s"
                            } else {
                                "${connection.displayName} · disabled"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeContextUsage(tokenStats: TokenUsageStatistics.ThreadTotals?) {
    val translation = LocalTranslation.current.runtime
    if (tokenStats?.contextStatus == TokenUsageStatistics.ContextStatus.OUT_OF_RANGE) {
        Text(
            text = "${translation.contextLabel} unavailable · provider reported " +
                "${tokenStats.reportedContextSize?.formatWithCommas() ?: "unknown"} tokens",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    val currentContext = tokenStats?.currentContextSize ?: return
    val contextWindow = tokenStats.contextWindowTokens
    if (contextWindow == null) {
        Text(
            "${translation.contextLabel} · ${currentContext.formatWithCommas()} ${translation.tokensLabel}",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val progress = (currentContext.toFloat() / contextWindow).coerceIn(0f, 1f)
    val percentage = (progress * 100).toInt()
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
        color = when {
            percentage >= 90 -> MaterialTheme.colorScheme.error
            percentage >= 75 -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
    )
    Text(
        text = "${translation.contextLabel} $percentage% · " +
            "${currentContext.formatWithCommas()} / ${contextWindow.formatWithCommas()}",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun RuntimeTokenUsageSummary(tokenStats: TokenUsageStatistics.ThreadTotals?) {
    val stats = tokenStats ?: return
    val translation = LocalTranslation.current.runtime
    Text(
        text = buildList {
            stats.lastCallTokens?.let { add("${translation.lastUsageLabel} ${it.formatWithCommas()}") }
            add("${translation.threadUsageLabel} ${stats.totalTokens.formatWithCommas()}")
            if (stats.totalCacheReadTokens > 0) {
                add("${translation.cacheReadUsageLabel} ${stats.totalCacheReadTokens.formatWithCommas()}")
            }
        }.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val observedRuntime = listOfNotNull(stats.provider, stats.modelId).joinToString(" · ")
    if (observedRuntime.isNotBlank()) {
        Text(
            text = "${translation.lastCallLabel}: $observedRuntime",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RuntimeQuotaObservation(observation: AiSubscriptionQuotaObservation) {
    val translation = LocalTranslation.current.runtime
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${observation.connectionKind.provider.name} · " +
                "${observation.connectionDisplayName} · ${observation.providerModelId}",
            style = MaterialTheme.typography.labelLarge,
        )
        when (observation.status) {
            AiSubscriptionQuotaObservation.Status.UNAVAILABLE,
            AiSubscriptionQuotaObservation.Status.NOT_SUPPORTED -> Text(
                translation.quotaUnavailableLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AiSubscriptionQuotaObservation.Status.FRESH,
            AiSubscriptionQuotaObservation.Status.STALE -> {
                if (observation.status == AiSubscriptionQuotaObservation.Status.STALE) {
                    Text(
                        translation.quotaStaleLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                val snapshot = requireNotNull(observation.snapshot)
                Text(
                    "${translation.quotaObservedLabel} " +
                        "${runtimeDurationLabel(Clock.System.now() - snapshot.observedAt)} " +
                        translation.quotaAgoLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    snapshot.unlimited -> Text(translation.quotaUnlimitedLabel)
                    snapshot.usageBlocked -> Text(
                        translation.quotaBlockedLabel,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> snapshot.windows.forEach { window ->
                        val usedProgress = (window.usedPercent / 100.0).toFloat().coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { usedProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = when {
                                window.usedPercent >= 90.0 -> MaterialTheme.colorScheme.error
                                window.usedPercent >= 75.0 -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                        Text(
                            "${window.displayName} · ${window.usedPercent.runtimePercent()}% · " +
                                "${translation.quotaResetLabel} " +
                                runtimeDurationLabel(window.resetsAt - Clock.System.now()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

internal fun runtimeQuotaModelConfigurations(
    agent: AgentDefinition,
    aiCatalog: AiCatalog,
): List<AiModelConfiguration> = buildList {
    add(agent.runtimeSelection)
    aiCatalog.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE)?.let(::add)
    aiCatalog.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_MAINTENANCE)?.let(::add)
}.mapNotNull { selection ->
    aiCatalog.modelConfigurations.firstOrNull { it.id == selection.modelConfigurationId }
}.filter { configuration ->
    configuration.enabled && aiCatalog.connectionFor(configuration) is AiSubscriptionConnection
}.distinctBy { configuration ->
    configuration.connectionId to configuration.providerModelId
}

private fun runtimeBackgroundQuotaPolicies(aiCatalog: AiCatalog) = listOf(
    AiRuntimeAssignment.Purpose.MEMORY_WRITE,
    AiRuntimeAssignment.Purpose.MEMORY_MAINTENANCE,
).mapNotNull(aiCatalog::runtimeSelectionFor)
    .mapNotNull { selection ->
        aiCatalog.modelConfigurations.firstOrNull { it.id == selection.modelConfigurationId }
    }
    .mapNotNull { modelConfiguration ->
        val subscription = aiCatalog.connectionFor(modelConfiguration) as? AiSubscriptionConnection
            ?: return@mapNotNull null
        (subscription as AiConnection) to subscription.quotaPacing
    }
    .distinctBy { (connection, _) -> connection.id }

internal fun runtimeDurationLabel(duration: Duration): String {
    if (duration <= ZERO) return "0m"
    val totalMinutes = duration.inWholeMinutes
    if (totalMinutes == 0L) return "<1m"
    val days = totalMinutes / (24 * 60)
    val hours = totalMinutes % (24 * 60) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

internal fun runtimeAutoCompactionLabel(
    connectionKind: AiConnection.Kind?,
    thresholdTokens: Int?,
): String? = when {
    connectionKind == AiConnection.Kind.CLAUDE_CODE -> "auto compact=provider-managed"
    connectionKind == AiConnection.Kind.OPENAI_SUBSCRIPTION && thresholdTokens != null ->
        "auto compact=${thresholdTokens.formatWithCommas()}"
    thresholdTokens != null -> "auto compact=unsupported"
    else -> null
}

private fun Double.runtimePercent(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

@Composable
private fun RuntimeTasksSection(
    runtimeSnapshot: ConversationRuntimeSnapshot?,
    onCancelCommandTask: (CommandTask.Id) -> Unit,
    onCancelCommandMonitor: (CommandMonitor.Id) -> Unit,
) {
    val appTranslation = LocalTranslation.current
    val translation = appTranslation.runtime
    val activeTask = runtimeSnapshot?.activeTask
    val pendingTasks = runtimeSnapshot?.pendingTasks.orEmpty()
    val runningTools = runtimeSnapshot?.runningToolActivities(translation).orEmpty()
    val activeCommands = runtimeSnapshot?.commandTasks.orEmpty().filter { it.status == CommandTask.Status.WORKING }
    val activeMonitors = runtimeSnapshot?.commandMonitors.orEmpty().activeForRuntimePanel()
    val incidents = runtimeSnapshot?.incidents.orEmpty()
    if (
        activeTask == null &&
        pendingTasks.isEmpty() &&
        runningTools.isEmpty() &&
        activeCommands.isEmpty() &&
        activeMonitors.isEmpty() &&
        incidents.isEmpty()
    ) {
        return
    }

    Spacer(modifier = Modifier.height(12.dp))
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                translation.tasksTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                activeTask?.let { task ->
                    RuntimeTaskRow(
                        if (runtimeSnapshot.state?.activeTaskStartedAt == null) {
                            translation.claimedTaskLabel
                        } else {
                            translation.runningTaskLabel
                        },
                        task.payload.runtimeLabel(translation),
                    )
                }
                pendingTasks.forEach { task ->
                    RuntimeTaskRow(translation.pendingTaskLabel, task.payload.runtimeLabel(translation))
                }
                runningTools.forEach { caption -> RuntimeTaskRow(translation.toolTaskLabel, caption) }
                activeCommands.forEach { commandTask ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = commandTask.command,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = commandTask.outputBytes.formatBytes(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onCancelCommandTask(commandTask.id) }) {
                            Text(translation.killButton)
                        }
                    }
                }
                activeMonitors.forEach { monitor ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag(UiTestTag.CommandMonitorItem(monitor.id.value).value),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = monitor.filterCommand,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = buildList {
                                    add(monitor.mode.runtimeMonitorModeLabel(translation))
                                    add("${translation.monitorEventsLabel}: ${monitor.eventCount}")
                                    add(monitor.workerId.value)
                                }.joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            monitor.lastEventPreview?.takeIf { it.isNotBlank() }?.let { preview ->
                                Text(
                                    text = preview,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(
                            onClick = { onCancelCommandMonitor(monitor.id) },
                            enabled = monitor.cancellationRequestedAt == null,
                        ) {
                            Text(
                                if (monitor.cancellationRequestedAt == null) {
                                    appTranslation.cancelButton
                                } else {
                                    translation.cancellingStatus
                                }
                            )
                        }
                    }
                }
                incidents.forEach { incident ->
                    RuntimeTaskRow(
                        if (incident.kind == com.gromozeka.domain.service.ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN) {
                            translation.unknownTaskLabel
                        } else {
                            translation.failedTaskLabel
                        },
                        incident.message,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeTaskRow(kind: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = kind,
            modifier = Modifier.width(54.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PendingMessagesSection(
    isWaitingForResponse: Boolean,
    pendingMessages: List<PendingUserMessage>,
    onSendInCurrentTurn: (String) -> Unit,
    onEdit: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    if (pendingMessages.isEmpty()) return

    val translation = LocalTranslation.current.runtime
    val orderedMessages = pendingMessages.orderedForDisplay()
    val steeringMessages = orderedMessages.filter { it.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT }
    val queuedMessages = orderedMessages.filter { it.placement == QueuedMessagePlacement.END_OF_TURN }

    Spacer(modifier = Modifier.height(12.dp))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .testTag(UiTestTag.PendingMessagesPanel.value),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "${translation.queueTitle} ${pendingMessages.size}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PendingMessageGroup(
                    title = translation.currentTurnLabel,
                    messages = steeringMessages,
                    isWaitingForResponse = isWaitingForResponse,
                    onSendInCurrentTurn = onSendInCurrentTurn,
                    onEdit = onEdit,
                    onCancel = onCancel,
                )
                PendingMessageGroup(
                    title = translation.afterResponseLabel,
                    messages = queuedMessages,
                    isWaitingForResponse = isWaitingForResponse,
                    onSendInCurrentTurn = onSendInCurrentTurn,
                    onEdit = onEdit,
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun PendingMessageGroup(
    title: String,
    messages: List<PendingUserMessage>,
    isWaitingForResponse: Boolean,
    onSendInCurrentTurn: (String) -> Unit,
    onEdit: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    if (messages.isEmpty()) return

    val translation = LocalTranslation.current

    Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    messages.forEach { message ->
        Column {
            Text(
                text = message.text.ifBlank {
                    message.artifacts.joinToString { it.fileName }
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = queuePlacementDescription(message.placement, translation.runtime),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (
                    isWaitingForResponse &&
                    message.agentDefinitionId != null &&
                    message.placement == QueuedMessagePlacement.END_OF_TURN
                ) {
                    TextButton(onClick = { onSendInCurrentTurn(message.id) }) {
                        Text(translation.runtime.currentTurnLabel)
                    }
                }
                TextButton(onClick = { onEdit(message.id) }) {
                    Text(translation.runtime.editButton)
                }
                TextButton(onClick = { onCancel(message.id) }) {
                    Text(translation.cancelButton)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun RuntimeStatusFooter(
    agentName: String?,
    isWaitingForResponse: Boolean,
    executionPauseRequested: Boolean,
    pttState: PttState,
    pttStatusMessage: String?,
    pendingMessages: List<PendingUserMessage>,
    runtimeSnapshot: ConversationRuntimeSnapshot?,
    activeGeneration: ActiveGenerationSnapshot?,
    remoteConnectionState: RemoteConnectionState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val translation = LocalTranslation.current.runtime
    val voiceError = pttStatusMessage?.takeIf { it.isNotBlank() }
    val activeCommands = runtimeSnapshot?.commandTasks.orEmpty().filter { it.status == CommandTask.Status.WORKING }
    val activeMonitors = runtimeSnapshot?.commandMonitors.orEmpty().activeForRuntimePanel()
    val runningTools = runtimeSnapshot?.runningToolActivities(translation).orEmpty().distinct()
    val activeTask = runtimeSnapshot?.activeTask
    val activeGenerationElapsedSeconds by produceState(
        initialValue = activeGeneration?.elapsedSeconds() ?: 0L,
        key1 = activeGeneration?.generationId,
    ) {
        val generation = activeGeneration ?: return@produceState
        while (true) {
            value = generation.elapsedSeconds()
            delay(1_000)
        }
    }
    val controlState = runtimeSnapshot?.state?.controlState
    val controllableRuntimeHasWork = runtimeSnapshot?.state != null || activeTask != null || activeGeneration != null ||
        runtimeSnapshot?.pendingTasks.orEmpty().isNotEmpty() || activeCommands.isNotEmpty() || runningTools.isNotEmpty()
    val runtimeHasWork = controllableRuntimeHasWork || activeMonitors.isNotEmpty()
    val isPaused = executionPauseRequested ||
        controlState == ConversationExecutionState.ControlState.PAUSE_REQUESTED ||
        controlState == ConversationExecutionState.ControlState.PAUSED
    val isStopping = controlState == ConversationExecutionState.ControlState.STOPPING ||
        controlState == ConversationExecutionState.ControlState.INTERRUPTING
    val isReady = !isWaitingForResponse && !runtimeHasWork && pendingMessages.isEmpty() &&
        pttState == PttState.IDLE && voiceError == null
    val statusText = when {
        voiceError != null -> voiceError
        pttState == PttState.PREPARING -> translation.preparingVoiceStatus
        pttState == PttState.TRANSCRIBING -> translation.transcribingVoiceStatus
        pttState == PttState.RECORDING -> translation.recordingVoiceStatus
        controlState == ConversationExecutionState.ControlState.PAUSE_REQUESTED ->
            translation.pauseRequestedStatus
        controlState == ConversationExecutionState.ControlState.PAUSED -> translation.pausedStatus
        controlState == ConversationExecutionState.ControlState.STOPPING -> translation.stoppingStatus
        controlState == ConversationExecutionState.ControlState.INTERRUPTING -> translation.interruptingStatus
        executionPauseRequested -> translation.pauseRequestedStatus
        runningTools.size == 1 -> runningTools.single()
        runningTools.size > 1 -> runningTools.joinToString(" · ")
        activeTask != null -> activeTask.payload.runtimeStatusLabel(agentName, translation)
        isWaitingForResponse -> agentName?.let { "$it ${translation.agentWorkingStatus}" }
            ?: translation.agentInvocationTask
        activeCommands.size == 1 -> translation.commandRunningStatus
        activeCommands.size > 1 -> "${translation.commandsRunningStatus}: ${activeCommands.size}"
        activeMonitors.size == 1 -> translation.monitorRunningStatus
        activeMonitors.size > 1 -> "${translation.monitorsRunningStatus}: ${activeMonitors.size}"
        pendingMessages.isNotEmpty() -> "${translation.queuedStatus} ${pendingMessages.size}"
        else -> translation.readyStatus
    }
    val detailText = activeGeneration?.detailsText(activeGenerationElapsedSeconds)
        ?: runtimeSnapshot?.runtimeDetailsText(translation)
        ?.takeIf { it.isNotBlank() }
        ?: runtimeSnapshot?.trace?.lastOrNull()?.runtimeTraceText()
    val containerColor = when {
        voiceError != null -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
        isReady -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val contentColor = when {
        voiceError != null -> MaterialTheme.colorScheme.onErrorContainer
        isReady -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when {
        voiceError != null -> Icons.Default.ErrorOutline
        pttState == PttState.RECORDING -> Icons.Default.FiberManualRecord
        isReady -> Icons.Default.CheckCircle
        pendingMessages.isEmpty() -> Icons.Default.HourglassTop
        else -> Icons.AutoMirrored.Filled.PlaylistAddCheck
    }

    Spacer(modifier = Modifier.height(12.dp))
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag(UiTestTag.ConversationProgressStrip.value),
        color = containerColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pttState == PttState.PREPARING || pttState == PttState.TRANSCRIBING) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                    )
                    if (!detailText.isNullOrBlank() && !isReady && voiceError == null) {
                        Text(
                            text = detailText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.78f),
                        )
                    }
                }
                if ((isWaitingForResponse || controllableRuntimeHasWork) && !isStopping) {
                    TextButton(onClick = if (isPaused) onResume else onPause) {
                        Text(if (isPaused) translation.resumeButton else translation.pauseButton)
                    }
                    TextButton(onClick = onStop) {
                        Text(translation.stopButton)
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 10.dp),
                color = contentColor.copy(alpha = 0.15f),
            )
            RemoteConnectionStatus(
                state = remoteConnectionState,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

private fun ActiveGenerationSnapshot.elapsedSeconds(): Long =
    (Clock.System.now() - startedAt).inWholeSeconds.coerceAtLeast(0)

private fun ActiveGenerationSnapshot.detailsText(elapsedSeconds: Long): String = buildList {
    add("${elapsedSeconds}s")
    add("#$iteration")
    add(modelName)
    add("$inputMessageCount msg / $inputContentItemCount blocks")
    add("$systemPromptCount prompts")
    add("$availableToolCount tools")
    add(provider.lowercase().replace('_', ' '))
}.joinToString(" · ")

private fun ConversationRuntimeTask.Payload.runtimeLabel(translation: Translation.RuntimeTranslation): String =
    when (this) {
        is ConversationRuntimeTask.Payload.PostMessage -> translation.messagePostTask
        is ConversationRuntimeTask.Payload.AgentInvocation -> translation.agentInvocationTask
        is ConversationRuntimeTask.Payload.HistoryMutation -> translation.historyMutationTask
        is ConversationRuntimeTask.Payload.LlmCall -> translation.llmCallTask
        is ConversationRuntimeTask.Payload.ToolExecution -> translation.toolExecutionTask
        is ConversationRuntimeTask.Payload.ToolResultProcessing -> translation.toolResultProcessingTask
        is ConversationRuntimeTask.Payload.MemoryRecall -> translation.memoryRecallTask
        is ConversationRuntimeTask.Payload.MemoryRunCompletion -> translation.memoryRunCompletionTask
        is ConversationRuntimeTask.Payload.BackgroundActivityCompletion -> translation.backgroundActivityDeliveryTask
        is ConversationRuntimeTask.Payload.ExecutionIncident -> translation.executionIncidentTask
    }

private fun ConversationRuntimeTask.Payload.runtimeStatusLabel(
    agentName: String?,
    translation: Translation.RuntimeTranslation,
): String = when (this) {
    is ConversationRuntimeTask.Payload.PostMessage -> translation.messagePostTask
    is ConversationRuntimeTask.Payload.AgentInvocation -> agentName?.let { "$it ${translation.agentWorkingStatus}" }
        ?: translation.agentInvocationTask
    is ConversationRuntimeTask.Payload.HistoryMutation -> translation.historyMutationStatus
    is ConversationRuntimeTask.Payload.LlmCall -> translation.modelRequestStatus
    is ConversationRuntimeTask.Payload.ToolExecution -> translation.toolExecutionStatus
    is ConversationRuntimeTask.Payload.ToolResultProcessing -> translation.toolResultProcessingStatus
    is ConversationRuntimeTask.Payload.MemoryRecall -> translation.memoryRecallStatus
    is ConversationRuntimeTask.Payload.MemoryRunCompletion -> translation.memoryRunCompletionStatus
    is ConversationRuntimeTask.Payload.BackgroundActivityCompletion -> translation.backgroundActivityDeliveryStatus
    is ConversationRuntimeTask.Payload.ExecutionIncident -> translation.executionIncidentStatus
}

private fun ConversationRuntimeTask.Payload.agentDefinitionIdOrNull(): AgentDefinition.Id? = when (this) {
    is ConversationRuntimeTask.Payload.AgentInvocation -> agentDefinitionId
    is ConversationRuntimeTask.Payload.LlmCall -> agentDefinitionId
    is ConversationRuntimeTask.Payload.ToolExecution -> agentDefinitionId
    is ConversationRuntimeTask.Payload.ToolResultProcessing -> agentDefinitionId
    is ConversationRuntimeTask.Payload.MemoryRecall -> agentDefinitionId
    is ConversationRuntimeTask.Payload.MemoryRunCompletion -> agentDefinitionId
    is ConversationRuntimeTask.Payload.PostMessage,
    is ConversationRuntimeTask.Payload.HistoryMutation,
    is ConversationRuntimeTask.Payload.BackgroundActivityCompletion,
    is ConversationRuntimeTask.Payload.ExecutionIncident,
    -> null
}

private fun MemoryRun.Status.runtimeMemoryStatusLabel(translation: Translation.RuntimeTranslation): String =
    when (this) {
        MemoryRun.Status.QUEUED -> translation.memoryQueuedStatus
        MemoryRun.Status.RUNNING -> translation.memoryRunningStatus
        MemoryRun.Status.NEEDS_INPUT -> translation.memoryNeedsInputStatus
        MemoryRun.Status.SUCCESS, MemoryRun.Status.PARTIAL -> translation.memoryCompletedStatus
        MemoryRun.Status.FAILED -> translation.memoryFailedStatus
        MemoryRun.Status.CANCELLED -> translation.memoryCancelledStatus
    }

private fun CommandMonitor.Mode.runtimeMonitorModeLabel(
    translation: Translation.RuntimeTranslation,
): String = when (this) {
    CommandMonitor.Mode.ONCE -> translation.monitorOnceMode
    CommandMonitor.Mode.CONTINUOUS -> translation.monitorContinuousMode
}

internal fun List<CommandMonitor>.activeForRuntimePanel(): List<CommandMonitor> =
    asSequence()
        .filterNot(CommandMonitor::isTerminal)
        .sortedWith(
            compareBy<CommandMonitor> { it.cancellationRequestedAt != null }
                .thenByDescending { it.lastEventAt ?: it.updatedAt }
        )
        .toList()

private fun ConversationRuntimeSnapshot.runtimeDetailsText(
    translation: Translation.RuntimeTranslation,
): String = buildList {
    activeTask?.payload?.let { add(it.runtimeLabel(translation)) }
    if (pendingTasks.isNotEmpty()) add("${translation.pendingDetailsLabel} ${pendingTasks.size}")
    commandTasks.count { !it.isTerminal }
        .takeIf { it > 0 }
        ?.let { add("${translation.commandsDetailsLabel} $it") }
    commandMonitors.count { !it.isTerminal }
        .takeIf { it > 0 }
        ?.let { add("${translation.monitorsDetailsLabel} $it") }
    if (incidents.isNotEmpty()) add("${translation.incidentsDetailsLabel} ${incidents.size}")
}.joinToString(" · ")

private fun ConversationRuntimeTraceEntry.runtimeTraceText(): String = buildString {
    append(kind.name.lowercase().replace('_', ' '))
    message?.takeIf { it.isNotBlank() }?.let {
        append(": ")
        append(it)
    }
}

private fun queuePlacementDescription(
    placement: QueuedMessagePlacement,
    translation: Translation.RuntimeTranslation,
): String = when (placement) {
    QueuedMessagePlacement.AFTER_TOOL_RESULT -> translation.nearestToolResultPlacement
    QueuedMessagePlacement.END_OF_TURN -> translation.currentResponsePlacement
}

private fun List<PendingUserMessage>.orderedForDisplay(): List<PendingUserMessage> =
    withIndex()
        .sortedWith(
            compareBy(
                { if (it.value.placement == QueuedMessagePlacement.AFTER_TOOL_RESULT) 0 else 1 },
                { it.index },
            )
        )
        .map { it.value }

private fun Long.formatBytes(): String = when {
    this < 1_024 -> "$this B"
    this < 1_048_576 -> "${this / 1_024} KiB"
    else -> "${this / 1_048_576} MiB"
}

private fun Int.formatWithCommas(): String =
    toString().reversed().chunked(3).joinToString(",").reversed()
