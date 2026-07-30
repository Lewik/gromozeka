package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gromozeka.client.RemoteUserAdministrationService
import com.gromozeka.domain.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun UserAdministrationSettings(
    service: RemoteUserAdministrationService,
    coroutineScope: CoroutineScope,
) {
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingUser by remember { mutableStateOf<User?>(null) }
    var passwordUser by remember { mutableStateOf<User?>(null) }
    var submitting by remember { mutableStateOf(false) }

    fun reload() {
        coroutineScope.launch {
            loading = true
            error = null
            runCatching { service.list() }
                .onSuccess { users = it }
                .onFailure { error = it.message ?: it.toString() }
            loading = false
        }
    }

    LaunchedEffect(service) {
        reload()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Runtime users",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Owners manage this isolated Runtime. Project roles control access to individual projects.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !loading && error == null,
                onClick = { showCreateDialog = true },
            ) {
                Text("Add user")
            }
            TextButton(
                enabled = !loading,
                onClick = ::reload,
            ) {
                Text("Refresh")
            }
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
        } else {
            users.forEach { user ->
                RuntimeUserCard(
                    user = user,
                    onEdit = { editingUser = user },
                    onResetPassword = { passwordUser = user },
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateRuntimeUserDialog(
            submitting = submitting,
            onDismiss = { if (!submitting) showCreateDialog = false },
            onCreate = { username, displayName, password, role ->
                submitting = true
                coroutineScope.launch {
                    error = null
                    try {
                        service.create(username, displayName, password, role)
                        showCreateDialog = false
                        users = service.list()
                    } catch (failure: Throwable) {
                        error = failure.message ?: failure.toString()
                    } finally {
                        submitting = false
                    }
                }
            },
        )
    }

    editingUser?.let { user ->
        EditRuntimeUserDialog(
            user = user,
            submitting = submitting,
            onDismiss = { if (!submitting) editingUser = null },
            onSave = { displayName, status, role ->
                submitting = true
                coroutineScope.launch {
                    error = null
                    try {
                        service.update(user.id, displayName, status, role)
                        editingUser = null
                        users = service.list()
                    } catch (failure: Throwable) {
                        error = failure.message ?: failure.toString()
                    } finally {
                        submitting = false
                    }
                }
            },
        )
    }

    passwordUser?.let { user ->
        ResetRuntimeUserPasswordDialog(
            user = user,
            submitting = submitting,
            onDismiss = { if (!submitting) passwordUser = null },
            onReset = { password ->
                submitting = true
                coroutineScope.launch {
                    error = null
                    try {
                        service.resetPassword(user.id, password)
                        passwordUser = null
                    } catch (failure: Throwable) {
                        error = failure.message ?: failure.toString()
                    } finally {
                        submitting = false
                    }
                }
            },
        )
    }
}

@Composable
private fun RuntimeUserCard(
    user: User,
    onEdit: () -> Unit,
    onResetPassword: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(user.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "@${user.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (user.status == User.Status.ACTIVE) user.role.displayName() else "Disabled",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (user.status == User.Status.ACTIVE) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) {
                    Text("Edit")
                }
                TextButton(onClick = onResetPassword) {
                    Text("Reset password")
                }
            }
        }
    }
}

@Composable
private fun CreateRuntimeUserDialog(
    submitting: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, User.Role) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(User.Role.MEMBER) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Runtime user") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Initial password") },
                    supportingText = { Text("At least 12 characters") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RoleSelector(role, onRoleChange = { role = it })
            }
        },
        confirmButton = {
            Button(
                enabled = !submitting && username.isNotBlank() && password.length >= 12,
                onClick = { onCreate(username, displayName, password, role) },
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun EditRuntimeUserDialog(
    user: User,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, User.Status, User.Role) -> Unit,
) {
    var displayName by remember(user.id) { mutableStateOf(user.displayName) }
    var status by remember(user.id) { mutableStateOf(user.status) }
    var role by remember(user.id) { mutableStateOf(user.role) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit @${user.username}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RoleSelector(role, onRoleChange = { role = it })
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Active")
                        Text(
                            text = "Disabling revokes sessions and personal access tokens.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = status == User.Status.ACTIVE,
                        onCheckedChange = {
                            status = if (it) User.Status.ACTIVE else User.Status.DISABLED
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !submitting && displayName.isNotBlank(),
                onClick = { onSave(displayName, status, role) },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ResetRuntimeUserPasswordDialog(
    user: User,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onReset: (String) -> Unit,
) {
    var password by remember(user.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset @${user.username} password") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("New password") },
                supportingText = { Text("Existing sessions and personal access tokens will be revoked.") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = !submitting && password.length >= 12,
                onClick = { onReset(password) },
            ) {
                Text("Reset")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun RoleSelector(
    role: User.Role,
    onRoleChange: (User.Role) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Runtime role", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            User.Role.entries.forEach { candidate ->
                FilterChip(
                    selected = role == candidate,
                    onClick = { onRoleChange(candidate) },
                    label = { Text(candidate.displayName()) },
                )
            }
        }
    }
}

private fun User.Role.displayName(): String =
    when (this) {
        User.Role.OWNER -> "Owner"
        User.Role.MEMBER -> "Member"
    }
