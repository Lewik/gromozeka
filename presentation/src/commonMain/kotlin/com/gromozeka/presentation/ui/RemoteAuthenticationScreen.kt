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
import com.gromozeka.client.RemoteAuthenticationException
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.remote.protocol.AuthenticationErrorCode
import com.gromozeka.remote.protocol.DeviceConnectionChallenge
import com.gromozeka.remote.protocol.DeviceConnectionConsumeResponse
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Clock

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
    error: Throwable?,
    onSubmit: (RemoteAuthenticationInput, deviceToken: String?) -> Unit,
    onStartDeviceConnection: suspend () -> DeviceConnectionChallenge,
    onConsumeDeviceConnection: suspend (String) -> DeviceConnectionConsumeResponse,
    deviceConnectionVerificationUrl: (DeviceConnectionChallenge) -> String,
    onDeviceConnected: (DeviceConnectionConsumeResponse) -> Unit,
    preferPassword: Boolean = false,
) {
    val translation = LocalTranslation.current
    var usePassword by remember(initialized, preferPassword) {
        mutableStateOf(!initialized || preferPassword)
    }
    var challenge by remember(initialized) { mutableStateOf<DeviceConnectionChallenge?>(null) }
    var connectionMessage by remember(initialized) { mutableStateOf<AuthenticationConnectionMessage?>(null) }
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
            connectionMessage = AuthenticationConnectionMessage(
                AuthenticationConnectionStatus.START_FAILED,
                error,
            )
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
            } catch (error: RemoteAuthenticationException) {
                connectionMessage = AuthenticationConnectionMessage(AuthenticationConnectionStatus.RETRYING, error)
                when (error.code) {
                    AuthenticationErrorCode.REQUEST_FAILED,
                    AuthenticationErrorCode.RATE_LIMITED,
                    AuthenticationErrorCode.DEVICE_CONNECTION_RATE_LIMITED -> continue
                    else -> return@LaunchedEffect
                }
            } catch (_: Throwable) {
                connectionMessage = AuthenticationConnectionMessage(AuthenticationConnectionStatus.RETRYING)
                continue
            }
            when (response.status) {
                DeviceConnectionConsumeResponse.Status.PENDING -> connectionMessage = null
                DeviceConnectionConsumeResponse.Status.CONNECTED -> {
                    connectionMessage = AuthenticationConnectionMessage(AuthenticationConnectionStatus.CONNECTED)
                    currentOnDeviceConnected(response)
                    return@LaunchedEffect
                }
                DeviceConnectionConsumeResponse.Status.DENIED -> {
                    connectionMessage = AuthenticationConnectionMessage(AuthenticationConnectionStatus.DENIED)
                    return@LaunchedEffect
                }
                DeviceConnectionConsumeResponse.Status.EXPIRED -> {
                    connectionMessage = AuthenticationConnectionMessage(AuthenticationConnectionStatus.EXPIRED)
                    return@LaunchedEffect
                }
            }
        }
        connectionMessage = AuthenticationConnectionMessage(AuthenticationConnectionStatus.EXPIRED)
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
                        !initialized -> translation.text("auth.bootstrap.title")
                        usePassword -> translation.text("auth.signIn.title")
                        else -> translation.text("auth.device.title")
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        !initialized -> translation.text("auth.bootstrap.description")
                        usePassword -> translation.text("auth.signIn.description")
                        else -> translation.text("auth.device.description")
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
                        Text(translation.text("auth.device.usePassword"))
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
                            Text(translation.text("auth.device.useConnectionCode"))
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
    message: AuthenticationConnectionMessage?,
    verificationUrl: String?,
    onRetry: () -> Unit,
) {
    val translation = LocalTranslation.current
    if (starting) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Text(translation.text("auth.device.creatingCode"))
        }
        return
    }
    if (challenge == null || verificationUrl == null) {
        message?.let {
            Text(
                text = it.failure?.authenticationErrorText(translation, it.status.messageId)
                    ?: translation.text(it.status.messageId),
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(12.dp))
        }
        OutlinedButton(onClick = onRetry) {
            Text(translation.text("auth.device.retry"))
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
                contentDescription = translation.text("auth.device.qrCodeDescription"),
                modifier = Modifier.padding(14.dp),
            )
        }
        Text(
            text = challenge.userCode,
            style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 0.12.em),
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = translation.text("auth.device.instructions"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (message != null) {
            Text(
                text = message.failure?.authenticationErrorText(translation, message.status.messageId)
                    ?: translation.text(message.status.messageId),
                style = MaterialTheme.typography.bodySmall,
                color = if (message.status == AuthenticationConnectionStatus.CONNECTED) {
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
    error: Throwable?,
    deviceToken: String?,
    onSubmit: (RemoteAuthenticationInput, deviceToken: String?) -> Unit,
) {
    val translation = LocalTranslation.current
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
            label = { Text(translation.text("auth.bootstrap.tokenLabel")) },
            enabled = !submitting,
        )
        Spacer(Modifier.height(12.dp))
    }
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(translation.text("auth.field.username")) },
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
            label = { Text(translation.text("auth.field.displayName")) },
            singleLine = true,
            enabled = !submitting,
        )
    }
    Spacer(Modifier.height(12.dp))
    OutlinedSecretTextField(
        state = passwordState,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(translation.text("auth.field.password")) },
        enabled = !submitting,
        imeAction = if (initialized) ImeAction.Done else ImeAction.Next,
        onKeyboardAction = if (initialized) submit else null,
    )
    if (!initialized) {
        Spacer(Modifier.height(12.dp))
        OutlinedSecretTextField(
            state = passwordConfirmationState,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(translation.text("auth.field.confirmPassword")) },
            enabled = !submitting,
            isError = passwordMismatch,
            supportingText = if (passwordMismatch) {
                { Text(translation.text("auth.passwordMismatch")) }
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
            text = error.authenticationErrorText(translation),
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
            Text(if (initialized) translation.text("auth.signIn.action") else translation.text("auth.bootstrap.action"))
        }
    }
}

