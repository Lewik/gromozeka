package com.gromozeka.presentation.ui.notifications

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.UserNotificationService
import com.gromozeka.presentation.ui.*
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import kotlinx.coroutines.launch

@Composable
fun UserNotificationsDialog(
    state: UserNotificationsState,
    service: UserNotificationService,
    onOpenConversation: suspend (Conversation.Id) -> Unit,
) {
    if (!state.visible) return
    val text = LocalTranslation.current
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = state::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth(0.95f).fillMaxHeight(0.85f).testTag("user-notifications"),
            shape = MaterialTheme.shapes.large, tonalElevation = 8.dp) {
            Column(Modifier.fillMaxSize().padding(GromozekaTheme.spacing.contentPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(text.text("notifications.title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = state::close, modifier = Modifier.testTag("notifications-close")) {
                        Icon(Icons.Default.Close, text.text("notifications.close"))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.unreadOnly, onCheckedChange = { scope.launch { state.filter(service, it) } }, enabled = !state.busy)
                    Text(text.text("notifications.unreadOnly"), Modifier.weight(1f))
                    CompactButton(onClick = { scope.launch { state.refresh(service) } }, enabled = !state.busy) {
                        Icon(Icons.Default.Refresh, text.text("notifications.refresh"))
                    }
                }
                CompactButton(onClick = { scope.launch { state.markAllRead(service) } }, enabled = !state.busy && (state.summary?.unreadCount ?: 0) > 0) {
                    Text(text.text("notifications.markAllRead"))
                }
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val problem = state.problem
                if (problem != null || state.summaryFailed) {
                    val key = when(problem) {
                        UserNotificationsState.Problem.ACTION -> "notifications.actionFailed"
                        UserNotificationsState.Problem.CONVERSATION -> "notifications.conversationUnavailable"
                        else -> "notifications.loadFailed"
                    }
                    Text(text.text(key), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("notifications-error"))
                }
                if (!state.loading && problem == null && state.items.isEmpty()) Text(text.text("notifications.empty"))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.items, key = { it.id.value }) { item ->
                        Surface(Modifier.fillMaxWidth().testTag("notification-${item.id.value}"),
                            color = if(item.readAt == null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val icon = when(item.level) {
                                        UserNotification.Level.INFO -> Icons.Default.Info
                                        UserNotification.Level.WARNING -> Icons.Default.Warning
                                        UserNotification.Level.ERROR -> Icons.Default.Error
                                    }
                                    Icon(icon, text.text("notifications.level.${item.level.name.lowercase()}"))
                                    Spacer(Modifier.width(8.dp))
                                    Text(item.title, fontWeight = if(item.readAt == null) FontWeight.Bold else FontWeight.Normal,
                                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                }
                                SelectionContainer { Text(item.message) }
                                Text(item.createdAt.toString(), style = MaterialTheme.typography.labelSmall)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { scope.launch { state.setRead(service, item, item.readAt == null) } }, enabled = !state.busy) {
                                        Text(text.text(if(item.readAt == null) "notifications.markRead" else "notifications.markUnread"))
                                    }
                                    Spacer(Modifier.weight(1f))
                                    if(item.conversationId != null) IconButton(
                                        onClick = { scope.launch { state.follow(service, item, onOpenConversation) } }, enabled = !state.busy,
                                    ) { Icon(Icons.Default.OpenInNew, text.text("notifications.openConversation")) }
                                    IconButton(onClick = { scope.launch { state.delete(service, item.id) } }, enabled = !state.busy) {
                                        Icon(Icons.Default.Delete, text.text("notifications.delete"))
                                    }
                                }
                            }
                        }
                    }
                    if(state.nextCursor != null) item {
                        CompactButton(onClick = { scope.launch { state.loadMore(service) } }, enabled = !state.loading && !state.busy) {
                            Text(text.text("notifications.loadMore"))
                        }
                    }
                }
            }
        }
    }
}
