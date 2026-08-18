package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.NamedSecret
import com.gromozeka.domain.service.CurrentUserNamedSecretService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun NamedSecretSettings(
    service: CurrentUserNamedSecretService,
    coroutineScope: CoroutineScope,
) {
    var secrets by remember { mutableStateOf<List<NamedSecret>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        coroutineScope.launch {
            loading = true
            error = null
            runCatching { service.list() }
                .onSuccess { secrets = it }
                .onFailure { error = it.message ?: it.toString() }
            loading = false
        }
    }

    LaunchedEffect(service) { reload() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Named secrets", style = MaterialTheme.typography.titleLarge)
        Text(
            "Store durable credentials once and reference them as secret://name. Values are encrypted and are not shown again.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name") },
            placeholder = { Text("github-pat") },
            singleLine = true,
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Description") },
            singleLine = true,
        )
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Value") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Button(
            enabled = !saving && name.isNotBlank() && value.isNotBlank(),
            onClick = {
                coroutineScope.launch {
                    saving = true
                    error = null
                    runCatching { service.save(name, description, value) }
                        .onSuccess {
                            name = ""
                            description = ""
                            value = ""
                            secrets = service.list()
                        }
                        .onFailure { error = it.message ?: it.toString() }
                    saving = false
                }
            },
        ) {
            Text(if (saving) "Saving..." else "Save or rotate")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) {
            CircularProgressIndicator()
        } else {
            secrets.forEach { secret ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(secret.name, style = MaterialTheme.typography.titleMedium)
                            Text(secret.reference, fontFamily = FontFamily.Monospace)
                            if (secret.description.isNotBlank()) {
                                Text(secret.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    runCatching { service.delete(secret.id) }
                                        .onSuccess { secrets = service.list() }
                                        .onFailure { error = it.message ?: it.toString() }
                                }
                            }
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}
