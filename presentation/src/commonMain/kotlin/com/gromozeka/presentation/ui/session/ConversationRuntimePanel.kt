package com.gromozeka.presentation.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import com.gromozeka.presentation.ui.CompactIconButton
import com.gromozeka.presentation.ui.GromozekaTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.visual.Visual
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiSubscriptionConnection
import com.gromozeka.domain.model.ai.AiSubscriptionQuotaObservation
import com.gromozeka.domain.service.CommandMonitor
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.AiSubscriptionQuotaService
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.TokenStatisticsTable
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.viewmodel.PendingUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import com.gromozeka.presentation.ui.GromozekaLoadingIndicator

enum class RuntimeInspectionSection { COMMANDS, MONITORS, PROBLEMS }

data class RuntimeInspectionRequest(
    val conversationId: Conversation.Id,
    val section: RuntimeInspectionSection,
    val sequence: Long,
)

/** Inspection state only: independent of message routing and transient runtime tasks. */
@Stable
class RuntimeAgentTabSelection {
    private val selectedByConversation = mutableStateMapOf<Conversation.Id, AgentDefinition.Id>()

    internal fun resolve(
        conversationId: Conversation.Id,
        participantAgentIds: List<AgentDefinition.Id>,
    ): AgentDefinition.Id? = selectedByConversation[conversationId]
        ?.takeIf { it in participantAgentIds }
        ?: participantAgentIds.firstOrNull()

    internal fun select(conversationId: Conversation.Id, agentId: AgentDefinition.Id?) {
        if (agentId == null) selectedByConversation.remove(conversationId)
        else selectedByConversation[conversationId] = agentId
    }
}

