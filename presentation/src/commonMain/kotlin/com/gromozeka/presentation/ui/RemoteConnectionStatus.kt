package com.gromozeka.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.client.RemoteConnectionState

@Composable
fun RemoteConnectionStatus(
    state: RemoteConnectionState,
    modifier: Modifier = Modifier,
) {
    val translation = LocalTranslation.current
    val statusColor = when (state.status) {
        RemoteConnectionState.Status.CONNECTED -> MaterialTheme.colorScheme.primary
        RemoteConnectionState.Status.CONNECTING,
        RemoteConnectionState.Status.RECONNECTING -> MaterialTheme.colorScheme.tertiary
        RemoteConnectionState.Status.OFFLINE -> MaterialTheme.colorScheme.error
        RemoteConnectionState.Status.DISCONNECTED,
        RemoteConnectionState.Status.CLOSED -> MaterialTheme.colorScheme.outline
    }
    val label = when (state.status) {
        RemoteConnectionState.Status.DISCONNECTED -> translation.runtime.disconnectedStatus
        RemoteConnectionState.Status.CONNECTING -> translation.runtime.connectingStatus
        RemoteConnectionState.Status.CONNECTED -> translation.runtime.connectedStatus
        RemoteConnectionState.Status.RECONNECTING ->
            if (state.reconnectAttempt > 0) {
                translation.text("connection.status.reconnectingAttempt", "attempt" to state.reconnectAttempt)
            } else {
                translation.runtime.reconnectingStatus
            }
        RemoteConnectionState.Status.OFFLINE -> state.lastError?.takeIf(String::isNotBlank)?.let {
            translation.text("connection.status.offlineError", "error" to it)
        } ?: translation.runtime.offlineStatus
        RemoteConnectionState.Status.CLOSED -> translation.runtime.closedStatus
    }

    Row(
        modifier = modifier
            .testTag(UiTestTag.ConnectionStatus.value),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(statusColor),
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 7.dp),
            color = if (state.status == RemoteConnectionState.Status.OFFLINE) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
