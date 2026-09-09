package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.client.RemoteUserAdministrationService
import com.gromozeka.domain.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch

@Composable
fun UserAdministrationSettings(
    service: RemoteUserAdministrationService,
    coroutineScope: CoroutineScope,
) {
    val translation = LocalTranslation.current
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingUser by remember { mutableStateOf<User?>(null) }
    var passwordUser by remember { mutableStateOf<User?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(service, refreshKey) {
        loading = true
        service.observe()
            .catch { failure ->
                error = failure.message ?: failure.toString()
                loading = false
            }
            .collect { observedUsers ->
                users = observedUsers
                error = null
                loading = false
            }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = translation.text("security.users.title"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = translation.text("security.users.description"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !loading && error == null,
                onClick = { showCreateDialog = true },
            ) {
                Text(translation.text("security.users.addUser"))
            }
            TextButton(
                enabled = !loading,
                onClick = { refreshKey++ },
            ) {
                Text(translation.text("security.users.refresh"))
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
            onSave = { displayName, status, role, loginAllowed, aiAllowed ->
                submitting = true
                coroutineScope.launch {
                    error = null
                    try {
                        service.update(user.id, displayName, status, role, loginAllowed, aiAllowed)
                        editingUser = null
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
    val translation = LocalTranslation.current
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
                        text = user.identities.joinToString { identity ->
                            when (identity) {
                                is com.gromozeka.domain.model.UserIdentity.LocalLogin -> "@${identity.username}"
                                is com.gromozeka.domain.model.UserIdentity.Telegram -> "Telegram: ${identity.telegramUserId}" +
                                    (identity.username?.let { " (@$it)" } ?: "")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (user.status == User.Status.ACTIVE) user.role.displayName(translation) else translation.text("security.users.disabled"),
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
                    Text(translation.text("security.users.edit"))
                }
                TextButton(onClick = onResetPassword, enabled = user.username != null) {
                    Text(translation.text("security.users.resetPassword"))
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
    val translation = LocalTranslation.current
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    val passwordState = remember { TextFieldState() }
    val password = passwordState.text.toString()
    var role by remember { mutableStateOf(User.Role.MEMBER) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(translation.text("security.users.createTitle")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(translation.text("security.users.usernameLabel")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(translation.text("security.users.displayNameLabel")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedSecretTextField(
                    state = passwordState,
                    label = { Text(translation.text("security.users.initialPasswordLabel")) },
                    supportingText = { Text(translation.text("security.users.passwordLengthHint")) },
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
                Text(translation.text("security.users.add"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(translation.text("security.users.cancel"))
            }
        },
    )
}

@Composable
private fun EditRuntimeUserDialog(
    user: User,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, User.Status, User.Role, Boolean, Boolean) -> Unit,
) {
    val translation = LocalTranslation.current
    var displayName by remember(user.id) { mutableStateOf(user.displayName) }
    var status by remember(user.id) { mutableStateOf(user.status) }
    var role by remember(user.id) { mutableStateOf(user.role) }
    var loginAllowed by remember(user.id) { mutableStateOf(user.loginAllowed) }
    var aiAllowed by remember(user.id) { mutableStateOf(user.aiAllowed) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(user.username?.let { translation.text("security.users.editTitle", "username" to it) } ?: user.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(translation.text("security.users.displayNameLabel")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RoleSelector(role, onRoleChange = { role = it })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(translation.text("security.users.loginAllowed"), modifier = Modifier.weight(1f))
                    Switch(loginAllowed, { loginAllowed = it }, enabled = user.username != null)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(translation.text("security.users.aiAllowed"), modifier = Modifier.weight(1f))
                    Switch(aiAllowed, { aiAllowed = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(translation.text("security.users.active"))
                        Text(
                            text = translation.text("security.users.disableWarning"),
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
                onClick = { onSave(displayName, status, role, loginAllowed, aiAllowed) },
            ) {
                Text(translation.text("security.users.save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(translation.text("security.users.cancel"))
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
    val translation = LocalTranslation.current
    val passwordState = remember(user.id) { TextFieldState() }
    val password = passwordState.text.toString()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(translation.text("security.users.resetPasswordTitle", "username" to (user.username ?: user.displayName))) },
        text = {
            OutlinedSecretTextField(
                state = passwordState,
                label = { Text(translation.text("security.users.newPasswordLabel")) },
                supportingText = { Text(translation.text("security.users.resetPasswordWarning")) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = !submitting && password.length >= 12,
                onClick = { onReset(password) },
            ) {
                Text(translation.text("security.users.reset"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(translation.text("security.users.cancel"))
            }
        },
    )
}

@Composable
private fun RoleSelector(
    role: User.Role,
    onRoleChange: (User.Role) -> Unit,
) {
    val translation = LocalTranslation.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(translation.text("security.users.roleLabel"), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            User.Role.entries.forEach { candidate ->
                FilterChip(
                    selected = role == candidate,
                    onClick = { onRoleChange(candidate) },
                    label = { Text(candidate.displayName(translation)) },
                )
            }
        }
    }
}

private fun User.Role.displayName(translation: Translation): String =
    when (this) {
        User.Role.OWNER -> translation.text("security.users.role.owner")
        User.Role.MEMBER -> translation.text("security.users.role.member")
    }
