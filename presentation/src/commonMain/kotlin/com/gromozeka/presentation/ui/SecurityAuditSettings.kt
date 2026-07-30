package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.client.RemoteSecurityAuditService
import com.gromozeka.client.RemoteUserDirectoryService
import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Composable
fun SecurityAuditSettings(
    service: RemoteSecurityAuditService,
    userDirectoryService: RemoteUserDirectoryService,
    coroutineScope: CoroutineScope,
) {
    var events by remember { mutableStateOf<List<SecurityAuditEvent>>(emptyList()) }
    var users by remember { mutableStateOf<Map<User.Id, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        coroutineScope.launch {
            loading = true
            error = null
            runCatching {
                val eventsRequest = async { service.listRecent() }
                val usersRequest = async {
                    runCatching { userDirectoryService.list() }.getOrDefault(emptyList())
                }
                eventsRequest.await() to usersRequest.await()
            }.onSuccess { (loadedEvents, loadedUsers) ->
                events = loadedEvents
                users = loadedUsers.associate { it.id to it.displayName }
            }.onFailure {
                error = it.message ?: it.toString()
            }
            loading = false
        }
    }

    LaunchedEffect(service, userDirectoryService) {
        reload()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Security audit",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Recent successful identity and access changes. Credentials and conversation content are never recorded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(
            enabled = !loading,
            onClick = ::reload,
        ) {
            Text("Refresh")
        }

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (loading) {
            CircularProgressIndicator()
        } else if (events.isEmpty()) {
            Text(
                text = "No security changes recorded yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            events.forEach { event ->
                SecurityAuditEventCard(
                    event = event,
                    actorName = users[event.actorUserId],
                )
            }
        }
    }
}

@Composable
private fun SecurityAuditEventCard(
    event: SecurityAuditEvent,
    actorName: String?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = event.action.displayName(),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = event.occurredAt.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "By ${actorName ?: event.actorUserId.value}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${event.targetType.displayName()} · ${event.targetId}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            event.projectId?.let { projectId ->
                Text(
                    text = "Project · ${projectId.value}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (event.attributes.isNotEmpty()) {
                Text(
                    text = event.attributes.entries.joinToString(" · ") { (key, value) -> "$key=$value" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun SecurityAuditEvent.Action.displayName(): String =
    name.lowercase()
        .replace('_', ' ')
        .replaceFirstChar(Char::uppercase)

private fun SecurityAuditEvent.TargetType.displayName(): String =
    name.lowercase().replaceFirstChar(Char::uppercase)