private data class AuthenticationConnectionMessage(
    val status: AuthenticationConnectionStatus,
    val failure: Throwable? = null,
)

private enum class AuthenticationConnectionStatus(val messageId: String) {
    START_FAILED("auth.device.startFailed"),
    RETRYING("auth.device.retrying"),
    CONNECTED("auth.device.connected"),
    DENIED("auth.device.denied"),
    EXPIRED("auth.device.codeExpired"),
}

internal fun Throwable.authenticationErrorText(
    translation: Translation,
    fallbackMessageId: String = "auth.error.requestFailed",
): String = if (this is RemoteAuthenticationException) {
    translation.text(
        when (code) {
            AuthenticationErrorCode.INVALID_CREDENTIALS -> "auth.error.invalidCredentials"
            AuthenticationErrorCode.RATE_LIMITED -> "auth.error.tooManyAttempts"
            AuthenticationErrorCode.BOOTSTRAP_REJECTED -> "auth.error.bootstrapRejected"
            AuthenticationErrorCode.INVALID_REQUEST -> "auth.error.invalidRequest"
            AuthenticationErrorCode.AUTHENTICATION_REQUIRED -> "auth.error.authenticationRequired"
            AuthenticationErrorCode.HTTPS_REQUIRED -> "auth.error.httpsRequired"
            AuthenticationErrorCode.CROSS_ORIGIN_REJECTED -> "auth.error.crossOriginRejected"
            AuthenticationErrorCode.REQUEST_TOO_LARGE -> "auth.error.requestTooLarge"
            AuthenticationErrorCode.REQUEST_READ_FAILED -> "auth.error.requestReadFailed"
            AuthenticationErrorCode.RUNTIME_NOT_INITIALIZED -> "auth.error.runtimeNotInitialized"
            AuthenticationErrorCode.DEVICE_CONNECTION_RATE_LIMITED -> "auth.error.tooManyDeviceRequests"
            AuthenticationErrorCode.INVALID_DEVICE_CONNECTION -> "auth.error.invalidDeviceCode"
            AuthenticationErrorCode.DEVICE_CONNECTION_FAILED -> "auth.error.deviceConnectionFailed"
            AuthenticationErrorCode.REQUEST_FAILED -> "auth.error.requestFailed"
        }
    )
} else {
    message ?: translation.text(fallbackMessageId)
}