@Composable
fun ConversationRuntimePanel(
    isVisible: Boolean,
    conversationId: Conversation.Id,
    participants: Set<Conversation.Participant>?,
    agentService: AgentDomainService,
    aiConfigurationProvider: AiConfigurationProvider,
    aiSubscriptionQuotaService: AiSubscriptionQuotaService,
    tokenStats: TokenUsageStatistics.ThreadTotals?,
    isWaitingForResponse: Boolean,
    pendingMessages: List<PendingUserMessage>,
    runtimeSnapshot: ConversationRuntimeSnapshot?,
    onCancelCommandTask: (CommandTask.Id) -> Unit,
    onCancelCommandMonitor: (CommandMonitor.Id) -> Unit,
    onSendInCurrentTurn: (String) -> Unit,
    onEditPendingMessage: (String) -> Unit,
    onCancelPendingMessage: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
    slideFromRight: Boolean = false,
    tabSelection: RuntimeAgentTabSelection = remember { RuntimeAgentTabSelection() },
    replyRoutingContent: @Composable () -> Unit = {},
    inspectionRequest: RuntimeInspectionRequest? = null,
    visuals: List<Visual> = emptyList(),
    selectedVisualId: String? = null,
    dirtyVisualIds: Set<String> = emptySet(),
    highlightedVisualIds: Set<String> = emptySet(),
    onClearVisualHighlights: (String) -> Unit = {},
    onSelectVisual: (String?) -> Unit = {},
    onCloseVisual: (Visual) -> Unit = {},
    visualContent: @Composable (Visual) -> Unit = {},
    visualLoadError: String? = null,
) {
    val translation = LocalTranslation.current.runtime
    val localization = LocalTranslation.current
    val selectedVisual = visuals.firstOrNull { it.id == selectedVisualId }
    LaunchedEffect(inspectionRequest?.sequence) {
        if (inspectionRequest?.conversationId == conversationId) onSelectVisual(null)
    }
    val aiCatalogSnapshot by aiConfigurationProvider.snapshotFlow.collectAsState()
    val visibleRuntime = runtimeSnapshot?.takeIf { it.conversationId == conversationId }
    val participantAgentIds = remember(participants) {
        participants.orEmpty().filterIsInstance<Conversation.Participant.Agent>()
            .map { it.agentDefinitionId }.distinct().sortedBy { it.value }
    }
    var agentDefinitions by remember(agentService) { mutableStateOf<List<AgentDefinition>?>(null) }
    var agentLoadFailed by remember(agentService) { mutableStateOf(false) }
    LaunchedEffect(isVisible, agentService) {
        if (!isVisible) return@LaunchedEffect
        try {
            agentService.observeAll().collect {
                agentDefinitions = it
                agentLoadFailed = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            agentLoadFailed = true
        }
    }
    val selectedAgentId = tabSelection.resolve(conversationId, participantAgentIds)
    LaunchedEffect(conversationId, participants) {
        // A temporarily missing conversation snapshot must not erase the saved selection.
        if (participants != null) {
            tabSelection.select(conversationId, tabSelection.resolve(conversationId, participantAgentIds))
        }
    }
    val connectedAgents = participantAgentIds.mapNotNull { id ->
        agentDefinitions?.firstOrNull { it.id == id }
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
            Column(modifier = Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = GromozekaTheme.spacing.contentPadding)
                    .padding(top = GromozekaTheme.spacing.contentPadding)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = selectedVisual?.title ?: translation.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        CompactIconButton(
                            onClick = { if (selectedVisual != null) onCloseVisual(selectedVisual) else onClose() },
                            icon = Icons.Default.Close,
                            contentDescription = if (selectedVisual != null) localization.text("visuals.close") else translation.closePanelDescription,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }

                    if (visuals.isNotEmpty()) {
                        SecondaryScrollableTabRow(
                            selectedTabIndex = if (selectedVisual == null) 0 else visuals.indexOf(selectedVisual) + 1,
                            edgePadding = 0.dp,
                            modifier = Modifier.fillMaxWidth().testTag("visual-tabs"),
                        ) {
                            Tab(selected = selectedVisual == null, onClick = { onSelectVisual(null) },
                                text = { Text(translation.title, maxLines = 1) }, modifier = Modifier.testTag("visual-tab-runtime"))
                            visuals.forEach { visual ->
                                VisualTab(visual.id, visual.title,
                                    selected = visual.id == selectedVisualId,
                                    dirty = visual.id in dirtyVisualIds,
                                    highlighted = visual.id in highlightedVisualIds,
                                    onSelect = { onSelectVisual(visual.id) },
                                    onClose = { onCloseVisual(visual) },
                                    onClearHighlight = { onClearVisualHighlights(visual.id) },
                                )
                            }
                        }
                    }
                    visualLoadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                if (selectedVisual != null) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { visualContent(selectedVisual) }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth()
                        .padding(horizontal = GromozekaTheme.spacing.contentPadding)
                        .padding(bottom = GromozekaTheme.spacing.contentPadding)) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            replyRoutingContent()
                            RuntimeAgentSection(
                                conversationId = conversationId,
                                participantAgentIds = participantAgentIds,
                                participantsLoaded = participants != null,
                                agentDefinitions = agentDefinitions,
                                agentLoadFailed = agentLoadFailed,
                                selectedAgentId = selectedAgentId,
                                onSelectAgent = { tabSelection.select(conversationId, it) },
                                aiCatalog = aiCatalogSnapshot?.catalog ?: aiConfigurationProvider.catalog,
                                tokenStats = tokenStats,
                                runtimeSnapshot = visibleRuntime,
                            )
                            // Thread usage and provider-account quotas are not owned by the selected tab.
                            key(conversationId) {
                                RuntimeUsageCard(
                                    isVisible = isVisible,
                                    agents = connectedAgents,
                                    aiCatalog = aiCatalogSnapshot?.catalog ?: aiConfigurationProvider.catalog,
                                    tokenStats = tokenStats,
                                    quotaService = aiSubscriptionQuotaService,
                                )
                                TokenStatisticsTable(
                                    tokenStats = tokenStats,
                                    modifier = Modifier.fillMaxWidth().testTag(UiTestTag.RuntimeTokenStatistics.value),
                                )
                            }
                        }

                        // Memory activity UI is hidden while the memory subsystem is being redesigned.
                        RuntimeTasksSection(
                            runtimeSnapshot = visibleRuntime,
                            onCancelCommandTask = onCancelCommandTask,
                            onCancelCommandMonitor = onCancelCommandMonitor,
                            inspectionRequest = inspectionRequest?.takeIf { isVisible && it.conversationId == conversationId },
                        )

                        PendingMessagesSection(
                            isWaitingForResponse = isWaitingForResponse,
                            pendingMessages = pendingMessages,
                            onSendInCurrentTurn = onSendInCurrentTurn,
                            onEdit = onEditPendingMessage,
                            onCancel = onCancelPendingMessage,
                        )
                    }
                }


            }
        }
    }
}

