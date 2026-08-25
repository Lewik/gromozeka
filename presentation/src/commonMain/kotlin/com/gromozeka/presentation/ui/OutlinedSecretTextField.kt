package com.gromozeka.presentation.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

@Composable
internal fun OutlinedSecretTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Default,
    onKeyboardAction: (() -> Unit)? = null,
) {
    var hidden by remember(state) { mutableStateOf(true) }
    val visibilityDescription = if (hidden) "Show secret" else "Hide secret"

    OutlinedSecureTextField(
        state = state,
        modifier = modifier,
        enabled = enabled,
        label = label?.let { labelContent -> { labelContent() } },
        supportingText = supportingText,
        isError = isError,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        ),
        onKeyboardAction = onKeyboardAction?.let { action ->
            KeyboardActionHandler { action() }
        },
        textObfuscationMode = if (hidden) {
            TextObfuscationMode.Hidden
        } else {
            TextObfuscationMode.Visible
        },
        trailingIcon = {
            IconButton(
                enabled = enabled,
                onClick = { hidden = !hidden },
            ) {
                Icon(
                    imageVector = if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = visibilityDescription,
                )
            }
        },
    )
}
