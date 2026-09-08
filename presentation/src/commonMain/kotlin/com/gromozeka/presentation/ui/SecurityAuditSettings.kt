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
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.client.RemoteSecurityAuditService
import com.gromozeka.client.RemoteUserDirectoryService
import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch

@Composable
fun SecurityAuditSettings(
    service: RemoteSecurityAuditService,
    userDirectoryService: RemoteUserDirectoryService,
    coroutineScope: CoroutineScope,
) {
    val translation = LocalTranslation.current
    var events by remember { mutableStateOf<List<SecurityAuditEvent>>(emptyList()) }
    var users by remember { mutableStateOf<Map<User.Id, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        coroutineScope.launch {
            loading = true
            error = null
            runCatching { service.listRecent() }.onSuccess { loadedEvents ->
                events = loadedEvents
            }.onFailure {
                error = it.message ?: it.toString()
            }
            loading = false
        }
    }

    LaunchedEffect(service) {
        reload()
    }

    LaunchedEffect(userDirectoryService) {
        userDirectoryService.observe()
            .catch { failure -> error = failure.message ?: failure.toString() }
            .collect { loadedUsers ->
                users = loadedUsers.associate { it.id to it.displayName }
            }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = translation.text("security.audit.title"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = translation.text("security.audit.description"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(
            enabled = !loading,
            onClick = ::reload,
        ) {
            Text(translation.text("security.audit.refresh"))
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
                text = translation.text("security.audit.empty"),
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
    val translation = LocalTranslation.current
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
                    text = event.action.displayName(translation),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = event.occurredAt.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = translation.text("security.audit.actor", "actor" to (actorName ?: event.actorUserId.value)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = translation.text(
                    "security.audit.target",
                    "targetType" to event.targetType.displayName(translation),
                    "targetId" to event.targetId,
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            event.projectId?.let { projectId ->
                Text(
                    text = translation.text("security.audit.project", "projectId" to projectId.value),
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

private fun SecurityAuditEvent.Action.displayName(translation: Translation): String =
    translation.text(
        when (this) {
            SecurityAuditEvent.Action.AI_USER_CREDENTIAL_CONFIGURED -> "security.audit.action.aiUserCredentialConfigured"
            SecurityAuditEvent.Action.AI_USER_CREDENTIAL_REMOVED -> "security.audit.action.aiUserCredentialRemoved"
            SecurityAuditEvent.Action.DEVICE_CONNECTED -> "security.audit.action.deviceConnected"
            SecurityAuditEvent.Action.DEVICE_CONNECTION_APPROVED -> "security.audit.action.deviceConnectionApproved"
            SecurityAuditEvent.Action.DEVICE_CONNECTION_DENIED -> "security.audit.action.deviceConnectionDenied"
            SecurityAuditEvent.Action.NAMED_SECRET_DELETED -> "security.audit.action.namedSecretDeleted"
            SecurityAuditEvent.Action.NAMED_SECRET_SAVED -> "security.audit.action.namedSecretSaved"
            SecurityAuditEvent.Action.PERSONAL_ACCESS_TOKEN_ISSUED -> "security.audit.action.personalAccessTokenIssued"
            SecurityAuditEvent.Action.PERSONAL_ACCESS_TOKEN_REVOKED -> "security.audit.action.personalAccessTokenRevoked"
            SecurityAuditEvent.Action.PROJECT_CREATED -> "security.audit.action.projectCreated"
            SecurityAuditEvent.Action.PROJECT_DELETED -> "security.audit.action.projectDeleted"
            SecurityAuditEvent.Action.PROJECT_MEMBERSHIP_REMOVED -> "security.audit.action.projectMembershipRemoved"
            SecurityAuditEvent.Action.PROJECT_MEMBERSHIP_SET -> "security.audit.action.projectMembershipSet"
            SecurityAuditEvent.Action.RUNTIME_BOOTSTRAPPED -> "security.audit.action.runtimeBootstrapped"
            SecurityAuditEvent.Action.USER_CREATED -> "security.audit.action.userCreated"
            SecurityAuditEvent.Action.USER_PASSWORD_RESET -> "security.audit.action.userPasswordReset"
            SecurityAuditEvent.Action.USER_UPDATED -> "security.audit.action.userUpdated"
            SecurityAuditEvent.Action.WORKER_ENROLLED -> "security.audit.action.workerEnrolled"
            SecurityAuditEvent.Action.WORKER_ENROLLMENT_CREATED -> "security.audit.action.workerEnrollmentCreated"
            SecurityAuditEvent.Action.WORKER_PROJECT_GRANT_REMOVED -> "security.audit.action.workerProjectGrantRemoved"
            SecurityAuditEvent.Action.WORKER_PROJECT_GRANT_SET -> "security.audit.action.workerProjectGrantSet"
            SecurityAuditEvent.Action.WORKER_REVOKED -> "security.audit.action.workerRevoked"
            SecurityAuditEvent.Action.WORKER_RUNTIME_ACCESS_UPDATED -> "security.audit.action.workerRuntimeAccessUpdated"
            SecurityAuditEvent.Action.WORKER_USER_GRANT_REMOVED -> "security.audit.action.workerUserGrantRemoved"
            SecurityAuditEvent.Action.WORKER_USER_GRANT_SET -> "security.audit.action.workerUserGrantSet"
        }
    )

private fun SecurityAuditEvent.TargetType.displayName(translation: Translation): String =
    translation.text(
        when (this) {
            SecurityAuditEvent.TargetType.AI_CONNECTION -> "security.audit.targetType.aiConnection"
            SecurityAuditEvent.TargetType.DEVICE_CONNECTION -> "security.audit.targetType.deviceConnection"
            SecurityAuditEvent.TargetType.NAMED_SECRET -> "security.audit.targetType.namedSecret"
            SecurityAuditEvent.TargetType.PERSONAL_ACCESS_TOKEN -> "security.audit.targetType.personalAccessToken"
            SecurityAuditEvent.TargetType.PROJECT -> "security.audit.targetType.project"
            SecurityAuditEvent.TargetType.RUNTIME -> "security.audit.targetType.runtime"
            SecurityAuditEvent.TargetType.USER -> "security.audit.targetType.user"
            SecurityAuditEvent.TargetType.WORKER -> "security.audit.targetType.worker"
        }
    )
