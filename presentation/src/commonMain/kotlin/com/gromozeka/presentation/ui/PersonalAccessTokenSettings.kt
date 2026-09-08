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
import com.gromozeka.presentation.services.translation.data.Translation
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
    val translation = LocalTranslation.current
    var tokens by remember { mutableStateOf<List<PersonalAccessTokenView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var issuedToken by remember { mutableStateOf<IssuedPersonalAccessTokenResponse?>(null) }

    LaunchedEffect(service) {
        runCatching { service.list() }
            .onSuccess { tokens = it }
            .onFailure { error = it }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = translation.text("security.tokens.title"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = translation.text("security.tokens.description"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(onClick = { showCreateDialog = true }) {
            Text(translation.text("security.tokens.createButton"))
        }

        error?.let {
            Text(
                text = if (it is TokenAlreadyRevokedException) {
                    translation.text("security.tokens.alreadyRevoked")
                } else {
                    it.message ?: it.toString()
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (loading) {
            CircularProgressIndicator()
        } else if (tokens.isEmpty()) {
            Text(
                text = translation.text("security.tokens.empty"),
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
                                if (!service.revoke(token.id)) throw TokenAlreadyRevokedException()
                                tokens = service.list()
                            } catch (failure: Throwable) {
                                error = failure
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
                        error = failure
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
            title = { Text(translation.text("security.tokens.createdTitle")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(translation.text("security.tokens.copyWarning"))
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
                    Text(translation.text("security.tokens.savedAcknowledgment"))
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
    val translation = LocalTranslation.current
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
                        Text(translation.text("security.tokens.revoke"))
                    }
                } else {
                    Text(
                        text = translation.text("security.tokens.revoked"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Text(
                text = token.scopes.joinToString { it.displayName(translation) },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = token.displayDates(translation),
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
    val translation = LocalTranslation.current
    var name by remember { mutableStateOf("") }
    var expiresInDays by remember { mutableStateOf("365") }
    var scopes by remember {
        mutableStateOf(setOf(PersonalAccessToken.Scope.MCP_MEMORY))
    }
    val parsedExpiration = expiresInDays.toIntOrNull()
    val expirationValid = expiresInDays.isBlank() || parsedExpiration?.let { it in 1..3_650 } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(translation.text("security.tokens.createTitle")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(translation.text("security.tokens.nameLabel")) },
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
                        Text(scope.displayName(translation))
                    }
                }
                OutlinedTextField(
                    value = expiresInDays,
                    onValueChange = { expiresInDays = it.filter(Char::isDigit) },
                    label = { Text(translation.text("security.tokens.expirationLabel")) },
                    supportingText = { Text(translation.text("security.tokens.expirationHint")) },
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
                Text(translation.text("security.tokens.create"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(translation.text("security.tokens.cancel"))
            }
        },
    )
}

private fun PersonalAccessToken.Scope.displayName(translation: Translation): String =
    when (this) {
        PersonalAccessToken.Scope.MCP_MEMORY -> translation.text("security.tokens.scope.memory")
        PersonalAccessToken.Scope.MCP_CONTROL -> translation.text("security.tokens.scope.control")
    }

private class TokenAlreadyRevokedException : IllegalStateException()

private fun PersonalAccessTokenView.displayDates(translation: Translation): String =
    when {
        expiresAt != null && lastUsedAt != null -> translation.text(
            "security.tokens.dates.expiringAndUsed",
            "createdAt" to createdAt,
            "expiresAt" to expiresAt,
            "lastUsedAt" to lastUsedAt,
        )
        expiresAt != null -> translation.text(
            "security.tokens.dates.expiring",
            "createdAt" to createdAt,
            "expiresAt" to expiresAt,
        )
        lastUsedAt != null -> translation.text(
            "security.tokens.dates.nonExpiringAndUsed",
            "createdAt" to createdAt,
            "lastUsedAt" to lastUsedAt,
        )
        else -> translation.text("security.tokens.dates.nonExpiring", "createdAt" to createdAt)
    }
