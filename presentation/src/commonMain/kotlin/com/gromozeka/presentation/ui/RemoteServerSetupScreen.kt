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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.client.normalizeRemoteUrl
import com.gromozeka.client.RemoteServerAddressException

@Composable
fun RemoteServerSetupScreen(
    initialAddress: String,
    connecting: Boolean,
    connectionError: Throwable?,
    onConnect: (String) -> Unit,
) {
    val translation = LocalTranslation.current
    var address by remember(initialAddress) { mutableStateOf(initialAddress) }
    var validationError by remember { mutableStateOf<Throwable?>(null) }

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
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(),
            ) {
                Text(
                    text = translation.text("connection.setup.title"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = translation.text("connection.setup.description"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        validationError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(translation.text("connection.setup.addressLabel")) },
                    placeholder = { Text("https://gromozeka.example") },
                    supportingText = {
                        Text(translation.text("connection.setup.supportedSchemes"))
                    },
                    singleLine = true,
                    enabled = !connecting,
                )
                val error = validationError?.let { failure ->
                    if (failure is RemoteServerAddressException) {
                        translation.text(failure.messageKey)
                    } else {
                        failure.message ?: translation.text("connection.setup.invalidAddress")
                    }
                } ?: connectionError?.clientErrorText(translation)
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    enabled = address.isNotBlank() && !connecting,
                    onClick = {
                        runCatching { normalizeRemoteUrl(address) }
                            .onSuccess(onConnect)
                            .onFailure { validationError = it }
                    },
                ) {
                    if (connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(translation.text("connection.setup.connect"))
                    }
                }
            }
        }
    }
}
