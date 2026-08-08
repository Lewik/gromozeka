package com.gromozeka.presentation.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.gromozeka.remote.protocol.DeviceConnectionChallenge
import com.gromozeka.remote.protocol.DeviceConnectionConsumeResponse
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock

data class RemoteAuthenticationInput(
    val username: String,
    val password: String,
    val displayName: String = "",
    val bootstrapToken: String = "",
)

@Composable
fun RemoteAuthenticationScreen(
    initialized: Boolean,
    submitting: Boolean,
    error: String?,
    onSubmit: (RemoteAuthenticationInput, deviceToken: String?) -> Unit,
    onStartDeviceConnection: suspend () -> DeviceConnectionChallenge,
    onConsumeDeviceConnection: suspend (String) -> DeviceConnectionConsumeResponse,
    deviceConnectionVerificationUrl: (DeviceConnectionChallenge) -> String,
    onDeviceConnected: (DeviceConnectionConsumeResponse) -> Unit,
    preferPassword: Boolean = false,
) {
    var usePassword by remember(initialized, preferPassword) {
        mutableStateOf(!initialized || preferPassword)
    }
    var challenge by remember(initialized) { mutableStateOf<DeviceConnectionChallenge?>(null) }
    var connectionMessage by remember(initialized) { mutableStateOf<String?>(null) }
    var connectionStarting by remember(initialized) { mutableStateOf(false) }
    var restartKey by remember(initialized) { mutableIntStateOf(0) }
    val currentOnDeviceConnected by rememberUpdatedState(onDeviceConnected)

    LaunchedEffect(initialized, restartKey) {
        if (!initialized) return@LaunchedEffect
        connectionStarting = true
        connectionMessage = null
        challenge = null
        try {
            challenge = onStartDeviceConnection()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            connectionMessage = error.message ?: "Could not start device connection"
        } finally {
            connectionStarting = false
        }
    }

    LaunchedEffect(challenge, usePassword) {
        val activeChallenge = challenge ?: return@LaunchedEffect
        if (usePassword) return@LaunchedEffect
        while (Clock.System.now() < activeChallenge.expiresAt) {
            delay(activeChallenge.pollIntervalSeconds * 1_000L)
            val response = try {
                onConsumeDeviceConnection(activeChallenge.deviceToken)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                connectionMessage = "Connection interrupted. Retrying..."
                continue
            }
            when (response.status) {
                DeviceConnectionConsumeResponse.Status.PENDING -> connectionMessage = null
                DeviceConnectionConsumeResponse.Status.CONNECTED -> {
                    connectionMessage = "Connected"
                    currentOnDeviceConnected(response)
                    return@LaunchedEffect
                }
                DeviceConnectionConsumeResponse.Status.DENIED -> {
                    connectionMessage = response.message ?: "Connection was denied"
                    return@LaunchedEffect
                }
                DeviceConnectionConsumeResponse.Status.EXPIRED -> {
                    connectionMessage = response.message ?: "Connection code expired"
                    return@LaunchedEffect
                }
            }
        }
        connectionMessage = "Connection code expired"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
                    text = when {
                        !initialized -> "Create the first owner"
                        usePassword -> "Sign in to Gromozeka"
                        else -> "Connect this device"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        !initialized -> "The bootstrap token is printed once in the Server log."
                        usePassword -> "Use the account stored on this Gromozeka Server."
                        else -> "Approve this connection from a device where you are already signed in."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))

                if (initialized && !usePassword) {
                    DeviceConnectionChallengeContent(
                        challenge = challenge,
                        starting = connectionStarting,
                        message = connectionMessage,
                        verificationUrl = challenge?.let(deviceConnectionVerificationUrl),
                        onRetry = { restartKey++ },
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = { usePassword = true },
                        enabled = !submitting,
                    ) {
                        Text("Use username and password instead")
                    }
                } else {
                    PasswordAuthenticationContent(
                        initialized = initialized,
                        submitting = submitting,
                        error = error,
                        deviceToken = challenge?.deviceToken,
                        onSubmit = onSubmit,
                    )
                    if (initialized) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { usePassword = false },
                            enabled = !submitting && challenge != null,
                        ) {
                            Text("Use connection code")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceConnectionChallengeContent(
    challenge: DeviceConnectionChallenge?,
    starting: Boolean,
    message: String?,
    verificationUrl: String?,
    onRetry: () -> Unit,
) {
    if (starting) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Text("Creating a secure connection code...")
        }
        return
    }
    if (challenge == null || verificationUrl == null) {
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
        }
        OutlinedButton(onClick = onRetry) {
            Text("Try again")
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(208.dp),
            color = Color.White,
            shape = MaterialTheme.shapes.medium,
        ) {
            Image(
                painter = rememberQrCodePainter(verificationUrl),
                contentDescription = "Connection QR code",
                modifier = Modifier.padding(14.dp),
            )
        }
        Text(
            text = challenge.userCode,
            style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 0.12.em),
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Scan the QR code or enter this code in Settings > Security on an authorized device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (message == "Connected") {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Composable
private fun PasswordAuthenticationContent(
    initialized: Boolean,
    submitting: Boolean,
    error: String?,
    deviceToken: String?,
    onSubmit: (RemoteAuthenticationInput, deviceToken: String?) -> Unit,
) {
    var username by remember(initialized) { mutableStateOf("") }
    var displayName by remember(initialized) { mutableStateOf("") }
    val bootstrapTokenState = remember(initialized) { TextFieldState() }
    val passwordState = remember(initialized) { TextFieldState() }
    val passwordConfirmationState = remember(initialized) { TextFieldState() }
    val bootstrapToken = bootstrapTokenState.text.toString()
    val password = passwordState.text.toString()
    val passwordConfirmation = passwordConfirmationState.text.toString()
    val passwordMismatch = !initialized &&
        passwordConfirmation.isNotEmpty() &&
        password != passwordConfirmation
    val canSubmit = !submitting &&
        username.isNotBlank() &&
        password.length >= 12 &&
        (initialized || (
            bootstrapToken.isNotBlank() &&
                password == passwordConfirmation
            ))
    val submit = {
        if (canSubmit) {
            onSubmit(
                RemoteAuthenticationInput(
                    username = username,
                    password = password,
                    displayName = displayName,
                    bootstrapToken = bootstrapToken,
                ),
                deviceToken,
            )
        }
    }

    if (!initialized) {
        OutlinedSecretTextField(
            state = bootstrapTokenState,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Bootstrap token") },
            enabled = !submitting,
        )
        Spacer(Modifier.height(12.dp))
    }
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Username") },
        singleLine = true,
        enabled = !submitting,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
        ),
    )
    if (!initialized) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Display name") },
            singleLine = true,
            enabled = !submitting,
        )
    }
    Spacer(Modifier.height(12.dp))
    OutlinedSecretTextField(
        state = passwordState,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Password") },
        enabled = !submitting,
        imeAction = if (initialized) ImeAction.Done else ImeAction.Next,
        onKeyboardAction = if (initialized) submit else null,
    )
    if (!initialized) {
        Spacer(Modifier.height(12.dp))
        OutlinedSecretTextField(
            state = passwordConfirmationState,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Confirm password") },
            enabled = !submitting,
            isError = passwordMismatch,
            supportingText = if (passwordMismatch) {
                { Text("Passwords do not match") }
            } else {
                null
            },
            imeAction = ImeAction.Done,
            onKeyboardAction = submit,
        )
    }
    if (error != null) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(20.dp))
    Button(
        enabled = canSubmit,
        onClick = submit,
    ) {
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Text(if (initialized) "Sign in" else "Create owner")
        }
    }
}
