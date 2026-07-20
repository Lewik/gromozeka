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
    val translation = LocalTranslation.current.runtime
    val statusColor = when (state.status) {
        RemoteConnectionState.Status.CONNECTED -> MaterialTheme.colorScheme.primary
        RemoteConnectionState.Status.CONNECTING,
        RemoteConnectionState.Status.RECONNECTING -> MaterialTheme.colorScheme.tertiary
        RemoteConnectionState.Status.OFFLINE -> MaterialTheme.colorScheme.error
        RemoteConnectionState.Status.DISCONNECTED,
        RemoteConnectionState.Status.CLOSED -> MaterialTheme.colorScheme.outline
    }
    val label = when (state.status) {
        RemoteConnectionState.Status.DISCONNECTED -> translation.disconnectedStatus
        RemoteConnectionState.Status.CONNECTING -> translation.connectingStatus
        RemoteConnectionState.Status.CONNECTED -> translation.connectedStatus
        RemoteConnectionState.Status.RECONNECTING ->
            translation.reconnectingStatus +
                state.reconnectAttempt.takeIf { it > 0 }?.let { " ($it)" }.orEmpty()
        RemoteConnectionState.Status.OFFLINE -> translation.offlineStatus
        RemoteConnectionState.Status.CLOSED -> translation.closedStatus
    }
    val details = state.lastError
        ?.takeIf { state.status == RemoteConnectionState.Status.OFFLINE && it.isNotBlank() }
        ?.let { " · $it" }
        .orEmpty()

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
            text = label + details,
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
