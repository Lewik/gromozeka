package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.client.RemotePersonalAccessTokenService
import com.gromozeka.domain.model.PersonalAccessToken
import com.gromozeka.remote.protocol.IssuedPersonalAccessTokenResponse
import com.gromozeka.remote.protocol.PersonalAccessTokenView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun PersonalAccessTokenSettings(
    service: RemotePersonalAccessTokenService,
    coroutineScope: CoroutineScope,
) {
    var tokens by remember { mutableStateOf<List<PersonalAccessTokenView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var issuedToken by remember { mutableStateOf<IssuedPersonalAccessTokenResponse?>(null) }

    LaunchedEffect(service) {
        runCatching { service.list() }
            .onSuccess { tokens = it }
            .onFailure { error = it.message ?: it.toString() }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Personal access tokens",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Use separate revocable tokens for external MCP clients. " +
                    "Your account password is never stored in an MCP configuration.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(onClick = { showCreateDialog = true }) {
            Text("Create token")
        }

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (loading) {
            CircularProgressIndicator()
        } else if (tokens.isEmpty()) {
            Text(
                text = "No personal access tokens.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            tokens.asReversed().forEach { token ->
                PersonalAccessTokenCard(
                    token = token,
                    onRevoke = {
                        coroutineScope.launch {
                            error = null
                            try {
                                check(service.revoke(token.id)) { "Token is already revoked" }
                                tokens = service.list()
                            } catch (failure: Throwable) {
                                error = failure.message ?: failure.toString()
                            }
                        }
                    },
                )
            }
        }
    }

    if (showCreateDialog) {
        CreatePersonalAccessTokenDialog(
            submitting = creating,
            onDismiss = {
                if (!creating) showCreateDialog = false
            },
            onCreate = { name, scopes, expiresInDays ->
                creating = true
                coroutineScope.launch {
                    error = null
                    try {
                        val issued = service.create(name, scopes, expiresInDays)
                        issuedToken = issued
                        showCreateDialog = false
                        tokens = service.list()
                    } catch (failure: Throwable) {
                        error = failure.message ?: failure.toString()
                    } finally {
                        creating = false
                    }
                }
            },
        )
    }

    issuedToken?.let { issued ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Token created") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Copy this token now. Gromozeka will not show it again.")
                    SelectionContainer {
                        Text(
                            text = issued.rawToken,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { issuedToken = null }) {
                    Text("I saved it")
                }
            },
        )
    }
}

@Composable
private fun PersonalAccessTokenCard(
    token: PersonalAccessTokenView,
    onRevoke: () -> Unit,
) {
    val active = token.revokedAt == null
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(token.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        token.tokenPrefix + "...",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (active) {
                    TextButton(onClick = onRevoke) {
                        Text("Revoke")
                    }
                } else {
                    Text(
                        text = "Revoked",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Text(
                text = token.scopes.joinToString { it.displayName() },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Created ${token.createdAt}" +
                    (token.expiresAt?.let { " · Expires $it" } ?: " · No expiration") +
                    (token.lastUsedAt?.let { " · Last used $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CreatePersonalAccessTokenDialog(
    submitting: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, Set<PersonalAccessToken.Scope>, Int?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var expiresInDays by remember { mutableStateOf("365") }
    var scopes by remember {
        mutableStateOf(setOf(PersonalAccessToken.Scope.MCP_MEMORY))
    }
    val parsedExpiration = expiresInDays.toIntOrNull()
    val expirationValid = expiresInDays.isBlank() || parsedExpiration?.let { it in 1..3_650 } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create personal access token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                PersonalAccessToken.Scope.entries.forEach { scope ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = scope in scopes,
                            onCheckedChange = { checked ->
                                scopes = if (checked) scopes + scope else scopes - scope
                            },
                        )
                        Text(scope.displayName())
                    }
                }
                OutlinedTextField(
                    value = expiresInDays,
                    onValueChange = { expiresInDays = it.filter(Char::isDigit) },
                    label = { Text("Expires in days") },
                    supportingText = { Text("Leave empty for no expiration") },
                    isError = !expirationValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !submitting &&
                    name.isNotBlank() &&
                    scopes.isNotEmpty() &&
                    expirationValid,
                onClick = {
                    onCreate(
                        name,
                        scopes,
                        expiresInDays.takeIf(String::isNotBlank)?.toInt(),
                    )
                },
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun PersonalAccessToken.Scope.displayName(): String =
    when (this) {
        PersonalAccessToken.Scope.MCP_MEMORY -> "Memory MCP"
        PersonalAccessToken.Scope.MCP_CONTROL -> "Control MCP"
    }
