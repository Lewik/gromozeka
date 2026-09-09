package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.client.RemoteDeviceConnectionClient
import com.gromozeka.domain.model.DeviceConnection
import com.gromozeka.remote.protocol.DeviceConnectionPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DeviceConnectionApprovalScreen(
    initialCode: String,
    preview: suspend (String) -> DeviceConnectionPreview,
    approve: suspend (String) -> DeviceConnectionPreview,
    deny: suspend (String) -> Unit,
    onDone: () -> Unit,
) {
    val translation = LocalTranslation.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(),
            ) {
                Text(
                    text = translation.text("security.deviceConnection.title"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = translation.text("security.deviceConnection.description"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                DeviceConnectionApprovalContent(
                    initialCode = initialCode,
                    preview = preview,
                    approve = approve,
                    deny = deny,
                    onDone = onDone,
                )
            }
        }
    }
}

@Composable
fun DeviceConnectionApprovalSettings(
    service: RemoteDeviceConnectionClient,
) {
    val translation = LocalTranslation.current
    SettingsGroup(title = translation.text("security.deviceConnection.settingsTitle")) {
        Text(
            text = translation.text("security.deviceConnection.settingsDescription"),
            style = MaterialTheme.typography.bodyMedium,
        )
        DeviceConnectionApprovalContent(
            initialCode = "",
            preview = service::preview,
            approve = service::approve,
            deny = service::deny,
        )
    }
}

@Composable
private fun DeviceConnectionApprovalContent(
    initialCode: String,
    preview: suspend (String) -> DeviceConnectionPreview,
    approve: suspend (String) -> DeviceConnectionPreview,
    deny: suspend (String) -> Unit,
    onDone: (() -> Unit)? = null,
) {
    val translation = LocalTranslation.current
    val scope = rememberCoroutineScope()
    var code by remember(initialCode) { mutableStateOf(initialCode) }
    var connection by remember(initialCode) { mutableStateOf<DeviceConnectionPreview?>(null) }
    var loading by remember(initialCode) { mutableStateOf(false) }
    var result by remember(initialCode) { mutableStateOf<ApprovalResult?>(null) }
    var error by remember(initialCode) { mutableStateOf<DeviceApprovalError?>(null) }

    fun review() {
        if (code.isBlank() || loading) return
        scope.launch {
            loading = true
            error = null
            result = null
            try {
                connection = preview(code)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                connection = null
                error = DeviceApprovalError(failure, "security.deviceConnection.codeNotFound")
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(initialCode) {
        if (initialCode.isNotBlank()) review()
    }

    if (result != null) {
        Text(
            text = if (result == ApprovalResult.APPROVED) {
                translation.text("security.deviceConnection.approved")
            } else {
                translation.text("security.deviceConnection.denied")
            },
            color = if (result == ApprovalResult.APPROVED) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (onDone != null) {
                    onDone()
                } else {
                    code = ""
                    connection = null
                    result = null
                }
            }
        ) {
            Text(translation.text("security.deviceConnection.done"))
        }
        return
    }

    if (connection == null) {
        OutlinedTextField(
            value = code,
            onValueChange = { value ->
                code = value.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(9)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(translation.text("security.deviceConnection.codeLabel")) },
            placeholder = { Text("ABCD-EFGH") },
            singleLine = true,
            enabled = !loading,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
            ),
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.failure.authenticationErrorText(translation, it.fallbackMessageId), color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = ::review,
            enabled = code.isNotBlank() && !loading,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(translation.text("security.deviceConnection.review"))
        }
        return
    }

    val current = requireNotNull(connection)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(current.deviceLabel, style = MaterialTheme.typography.titleMedium)
            Text(translation.text("security.deviceConnection.platform", "platform" to current.platform))
            Text(translation.text("security.deviceConnection.access", "components" to current.components.displayNames(translation)))
            current.workerId?.let { Text(translation.text("security.deviceConnection.worker", "workerId" to it)) }
            if (current.workerBindsToUser) {
                Text(translation.text("security.deviceConnection.contextAccess"))
            }
            Text(translation.text("security.deviceConnection.code", "code" to current.userCode))
        }
    }
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it.failure.authenticationErrorText(translation, it.fallbackMessageId), color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            enabled = !loading,
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    try {
                        approve(current.userCode)
                        result = ApprovalResult.APPROVED
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        error = DeviceApprovalError(failure, "security.deviceConnection.approvalFailed")
                    } finally {
                        loading = false
                    }
                }
            },
        ) {
            Text(translation.text("security.deviceConnection.approve"))
        }
        OutlinedButton(
            enabled = !loading,
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    try {
                        deny(current.userCode)
                        result = ApprovalResult.DENIED
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        error = DeviceApprovalError(failure, "security.deviceConnection.denialFailed")
                    } finally {
                        loading = false
                    }
                }
            },
        ) {
            Text(translation.text("security.deviceConnection.deny"))
        }
    }
}

private data class DeviceApprovalError(val failure: Throwable, val fallbackMessageId: String)

private fun Set<DeviceConnection.Component>.displayNames(translation: Translation): String =
    when (this) {
        setOf(DeviceConnection.Component.CLIENT, DeviceConnection.Component.WORKER) ->
            translation.text("security.deviceConnection.component.clientAndWorker")
        setOf(DeviceConnection.Component.CLIENT) ->
            translation.text("security.deviceConnection.component.client")
        setOf(DeviceConnection.Component.WORKER) ->
            translation.text("security.deviceConnection.component.worker")
        else -> ""
    }

private enum class ApprovalResult {
    APPROVED,
    DENIED,
}
