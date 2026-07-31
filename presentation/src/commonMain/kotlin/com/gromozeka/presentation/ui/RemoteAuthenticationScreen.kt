package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

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
    onSubmit: (RemoteAuthenticationInput) -> Unit,
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
                    text = if (initialized) "Sign in to Gromozeka" else "Create the first owner",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (initialized) {
                        "Use the account stored on this Gromozeka Server."
                    } else {
                        "The bootstrap token is printed once in the Server log."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
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
                    enabled = !submitting &&
                        username.isNotBlank() &&
                        password.length >= 12 &&
                        (initialized || (
                            bootstrapToken.isNotBlank() &&
                                password == passwordConfirmation
                            )),
                    onClick = {
                        onSubmit(
                            RemoteAuthenticationInput(
                                username = username,
                                password = password,
                                displayName = displayName,
                                bootstrapToken = bootstrapToken,
                            )
                        )
                    },
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
        }
    }
}
