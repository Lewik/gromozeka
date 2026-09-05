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
                    text = "Approve device connection",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Check the device details before granting access.",
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
    SettingsGroup(title = "Connect a device") {
        Text(
            text = "Enter the code shown on a new Client or Worker. Access is granted only after you review it here.",
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
    val scope = rememberCoroutineScope()
    var code by remember(initialCode) { mutableStateOf(initialCode) }
    var connection by remember(initialCode) { mutableStateOf<DeviceConnectionPreview?>(null) }
    var loading by remember(initialCode) { mutableStateOf(false) }
    var result by remember(initialCode) { mutableStateOf<ApprovalResult?>(null) }
    var error by remember(initialCode) { mutableStateOf<String?>(null) }

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
                error = failure.message ?: "Connection code was not found"
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
                "Device approved. It can connect now."
            } else {
                "Device connection denied."
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
            Text("Done")
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
            label = { Text("Connection code") },
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
            Text(it, color = MaterialTheme.colorScheme.error)
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
            Text("Review")
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
            Text("Platform: ${current.platform}")
            Text("Access: ${current.components.displayNames()}")
            current.workerId?.let { Text("Worker: $it") }
            if (current.workerBindsToUser) {
                Text("This Worker will report device context for your user, including location when enabled on the device.")
            }
            Text("Code: ${current.userCode}")
        }
    }
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
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
                        error = failure.message ?: "Device could not be approved"
                    } finally {
                        loading = false
                    }
                }
            },
        ) {
            Text("Approve")
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
                        error = failure.message ?: "Device could not be denied"
                    } finally {
                        loading = false
                    }
                }
            },
        ) {
            Text("Deny")
        }
    }
}

private fun Set<DeviceConnection.Component>.displayNames(): String =
    sortedBy(DeviceConnection.Component::name).joinToString(" + ") {
        when (it) {
            DeviceConnection.Component.CLIENT -> "Client"
            DeviceConnection.Component.WORKER -> "Worker"
        }
    }

private enum class ApprovalResult {
    APPROVED,
    DENIED,
}
