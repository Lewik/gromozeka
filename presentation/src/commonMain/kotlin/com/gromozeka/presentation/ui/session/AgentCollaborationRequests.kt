package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.AgentRequest
import com.gromozeka.domain.model.Conversation
import com.gromozeka.presentation.ui.LocalTranslation

@Composable
internal fun AgentCollaborationRequests(requests: List<AgentRequest>, conversationId: Conversation.Id) {
    if (requests.isEmpty()) return
    val translation = LocalTranslation.current
    Column(Modifier.fillMaxWidth().testTag("runtime-collaboration"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(translation.text("collaboration.title"), style = MaterialTheme.typography.labelLarge)
        requests.sortedBy { !it.isOpen }.take(16).forEach { request ->
            val incoming = request.target.conversationId == conversationId
            val peer = if (incoming) request.source else request.target
            val stateKey = when (request.state) {
                AgentRequest.State.WORKING -> "collaboration.working"
                AgentRequest.State.WAITING_USER -> "collaboration.waitingUser"
                AgentRequest.State.WAITING_RESULT -> "collaboration.waitingResult"
                AgentRequest.State.COMPLETED -> "collaboration.completed"
                AgentRequest.State.CANCELLED -> "collaboration.cancelled"
                AgentRequest.State.BLOCKED -> "collaboration.blocked"
            }
            Text("${if (incoming) "←" else "→"} ${peer.agentId.value} · ${translation.text(stateKey)}",
                style = MaterialTheme.typography.labelMedium,
                color = if (request.state == AgentRequest.State.BLOCKED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(request.text.take(240), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            request.result?.let { Text(it.take(1200), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
