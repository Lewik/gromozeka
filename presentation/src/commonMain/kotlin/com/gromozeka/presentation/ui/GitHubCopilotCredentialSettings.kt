package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiUserCredentialStatus
import com.gromozeka.domain.service.CurrentUserAiCredentialService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun GitHubCopilotCredentialSettings(
    connections: List<AiConnection.GitHubCopilot>,
    service: CurrentUserAiCredentialService,
    coroutineScope: CoroutineScope,
) {
    val translation = LocalTranslation.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            translation.text("security.copilot.description"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (connections.isEmpty()) {
            Text(translation.text("security.copilot.empty"))
        }
        connections.forEach { connection ->
            GitHubCopilotCredentialCard(connection, service, coroutineScope)
        }
    }
}

@Composable
private fun GitHubCopilotCredentialCard(
    connection: AiConnection.GitHubCopilot,
    service: CurrentUserAiCredentialService,
    coroutineScope: CoroutineScope,
) {
    val translation = LocalTranslation.current
    var status by remember(connection.id) { mutableStateOf<AiUserCredentialStatus?>(null) }
    var busy by remember(connection.id) { mutableStateOf(false) }
    var error by remember(connection.id) { mutableStateOf<String?>(null) }
    val secretState = remember(connection.id) { TextFieldState() }

    LaunchedEffect(connection.id, connection.authMode) {
        if (connection.authMode == AiConnection.GitHubCopilotAuthMode.PER_USER_TOKEN) {
            busy = true
            runCatching { service.status(connection.id) }
                .onSuccess { status = it }
                .onFailure { error = it.message ?: it::class.simpleName }
            busy = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(connection.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                connection.id.value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (connection.authMode) {
                AiConnection.GitHubCopilotAuthMode.SERVER_CLI -> Text(
                    when (val target = connection.executionTarget) {
                        AiExecutionTarget.Server ->
                            translation.text("security.copilot.serverCli")
                        is AiExecutionTarget.Worker ->
                            translation.text("security.copilot.workerCli", "workerId" to target.workerId)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AiConnection.GitHubCopilotAuthMode.PER_USER_TOKEN -> {
                    Text(
                        when {
                            busy && status == null -> translation.text("security.copilot.checking")
                            status?.configured == true -> translation.text("security.copilot.configured")
                            else -> translation.text("security.copilot.notConfigured")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    status?.updatedAt?.let { updatedAt ->
                        Text(
                            translation.text("security.copilot.updatedAt", "updatedAt" to updatedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedSecretTextField(
                        state = secretState,
                        label = { Text(translation.text("security.copilot.tokenLabel")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        translation.text("security.copilot.tokenTypes"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    busy = true
                                    error = null
                                    runCatching {
                                        service.configure(connection.id, secretState.text.toString())
                                    }.onSuccess {
                                        status = it
                                        secretState.clearText()
                                    }.onFailure {
                                        error = it.message ?: it::class.simpleName
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy && secretState.text.isNotBlank(),
                        ) {
                            Text(if (status?.configured == true) translation.text("security.copilot.replace") else translation.text("security.copilot.configure"))
                        }
                        if (status?.configured == true) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        busy = true
                                        error = null
                                        runCatching { service.remove(connection.id) }
                                            .onSuccess { status = it }
                                            .onFailure { error = it.message ?: it::class.simpleName }
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                            ) {
                                Text(translation.text("security.copilot.remove"))
                            }
                        }
                        if (busy) {
                            Spacer(Modifier.width(10.dp))
                            CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
