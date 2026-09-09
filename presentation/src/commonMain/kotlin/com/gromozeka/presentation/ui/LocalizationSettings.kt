package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.TranslationMetadata
import com.gromozeka.presentation.services.translation.TranslationService
import kotlinx.coroutines.launch

@Composable
internal fun LocalizationSettings(translationService: TranslationService) {
    val translation = LocalTranslation.current
    val snapshot by translationService.snapshot.collectAsState()
    val operationError by translationService.operationError.collectAsState()
    val busy by translationService.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var choosingTranslation by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var exportText by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<TranslationMetadata?>(null) }
    var saved by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var clipboardError by remember { mutableStateOf<Throwable?>(null) }

    LaunchedEffect(snapshot?.effectiveSelectionId) {
        deleteTarget = null
    }

    SettingsGroup(title = translation.settings.localizationTitle) {
        val currentSnapshot = snapshot
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(translation.switchLanguage, modifier = Modifier.weight(1f))
            TextButton(
                enabled = !busy,
                onClick = {
                    saved = false
                    scope.launch { translationService.refreshTranslations() }
                },
            ) {
                Text(translation.settings.refreshTranslationsButton)
            }
        }

        if (currentSnapshot == null) {
            Text(translation.text("localization.loading"))
        } else {
            val selected = currentSnapshot.available.firstOrNull { it.id == currentSnapshot.effectiveSelectionId }
            Column {
                OutlinedButton(
                    onClick = { choosingTranslation = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(selected?.name ?: currentSnapshot.selectedPackage.name)
                }
                DropdownMenu(
                    expanded = choosingTranslation && !busy,
                    onDismissRequest = { choosingTranslation = false },
                ) {
                    currentSnapshot.available.forEach { candidate ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(candidate.name)
                                    Text(
                                        translation.text(if (candidate.builtin) "localization.builtIn" else "localization.personal"),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                            onClick = {
                                choosingTranslation = false
                                saved = false
                                scope.launch { translationService.select(candidate.id) }
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(translation.text("localization.synchronizeClients"))
                    Text(
                        translation.text("localization.synchronizeClientsDescription"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = currentSnapshot.synchronizeClients,
                    enabled = !busy,
                    onCheckedChange = { enabled ->
                        saved = false
                        scope.launch { translationService.setSynchronizeClients(enabled) }
                    },
                )
            }

            OutlinedButton(enabled = !busy, onClick = { saved = false; importing = true }) {
                Text(translation.text("localization.import"))
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    copied = false
                    clipboardError = null
                    exportText = translationService.exportJson()
                },
            ) {
                Text(translation.text("localization.export"))
            }
            if (selected?.builtin == false) {
                TextButton(enabled = !busy, onClick = { deleteTarget = selected }) {
                    Text(translation.text("localization.delete"), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Text(
            translation.settings.customTranslationInfoMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (busy) CircularProgressIndicator()
        operationError?.let { error ->
            Text(
                translation.text("localization.failed", "error" to (error.message ?: error.toString())),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (saved && operationError == null) Text(translation.text("localization.saved"))
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = { if (!busy) importing = false },
            title = { Text(translation.text("localization.import")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        enabled = !busy,
                        label = { Text(translation.text("localization.json")) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 360.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                    )
                    operationError?.let { error ->
                        Text(
                            translation.text("localization.failed", "error" to (error.message ?: error.toString())),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && importText.isNotBlank(),
                    onClick = {
                        scope.launch {
                            translationService.importJson(importText)
                            if (translationService.operationError.value == null) {
                                importing = false
                                importText = ""
                                saved = true
                            }
                        }
                    },
                ) {
                    Text(translation.text("localization.save"))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { importing = false }) {
                    Text(translation.text("localization.cancel"))
                }
            },
        )
    }

    exportText?.let { json ->
        AlertDialog(
            onDismissRequest = { exportText = null },
            title = { Text(translation.text("localization.export")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(translation.text("localization.exportReady"))
                    OutlinedTextField(
                        value = json,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(translation.text("localization.json")) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 360.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                    )
                    if (copied) Text(translation.text("localization.copied"))
                    clipboardError?.let { error ->
                        Text(
                            translation.text("localization.failed", "error" to (error.message ?: error.toString())),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboardError = null
                    try {
                        clipboard.setText(AnnotatedString(json))
                        copied = true
                    } catch (error: Exception) {
                        clipboardError = error
                    }
                }) {
                    Text(translation.text("localization.copy"))
                }
            },
            dismissButton = {
                TextButton(onClick = { exportText = null }) { Text(translation.text("common.close")) }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            title = { Text(translation.text("localization.deleteTitle")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(translation.text("localization.deleteDescription", "name" to target.name))
                    operationError?.let { error ->
                        Text(
                            translation.text("localization.failed", "error" to (error.message ?: error.toString())),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && snapshot?.effectiveSelectionId == target.id,
                    onClick = {
                        saved = false
                        scope.launch {
                            translationService.deletePackage(target.id)
                            if (translationService.operationError.value == null) deleteTarget = null
                        }
                    },
                ) {
                    Text(translation.text("localization.delete"))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { deleteTarget = null }) {
                    Text(translation.text("localization.cancel"))
                }
            },
        )
    }
}
