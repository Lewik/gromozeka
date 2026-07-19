package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.WorkspaceContextReference
import com.gromozeka.presentation.ui.session.BasicGromozekaDialog

@Composable
fun WorkspaceContextDialog(
    onSelect: (WorkspaceContextReference) -> Unit,
    onDismiss: () -> Unit,
) {
    var relativePath by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(WorkspaceContextReference.Kind.FILE) }
    val reference = remember(relativePath, kind) {
        runCatching {
            val normalizedPath = relativePath.trim().removePrefix("./")
            WorkspaceContextReference(
                relativePath = normalizedPath,
                name = normalizedPath.substringAfterLast('/').ifBlank { "Workspace root" },
                kind = kind,
            )
        }
    }

    BasicGromozekaDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 620.dp)
                .testTag(UiTestTag.WorkspacePathPicker.value),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Add workspace context", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Enter a path relative to the conversation workspace.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = relativePath,
                    onValueChange = { relativePath = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Relative path") },
                    placeholder = { Text("src/main/kotlin") },
                    singleLine = true,
                    isError = relativePath.isNotBlank() && reference.isFailure,
                    supportingText = {
                        reference.exceptionOrNull()?.message?.let { Text(it) }
                    },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = kind == WorkspaceContextReference.Kind.FILE,
                        onClick = { kind = WorkspaceContextReference.Kind.FILE },
                        label = { Text("File") },
                        leadingIcon = {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null)
                        },
                    )
                    FilterChip(
                        selected = kind == WorkspaceContextReference.Kind.DIRECTORY,
                        onClick = { kind = WorkspaceContextReference.Kind.DIRECTORY },
                        label = { Text("Directory") },
                        leadingIcon = {
                            Icon(Icons.Default.Folder, contentDescription = null)
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onSelect(reference.getOrThrow()) },
                        enabled = reference.isSuccess,
                    ) {
                        Text("Add")
                    }
                }
            }
        }
    }
}