@Composable
private fun RuntimeAgentSection(
    conversationId: Conversation.Id,
    participantAgentIds: List<AgentDefinition.Id>,
    participantsLoaded: Boolean,
    agentDefinitions: List<AgentDefinition>?,
    agentLoadFailed: Boolean,
    selectedAgentId: AgentDefinition.Id?,
    onSelectAgent: (AgentDefinition.Id) -> Unit,
    aiCatalog: AiCatalog,
    tokenStats: TokenUsageStatistics.ThreadTotals?,
    runtimeSnapshot: ConversationRuntimeSnapshot?,
) {
    val localization = LocalTranslation.current
    if (!participantsLoaded) {
        GromozekaLoadingIndicator(modifier = Modifier.size(20.dp))
        return
    }
    if (participantAgentIds.isEmpty() || selectedAgentId == null) {
        Text(localization.text("session.runtime.noConnectedAgents"))
        return
    }
    SecondaryScrollableTabRow(
        selectedTabIndex = participantAgentIds.indexOf(selectedAgentId),
        edgePadding = 0.dp,
        modifier = Modifier.fillMaxWidth().testTag(UiTestTag.RuntimeAgentTabs.value),
    ) {
        participantAgentIds.forEach { id ->
            Tab(
                selected = id == selectedAgentId,
                onClick = { onSelectAgent(id) },
                text = {
                    Text(
                        agentDefinitions?.firstOrNull { it.id == id }?.name ?: id.value,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.testTag(UiTestTag.RuntimeAgentTab(id.value).value),
            )
        }
    }
    if (agentLoadFailed) {
        Text(
            localization.text("session.participants.loadAgentsFailed"),
            color = MaterialTheme.colorScheme.error,
        )
    }
    val agent = agentDefinitions?.firstOrNull { it.id == selectedAgentId }
    Box(Modifier.fillMaxWidth().testTag(UiTestTag.RuntimeAgentConfiguration.value)) {
        when {
            agent != null -> RuntimeConfigurationCard(agent, aiCatalog)
            agentDefinitions == null && !agentLoadFailed ->
                GromozekaLoadingIndicator(modifier = Modifier.size(20.dp))
            else -> Text(localization.text("session.participants.unavailableAgent"))
        }
    }
    val activeTask = runtimeSnapshot?.activeTask
        ?.takeIf { it.payload.agentDefinitionIdOrNull() == selectedAgentId }
        ?: runtimeSnapshot?.activeInsertions?.firstOrNull {
            it.payload.agentDefinitionIdOrNull() == selectedAgentId
        }
    val queuedTask = runtimeSnapshot?.continuationTask
        ?.takeIf { it.payload.agentDefinitionIdOrNull() == selectedAgentId }
        ?: runtimeSnapshot?.pendingTasks?.firstOrNull {
            it.payload.agentDefinitionIdOrNull() == selectedAgentId
        }
    Text(
        text = when {
            activeTask != null -> activeTask.payload.runtimeStatusLabel(agent?.name, localization)
            queuedTask != null -> "${localization.runtime.pendingTaskLabel}: " +
                queuedTask.payload.runtimeLabel(localization.runtime)
            else -> localization.runtime.readyStatus
        },
        modifier = Modifier.testTag(UiTestTag.RuntimeAgentActivity.value),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    runtimeLastAgentCall(conversationId, selectedAgentId, tokenStats)?.let { call ->
        Column(
            modifier = Modifier.testTag(UiTestTag.RuntimeAgentUsage.value),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                localization.text("session.runtime.context.lastCall",
                    "runtime" to "${runtimeProviderLabel(call.provider, localization)} · ${call.modelId}"),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                localization.text("session.runtime.context.lastUsage", "count" to call.totalTokens.formatWithCommas()),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// recentCalls is bounded: show the last attributed call, not invented per-agent lifetime totals.
internal fun runtimeLastAgentCall(
    conversationId: Conversation.Id,
    agentId: AgentDefinition.Id,
    tokenStats: TokenUsageStatistics.ThreadTotals?,
): TokenUsageStatistics? = tokenStats?.recentCalls
    ?.filter { it.agentDefinitionId == agentId && (it.conversationId == null || it.conversationId == conversationId) }
    ?.maxByOrNull { it.timestamp }

@Composable
private fun RuntimeConfigurationCard(
    agent: AgentDefinition,
    aiCatalog: AiCatalog,
) {
    val localization = LocalTranslation.current
    val configuration = aiCatalog.modelConfigurations.firstOrNull {
        it.id == agent.runtimeSelection.modelConfigurationId
    }
    val connection = configuration?.let(aiCatalog::connectionFor)
    val modelSpec = configuration?.let(aiCatalog::modelSpecFor)
    val reasoning = agent.runtimeOverrides.reasoning ?: configuration?.defaultParameters?.reasoning
    val maxOutputTokens = agent.runtimeOverrides.maxOutputTokens ?: configuration?.defaultParameters?.maxOutputTokens
    val parameters = buildList {
        reasoning?.mode?.let { add(localization.text("session.runtime.parameters.mode", "mode" to it.runtimeDisplayName(localization))) }
        reasoning?.effort?.let { add(localization.text("session.runtime.parameters.effort", "effort" to it.runtimeDisplayName(localization))) }
        reasoning?.display?.let { add(localization.text("session.runtime.parameters.thinkingDisplay", "display" to it.runtimeDisplayName(localization))) }
        reasoning?.budgetTokens?.let { add(localization.text("session.runtime.parameters.budget", "count" to it.formatWithCommas())) }
        maxOutputTokens?.let { add(localization.text("session.runtime.parameters.maxOutput", "count" to it.formatWithCommas())) }
        configuration?.defaultParameters?.temperature?.let { add(localization.text("session.runtime.parameters.temperature", "temperature" to it)) }
        configuration?.defaultParameters?.timeoutSeconds?.let { add(localization.text("session.runtime.parameters.timeout", "seconds" to it)) }
        configuration?.assistantResponseFormat?.let { add(localization.text("session.runtime.parameters.responseFormat", "format" to it.runtimeDisplayName(localization))) }
        runtimeAutoCompactionLabel(connection?.kind, modelSpec?.autoCompactionThresholdTokens, localization)?.let(::add)
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
                connection?.kind?.provider?.runtimeDisplayName(localization),
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
    agents: List<AgentDefinition>,
    aiCatalog: AiCatalog,
    tokenStats: TokenUsageStatistics.ThreadTotals?,
    quotaService: AiSubscriptionQuotaService,
) {
    val localization = LocalTranslation.current
    val translation = LocalTranslation.current.runtime
    val targets = remember(agents.map { it.runtimeSelection }, aiCatalog) {
        runtimeQuotaModelConfigurations(agents, aiCatalog)
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
        modifier = Modifier.fillMaxWidth().testTag(UiTestTag.RuntimeSharedUsage.value),
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
                    localization.text("session.runtime.sharedUsageTitle"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (targets.isNotEmpty()) {
                    IconButton(
                        onClick = { refreshGeneration++ },
                        enabled = !isLoading,
                    ) {
                        if (isLoading) {
                            GromozekaLoadingIndicator(modifier = Modifier.size(20.dp))
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
                                localization.text("session.runtime.quota.policyEnabled",
                                    "connectionName" to connection.displayName,
                                    "reservePercent" to policy.reservePercent.runtimePercent(),
                                    "headroomPercent" to policy.minimumHeadroomPercent.runtimePercent(),
                                    "refreshSeconds" to policy.refreshIntervalSeconds)
                            } else {
                                localization.text("session.runtime.quota.policyDisabled", "connectionName" to connection.displayName)
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
    val localization = LocalTranslation.current
    if (tokenStats?.contextStatus == TokenUsageStatistics.ContextStatus.OUT_OF_RANGE) {
        Text(
            text = tokenStats.reportedContextSize?.let {
                localization.plural("session.runtime.context.reportedUnavailable", it.toLong())
            } ?: localization.text("session.runtime.context.reportedUnknown"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    val currentContext = tokenStats?.currentContextSize ?: return
    val contextWindow = tokenStats.contextWindowTokens
    if (contextWindow == null) {
        Text(
            localization.plural("session.runtime.context.withoutLimit", currentContext.toLong()),
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
        text = localization.text("session.runtime.context.withLimit", "percent" to percentage,
            "currentCount" to currentContext.formatWithCommas(), "limitCount" to contextWindow.formatWithCommas()),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun RuntimeTokenUsageSummary(tokenStats: TokenUsageStatistics.ThreadTotals?) {
    val localization = LocalTranslation.current
    val stats = tokenStats ?: return
    Text(
        text = buildList {
            stats.lastCallTokens?.let { add(localization.text("session.runtime.context.lastUsage", "count" to it.formatWithCommas())) }
            add(localization.text("session.runtime.context.threadUsage", "count" to stats.totalTokens.formatWithCommas()))
            if (stats.totalCacheReadTokens > 0) {
                add(localization.text("session.runtime.context.cacheReadUsage", "count" to stats.totalCacheReadTokens.formatWithCommas()))
            }
        }.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val observedRuntime = listOfNotNull(stats.provider?.let { runtimeProviderLabel(it, localization) }, stats.modelId).joinToString(" · ")
    if (observedRuntime.isNotBlank()) {
        Text(
            text = localization.text("session.runtime.context.lastCall", "runtime" to observedRuntime),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RuntimeQuotaObservation(observation: AiSubscriptionQuotaObservation) {
    val localization = LocalTranslation.current
    val translation = LocalTranslation.current.runtime
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${observation.connectionKind.provider.runtimeDisplayName(localization)} · " +
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
                    localization.text("session.runtime.quota.observedAgo",
                        "duration" to runtimeDurationLabel(Clock.System.now() - snapshot.observedAt, localization)),
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
                            localization.text("session.runtime.quota.window", "windowName" to window.displayName,
                                "usedPercent" to window.usedPercent.runtimePercent(),
                                "duration" to runtimeDurationLabel(window.resetsAt - Clock.System.now(), localization)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

internal fun runtimeQuotaModelConfigurations(
    agents: List<AgentDefinition>,
    aiCatalog: AiCatalog,
): List<AiModelConfiguration> = buildList {
    addAll(agents.map { it.runtimeSelection })
    aiCatalog.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_WRITE)?.let(::add)
    aiCatalog.runtimeSelectionFor(AiRuntimeAssignment.Purpose.MEMORY_MAINTENANCE)?.let(::add)
}.mapNotNull { selection ->
    aiCatalog.modelConfigurations.firstOrNull { it.id == selection.modelConfigurationId }
}.filter { configuration ->
    configuration.enabled && aiCatalog.connectionFor(configuration) is AiSubscriptionConnection
}.distinctBy { configuration ->
    configuration.connectionId to configuration.providerModelId
}.sortedBy { it.id.value }

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

internal fun runtimeDurationLabel(duration: Duration, localization: Translation): String {
    if (duration <= ZERO) return localization.text("session.runtime.duration.zero")
    val totalMinutes = duration.inWholeMinutes
    if (totalMinutes == 0L) return localization.text("session.runtime.duration.lessThanMinute")
    val days = totalMinutes / (24 * 60)
    val hours = totalMinutes % (24 * 60) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> localization.text("session.runtime.duration.daysHours", "days" to days, "hours" to hours)
        hours > 0 -> localization.text("session.runtime.duration.hoursMinutes", "hours" to hours, "minutes" to minutes)
        else -> localization.text("session.runtime.duration.minutes", "minutes" to minutes)
    }
}

internal fun runtimeAutoCompactionLabel(
    connectionKind: AiConnection.Kind?,
    thresholdTokens: Int?,
    localization: Translation,
): String? = when {
    connectionKind in setOf(AiConnection.Kind.OPENAI_SUBSCRIPTION, AiConnection.Kind.CLAUDE_CODE) && thresholdTokens != null ->
        localization.plural("session.runtime.parameters.autoCompactionThreshold", thresholdTokens.toLong())
    connectionKind == AiConnection.Kind.CLAUDE_CODE -> localization.text("session.runtime.parameters.autoCompactionProvider")
    thresholdTokens != null -> localization.text("session.runtime.parameters.autoCompactionUnsupported")
    else -> null
}

private fun Double.runtimePercent(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

@Composable
private fun RuntimeTasksSection(
    runtimeSnapshot: ConversationRuntimeSnapshot?,
    onCancelCommandTask: (CommandTask.Id) -> Unit,
    onCancelCommandMonitor: (CommandMonitor.Id) -> Unit,
    inspectionRequest: RuntimeInspectionRequest?,
) {
    val localization = LocalTranslation.current
    val appTranslation = localization
    val translation = localization.runtime
    val activeTask = runtimeSnapshot?.activeTask
    val pendingTasks = runtimeSnapshot?.pendingTasks.orEmpty()
    val runningTools = runtimeSnapshot?.runningToolActivities(localization).orEmpty()
    val activeCommands = runtimeSnapshot?.commandTasks.orEmpty().filter { it.status == CommandTask.Status.WORKING }
    val activeMonitors = runtimeSnapshot?.commandMonitors.orEmpty().activeForRuntimePanel()
    val turnProblems = runtimeSnapshot?.lastTurn?.problems.orEmpty()
    val collaborationRequests = runtimeSnapshot?.agentRequests.orEmpty()
    val incidents = runtimeSnapshot?.incidents.orEmpty().filterNot { incident ->
        turnProblems.any { it.key == "incident:${incident.task.id.value}" }
    }
    val commandsRequester = remember { BringIntoViewRequester() }
    val monitorsRequester = remember { BringIntoViewRequester() }
    val problemsRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(inspectionRequest) {
        when (inspectionRequest?.section) {
            RuntimeInspectionSection.COMMANDS -> commandsRequester.bringIntoView()
            RuntimeInspectionSection.MONITORS -> monitorsRequester.bringIntoView()
            RuntimeInspectionSection.PROBLEMS -> problemsRequester.bringIntoView()
            null -> Unit
        }
    }
    if (
        activeTask == null &&
        pendingTasks.isEmpty() &&
        runningTools.isEmpty() &&
        activeCommands.isEmpty() &&
        activeMonitors.isEmpty() &&
        incidents.isEmpty() &&
        turnProblems.isEmpty() && collaborationRequests.isEmpty()
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
                runtimeSnapshot?.let { AgentCollaborationRequests(collaborationRequests, it.conversationId) }
                if (turnProblems.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().bringIntoViewRequester(problemsRequester)
                        .testTag("runtime-turn-problems"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(localization.text("chat.activity.turnProblems", "count" to turnProblems.size),
                            style = MaterialTheme.typography.labelLarge)
                        turnProblems.forEach { problem ->
                            Text(problem.message, style = MaterialTheme.typography.bodySmall,
                                color = if (problem.outcomeUnknown) Color(0xFFFE8B17) else MaterialTheme.colorScheme.error)
                        }
                        HorizontalDivider()
                    }
                }
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
                Column(Modifier.fillMaxWidth().bringIntoViewRequester(commandsRequester)) {
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
                                    text = "${commandTask.workerId.value} · ${commandTask.outputBytes.formatBytes()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onCancelCommandTask(commandTask.id) }) {
                                Text(translation.killButton)
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().bringIntoViewRequester(monitorsRequester)) {
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
                                        add(localization.text("session.runtime.monitorEventCount", "count" to monitor.eventCount))
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
    val localization = LocalTranslation.current
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
                text = localization.text("session.runtime.queueCount", "count" to pendingMessages.size),
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
                    message.steeringAgentId != null &&
                    message.placement == QueuedMessagePlacement.END_OF_TURN
                ) {
                    TextButton(onClick = { onSendInCurrentTurn(message.id) }) {
                        Text(translation.runtime.currentTurnLabel)
                    }
                }
                if (message.editable) TextButton(onClick = { onEdit(message.id) }) {
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

private fun ConversationRuntimeTask.Payload.runtimeLabel(translation: Translation.RuntimeTranslation): String =
    when (this) {
        is ConversationRuntimeTask.Payload.PostMessage -> translation.messagePostTask
        is ConversationRuntimeTask.Payload.AgentInvocation -> translation.agentInvocationTask
        is ConversationRuntimeTask.Payload.AgentResponse -> translation.agentInvocationTask
        is ConversationRuntimeTask.Payload.HistoryMutation -> translation.historyMutationTask
        is ConversationRuntimeTask.Payload.LlmCall, is ConversationRuntimeTask.Payload.ResponseReview -> translation.llmCallTask
        is ConversationRuntimeTask.Payload.ToolExecution -> translation.toolExecutionTask
        is ConversationRuntimeTask.Payload.ToolResultProcessing -> translation.toolResultProcessingTask
        is ConversationRuntimeTask.Payload.MemoryRecall -> translation.memoryRecallTask
        is ConversationRuntimeTask.Payload.MemoryRunCompletion -> translation.memoryRunCompletionTask
        is ConversationRuntimeTask.Payload.BackgroundActivityCompletion -> translation.backgroundActivityDeliveryTask
        is ConversationRuntimeTask.Payload.ExecutionIncident -> translation.executionIncidentTask
    }

internal fun ConversationRuntimeTask.Payload.runtimeStatusLabel(
    agentName: String?,
    localization: Translation,
): String {
    val translation = localization.runtime
    return when (this) {
        is ConversationRuntimeTask.Payload.PostMessage -> translation.messagePostTask
        is ConversationRuntimeTask.Payload.AgentInvocation,
        is ConversationRuntimeTask.Payload.AgentResponse -> agentName?.let {
            localization.text("session.runtime.agentWorking", "agentName" to it)
        } ?: translation.agentInvocationTask
        is ConversationRuntimeTask.Payload.HistoryMutation -> translation.historyMutationStatus
        is ConversationRuntimeTask.Payload.LlmCall, is ConversationRuntimeTask.Payload.ResponseReview -> translation.modelRequestStatus
        is ConversationRuntimeTask.Payload.ToolExecution -> translation.toolExecutionStatus
        is ConversationRuntimeTask.Payload.ToolResultProcessing -> translation.toolResultProcessingStatus
        is ConversationRuntimeTask.Payload.MemoryRecall -> translation.memoryRecallStatus
        is ConversationRuntimeTask.Payload.MemoryRunCompletion -> translation.memoryRunCompletionStatus
        is ConversationRuntimeTask.Payload.BackgroundActivityCompletion -> translation.backgroundActivityDeliveryStatus
        is ConversationRuntimeTask.Payload.ExecutionIncident -> translation.executionIncidentStatus
    }
}

private fun ConversationRuntimeTask.Payload.agentDefinitionIdOrNull(): AgentDefinition.Id? = when (this) {
    is ConversationRuntimeTask.Payload.AgentInvocation -> agentDefinitionId
    is ConversationRuntimeTask.Payload.AgentResponse -> agentDefinitionId
    is ConversationRuntimeTask.Payload.LlmCall -> agentDefinitionId
    is ConversationRuntimeTask.Payload.ResponseReview -> agentDefinitionId
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
    localization: Translation,
): String = buildList {
    activeTask?.payload?.let { add(it.runtimeLabel(localization.runtime)) }
    if (pendingTasks.isNotEmpty()) add(localization.text("session.runtime.pendingTasks", "count" to pendingTasks.size))
    commandTasks.count { !it.isTerminal }
        .takeIf { it > 0 }
        ?.let { add(localization.text("session.runtime.commandCount", "count" to it)) }
    commandMonitors.count { !it.isTerminal }
        .takeIf { it > 0 }
        ?.let { add(localization.text("session.runtime.monitorCount", "count" to it)) }
    if (incidents.isNotEmpty()) add(localization.text("session.runtime.incidentCount", "count" to incidents.size))
}.joinToString(" · ")

private fun ConversationRuntimeTraceEntry.runtimeTraceText(localization: Translation): String {
    val label = kind.runtimeDisplayName(localization)
    return message?.takeIf(String::isNotBlank)?.let {
        localization.text("session.runtime.traceMessage", "kind" to label, "message" to it)
    } ?: label
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

/** Highlight the entire native Tab hit area, not just the title's Text bounds. */
@Composable
internal fun VisualTab(
    id: String, title: String, selected: Boolean, dirty: Boolean, highlighted: Boolean,
    onSelect: () -> Unit, onClose: () -> Unit, onClearHighlight: () -> Unit,
) {
    VisualHighlightContainer(highlighted, "tab-$id", onClearHighlight,
        modifier = Modifier.testTag("visual-tab-container-$id"),
        shape = androidx.compose.ui.graphics.RectangleShape,
        boundedGlow = true,
    ) {
        Tab(selected = selected, onClick = onSelect, modifier = Modifier.testTag("visual-tab-$id"),
            text = { VisualTabTitle(id, title, dirty, onClose) })
    }
}

/** Host chrome: the title and close button keep identical bounds with or without the dirty dot. */
@Composable
internal fun VisualTabTitle(id: String, title: String, dirty: Boolean, onClose: () -> Unit) {
    val translation = LocalTranslation.current
    VisualDirtyBadge(dirty, "visual-dirty-$id", Modifier.testTag("visual-title-$id")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp).testTag("visual-tab-label-$id"),
                color = MaterialTheme.colorScheme.onSurface)
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp).testTag("visual-close-$id")) {
                Icon(Icons.Default.Close, contentDescription = translation.text("visuals.close"), modifier = Modifier.size(16.dp))
            }
        }
    }
}
