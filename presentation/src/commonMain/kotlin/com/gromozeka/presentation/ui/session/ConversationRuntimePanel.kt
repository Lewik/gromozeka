package com.gromozeka.presentation.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.TokenUsageStatistics
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationExecutionState
import com.gromozeka.domain.service.ConversationRuntimeSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeToolExecution
import com.gromozeka.domain.service.ConversationRuntimeTraceEntry
import com.gromozeka.domain.service.QueuedMessagePlacement
import com.gromozeka.presentation.services.PttState
import com.gromozeka.presentation.ui.TokenStatisticsTable
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.ui.viewmodel.PendingUserMessage

@Composable
fun ConversationRuntimePanel(
    isVisible: Boolean,
    currentAgent: AgentDefinition,
    settings: Settings,
    tokenStats: TokenUsageStatistics.ThreadTotals?,
    isWaitingForResponse: Boolean,
    executionPauseRequested: Boolean,
    pttState: PttState,
    pttStatusMessage: String?,
    pendingMessages: List<PendingUserMessage>,
    runtimeSnapshot: ConversationRuntimeSnapshot?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onCancelCommandTask: (CommandTask.Id) -> Unit,
    onSendInCurrentTurn: (String) -> Unit,
    onEditPendingMessage: (String) -> Unit,
    onCancelPendingMessage: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
    slideFromRight: Boolean = false,
) {
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
                        text = "Runtime",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close runtime panel")
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RuntimeConfigurationCard(
                        agent = currentAgent,
                        settings = settings,
                        tokenStats = tokenStats,
                    )
                    TokenStatisticsTable(
                        tokenStats = tokenStats,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                RuntimeTasksSection(
                    runtimeSnapshot = runtimeSnapshot,
                    onCancelCommandTask = onCancelCommandTask,
                )

                PendingMessagesSection(
                    isWaitingForResponse = isWaitingForResponse,
                    pendingMessages = pendingMessages,
                    onSendInCurrentTurn = onSendInCurrentTurn,
                    onEdit = onEditPendingMessage,
                    onCancel = onCancelPendingMessage,
                )

                RuntimeStatusFooter(
                    agentName = currentAgent.name,
                    isWaitingForResponse = isWaitingForResponse,
                    executionPauseRequested = executionPauseRequested,
                    pttState = pttState,
                    pttStatusMessage = pttStatusMessage,
                    pendingMessages = pendingMessages,
                    runtimeSnapshot = runtimeSnapshot,
                    onPause = onPause,
                    onResume = onResume,
                    onStop = onStop,
                )
            }
        }
    }
}

