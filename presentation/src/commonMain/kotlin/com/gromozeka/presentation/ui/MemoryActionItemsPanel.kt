package com.gromozeka.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.client.RemoteMemoryActionItemService
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.memory.MemoryScope
import com.gromozeka.domain.model.memory.MemoryActionItem
import com.gromozeka.remote.protocol.MemoryActionItemCounts
import com.gromozeka.remote.protocol.MemoryActionItemsResponse
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val log = KLoggers.logger("MemoryActionItemsPanel")

@Composable
fun MemoryActionItemsPanel(
    isVisible: Boolean,
    conversationId: Conversation.Id,
    refreshKey: Int,
    memoryActionItemService: RemoteMemoryActionItemService,
    coroutineScope: CoroutineScope,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
    slideFromRight: Boolean = false,
) {
    var includeClosed by remember(conversationId) { mutableStateOf(false) }
    var response by remember(conversationId) { mutableStateOf<MemoryActionItemsResponse?>(null) }
    var isLoading by remember(conversationId) { mutableStateOf(false) }
    var error by remember(conversationId) { mutableStateOf<String?>(null) }

    suspend fun refreshActionItems() {
        isLoading = true
        error = null
        runCatching {
            memoryActionItemService.getActionItems(conversationId, includeClosed)
        }.onSuccess { loaded ->
            response = loaded
            log.info {
                "Loaded memory action items for conversation=${conversationId.value} actionItems=${loaded.actionItems.size} revision=${loaded.revision.take(12)}"
            }
        }.onFailure { failure ->
            error = failure.message ?: failure::class.simpleName
            log.warn(failure) { "Failed to load memory action items for conversation=${conversationId.value}" }
        }
        isLoading = false
    }

    LaunchedEffect(isVisible, conversationId, includeClosed, refreshKey) {
        if (isVisible) {
            refreshActionItems()
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = if (slideFromRight) slideInHorizontally(initialOffsetX = { it }) else expandHorizontally(),
        exit = if (slideFromRight) slideOutHorizontally(targetOffsetX = { it }) else shrinkHorizontally(),
        modifier = modifier
    ) {
        Surface(
            modifier = if (fullScreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .width(430.dp)
                    .fillMaxHeight()
            },
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                MemoryActionItemsHeader(
                    response = response,
                    includeClosed = includeClosed,
                    isLoading = isLoading,
                    onIncludeClosedChange = { includeClosed = it },
                    onRefresh = { coroutineScope.launch { refreshActionItems() } },
                    onClose = onClose
                )

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    isLoading && response == null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    error != null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = error ?: "Failed to load memory action items",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    response?.actionItems?.isEmpty() != false -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (includeClosed) "No memory action items" else "No active memory action items",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(response!!.actionItems, key = { it.id.value }) { actionItem ->
                                MemoryActionItemCard(actionItem)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryActionItemsHeader(
    response: MemoryActionItemsResponse?,
    includeClosed: Boolean,
    isLoading: Boolean,
    onIncludeClosedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Memory Action Items",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                response?.let {
                    Text(
                        text = "${it.counts.activeCount()} active / ${it.actionItems.size} shown / ${it.revision.take(8)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onRefresh, enabled = !isLoading) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh memory action items")
            }

            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close memory action items")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Show closed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = includeClosed,
                onCheckedChange = onIncludeClosedChange
            )
            Spacer(modifier = Modifier.width(12.dp))
            response?.counts?.let { counts ->
                Text(
                    text = "open ${counts.open}, progress ${counts.inProgress}, blocked ${counts.blocked}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MemoryActionItemCard(actionItem: MemoryActionItem) {
    var expanded by remember(actionItem.id) { mutableStateOf(false) }
    val statusColor = actionItem.status.taskStatusColor()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                MemoryActionItemPill(
                    text = actionItem.status.name,
                    color = statusColor
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = actionItem.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${actionItem.priority.name} / ${formatRelativeTime(actionItem.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse actionItem" else "Expand actionItem"
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))

                actionItem.description?.takeIf { it.isNotBlank() }?.let {
                    MemoryActionItemField("Description", it)
                }

                MemoryActionItemField("Scope", actionItem.scope.displayText())

                actionItem.dueAt?.let {
                    MemoryActionItemField("Due", it.toString())
                }

                if (actionItem.acceptanceCriteria.isNotEmpty()) {
                    MemoryActionItemField("Acceptance", actionItem.acceptanceCriteria.joinToString("\n") { "- $it" })
                }

                if (actionItem.blockers.isNotEmpty()) {
                    MemoryActionItemField("Blockers", actionItem.blockers.joinToString("\n") { "- $it" })
                }

                MemoryActionItemField(
                    label = "Debug",
                    value = "id=${actionItem.id.value}\nconfidence=${"%.2f".format(actionItem.confidence)}\nevidence=${actionItem.evidenceRefs.size}"
                )
            }
        }
    }
}

@Composable
private fun MemoryActionItemPill(
    text: String,
    color: Color,
) {
    Surface(
        color = color.copy(alpha = 0.18f),
        contentColor = color,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun MemoryActionItemField(
    label: String,
    value: String,
) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = value,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun MemoryActionItem.Status.taskStatusColor(): Color =
    when (this) {
        MemoryActionItem.Status.BLOCKED -> MaterialTheme.colorScheme.error
        MemoryActionItem.Status.IN_PROGRESS -> MaterialTheme.colorScheme.primary
        MemoryActionItem.Status.OPEN -> MaterialTheme.colorScheme.tertiary
        MemoryActionItem.Status.DONE -> MaterialTheme.colorScheme.secondary
        MemoryActionItem.Status.CANCELLED -> MaterialTheme.colorScheme.outline
    }

private fun MemoryActionItemCounts.activeCount(): Int = open + inProgress + blocked

private fun MemoryScope.displayText(): String =
    when (this) {
        is MemoryScope.Global -> text
        is MemoryScope.Project -> text
        is MemoryScope.Conversation -> text
        is MemoryScope.Entity -> text
        is MemoryScope.Environment -> text
        is MemoryScope.Document -> text
    }