@Composable
private fun RuntimeConfigurationCard(
    agent: AgentDefinition,
    settings: Settings,
    tokenStats: TokenUsageStatistics.ThreadTotals?,
) {
    val aiSettings = settings.userProfile.aiSettings
    val configuration = aiSettings.modelConfigurations.firstOrNull {
        it.id == agent.runtimeSelection.modelConfigurationId
    }
    val connection = configuration?.let(aiSettings::connectionFor)
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

            val currentContext = tokenStats?.currentContextSize
            val contextWindow = tokenStats?.contextWindowTokens
            if (currentContext != null && contextWindow != null) {
                val progress = (currentContext.toFloat() / contextWindow).coerceIn(0f, 1f)
                val percentage = (progress * 100).toInt()
                Spacer(modifier = Modifier.height(2.dp))
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
                    text = "Context $percentage% · ${currentContext.formatWithCommas()} / ${contextWindow.formatWithCommas()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            tokenStats?.let { stats ->
                Text(
                    text = buildList {
                        stats.lastCallTokens?.let { add("last ${it.formatWithCommas()}") }
                        add("thread ${stats.totalTokens.formatWithCommas()}")
                        if (stats.totalCacheReadTokens > 0) {
                            add("cache read ${stats.totalCacheReadTokens.formatWithCommas()}")
                        }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val observedRuntime = listOfNotNull(stats.provider, stats.modelId).joinToString(" · ")
                if (observedRuntime.isNotBlank() && observedRuntime != configuredRuntime) {
                    Text(
                        text = "Last call: $observedRuntime",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeTasksSection(
    runtimeSnapshot: ConversationRuntimeSnapshot?,
    onCancelCommandTask: (CommandTask.Id) -> Unit,
) {
    val activeTask = runtimeSnapshot?.activeTask
    val pendingTasks = runtimeSnapshot?.pendingTasks.orEmpty()
    val runningTools = runtimeSnapshot?.toolExecutions.orEmpty()
        .filter { it.status == ConversationRuntimeToolExecution.Status.RUNNING }
    val activeCommands = runtimeSnapshot?.commandTasks.orEmpty().filter { it.status == CommandTask.Status.WORKING }
    val incidents = runtimeSnapshot?.incidents.orEmpty()
    if (activeTask == null && pendingTasks.isEmpty() && runningTools.isEmpty() && activeCommands.isEmpty() && incidents.isEmpty()) {
        return
    }

    Spacer(modifier = Modifier.height(12.dp))
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("Tasks", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                activeTask?.let { task ->
                    RuntimeTaskRow(
                        if (runtimeSnapshot.state?.activeTaskStartedAt == null) "Claimed" else "Running",
                        task.payload.runtimeLabel(),
                    )
                }
                pendingTasks.forEach { task -> RuntimeTaskRow("Pending", task.payload.runtimeLabel()) }
                runningTools.forEach { tool -> RuntimeTaskRow("Tool", tool.toolName) }
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
                            Text("Kill")
                        }
                    }
                }
                incidents.forEach { incident ->
                    RuntimeTaskRow(
                        if (incident.kind == com.gromozeka.domain.service.ConversationRuntimeTaskIncident.Kind.OUTCOME_UNKNOWN) {
                            "Unknown"
                        } else {
                            "Failed"
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
                text = "Queue ${pendingMessages.size}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PendingMessageGroup(
                    title = "Current turn",
                    messages = steeringMessages,
                    isWaitingForResponse = isWaitingForResponse,
                    onSendInCurrentTurn = onSendInCurrentTurn,
                    onEdit = onEdit,
                    onCancel = onCancel,
                )
                PendingMessageGroup(
                    title = "After response",
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

    Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    messages.forEach { message ->
        Column {
            Text(
                text = message.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = queuePlacementDescription(message.placement),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isWaitingForResponse && message.placement == QueuedMessagePlacement.END_OF_TURN) {
                    TextButton(onClick = { onSendInCurrentTurn(message.id) }) {
                        Text("Current turn")
                    }
                }
                TextButton(onClick = { onEdit(message.id) }) {
                    Text("Edit")
                }
                TextButton(onClick = { onCancel(message.id) }) {
                    Text("Cancel")
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun RuntimeStatusFooter(
    agentName: String,
    isWaitingForResponse: Boolean,
    executionPauseRequested: Boolean,
    pttState: PttState,
    pttStatusMessage: String?,
    pendingMessages: List<PendingUserMessage>,
    runtimeSnapshot: ConversationRuntimeSnapshot?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val voiceError = pttStatusMessage?.takeIf { it.isNotBlank() }
    val activeCommands = runtimeSnapshot?.commandTasks.orEmpty().filter { it.status == CommandTask.Status.WORKING }
    val runningTools = runtimeSnapshot?.toolExecutions.orEmpty()
        .filter { it.status == ConversationRuntimeToolExecution.Status.RUNNING }
        .map { it.toolName }
        .distinct()
    val activeTask = runtimeSnapshot?.activeTask
    val controlState = runtimeSnapshot?.state?.controlState
    val runtimeHasWork = runtimeSnapshot?.state != null || activeTask != null ||
        runtimeSnapshot?.pendingTasks.orEmpty().isNotEmpty() || activeCommands.isNotEmpty() || runningTools.isNotEmpty()
    val isPaused = executionPauseRequested ||
        controlState == ConversationExecutionState.ControlState.PAUSE_REQUESTED ||
        controlState == ConversationExecutionState.ControlState.PAUSED
    val isStopping = controlState == ConversationExecutionState.ControlState.STOPPING ||
        controlState == ConversationExecutionState.ControlState.INTERRUPTING
    val isReady = !isWaitingForResponse && !runtimeHasWork && pendingMessages.isEmpty() &&
        pttState == PttState.IDLE && voiceError == null
    val statusText = when {
        voiceError != null -> voiceError
        pttState == PttState.TRANSCRIBING -> "Расшифровываю голос…"
        pttState == PttState.RECORDING -> "Идёт запись голоса"
        controlState == ConversationExecutionState.ControlState.PAUSE_REQUESTED -> "Пауза запрошена"
        controlState == ConversationExecutionState.ControlState.PAUSED -> "На паузе"
        controlState == ConversationExecutionState.ControlState.STOPPING -> "Останавливается"
        controlState == ConversationExecutionState.ControlState.INTERRUPTING -> "Прерывается"
        executionPauseRequested -> "Пауза запрошена"
        activeCommands.size == 1 -> "Команда выполняется"
        activeCommands.size > 1 -> "Выполняются команды: ${activeCommands.size}"
        runningTools.isNotEmpty() -> "Инструменты: ${runningTools.joinToString(", ")}"
        activeTask != null -> activeTask.payload.runtimeStatusLabel(agentName)
        isWaitingForResponse -> "$agentName работает"
        pendingMessages.isNotEmpty() -> "В очереди ${pendingMessages.size}"
        else -> "Готов"
    }
    val detailText = runtimeSnapshot?.runtimeDetailsText()
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
        else -> Icons.Default.PlaylistAddCheck
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
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pttState == PttState.TRANSCRIBING) {
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
            if ((isWaitingForResponse || runtimeHasWork) && !isStopping) {
                TextButton(onClick = if (isPaused) onResume else onPause) {
                    Text(if (isPaused) "Resume" else "Pause")
                }
                TextButton(onClick = onStop) {
                    Text("Stop")
                }
            }
        }
    }
}

private fun ConversationRuntimeTask.Payload.runtimeLabel(): String = when (this) {
    is ConversationRuntimeTask.Payload.UserTurn -> "User turn"
    is ConversationRuntimeTask.Payload.LlmCall -> "LLM call"
    is ConversationRuntimeTask.Payload.ToolExecution -> "Tool execution"
    is ConversationRuntimeTask.Payload.ToolResultProcessing -> "Tool result processing"
    is ConversationRuntimeTask.Payload.MemoryRecall -> "Memory recall"
    is ConversationRuntimeTask.Payload.ExecutionIncident -> "Execution incident"
}

private fun ConversationRuntimeTask.Payload.runtimeStatusLabel(agentName: String): String = when (this) {
    is ConversationRuntimeTask.Payload.UserTurn -> "$agentName работает"
    is ConversationRuntimeTask.Payload.LlmCall -> "Запрос к модели"
    is ConversationRuntimeTask.Payload.ToolExecution -> "Выполняется инструмент"
    is ConversationRuntimeTask.Payload.ToolResultProcessing -> "Обработка результата инструмента"
    is ConversationRuntimeTask.Payload.MemoryRecall -> "Вспоминание"
    is ConversationRuntimeTask.Payload.ExecutionIncident -> "Обработка сбоя выполнения"
}

private fun ConversationRuntimeSnapshot.runtimeDetailsText(): String = buildList {
    activeTask?.payload?.let { add(it.runtimeLabel()) }
    if (pendingTasks.isNotEmpty()) add("pending ${pendingTasks.size}")
    if (incidents.isNotEmpty()) add("incidents ${incidents.size}")
}.joinToString(" · ")

private fun ConversationRuntimeTraceEntry.runtimeTraceText(): String = buildString {
    append(kind.name.lowercase().replace('_', ' '))
    message?.takeIf { it.isNotBlank() }?.let {
        append(": ")
        append(it)
    }
}

private fun queuePlacementDescription(placement: QueuedMessagePlacement): String = when (placement) {
    QueuedMessagePlacement.AFTER_TOOL_RESULT -> "After the nearest tool result"
    QueuedMessagePlacement.END_OF_TURN -> "After the current response"
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
