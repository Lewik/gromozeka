package com.gromozeka.presentation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.WorkspaceDirectoryListing
import com.gromozeka.domain.model.WorkspacePath
import com.gromozeka.domain.service.WorkspaceFileSystemService
import com.gromozeka.presentation.ui.session.BasicGromozekaDialog

@Composable
fun WorkspacePathPickerDialog(
    title: String,
    fileSystemService: WorkspaceFileSystemService,
    initialPath: String?,
    showFiles: Boolean,
    selectLabel: String,
    allowDirectoryEntrySelection: Boolean = false,
    onSelect: (WorkspacePath) -> Unit,
    onDismiss: () -> Unit,
) {
    var requestedPath by remember(initialPath) { mutableStateOf(initialPath) }
    var pathInput by remember(initialPath) { mutableStateOf(initialPath.orEmpty()) }
    var listing by remember { mutableStateOf<WorkspaceDirectoryListing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(requestedPath, refreshKey) {
        isLoading = true
        error = null
        listing = null
        runCatching {
            fileSystemService.browse(requestedPath, includeFiles = showFiles)
        }.onSuccess { loaded ->
            listing = loaded
            pathInput = loaded.directory.path
        }.onFailure { failure ->
            error = failure.message ?: failure.toString()
        }
        isLoading = false
    }

    BasicGromozekaDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 780.dp)
                .heightIn(min = 360.dp, max = 680.dp)
                .testTag(UiTestTag.WorkspacePathPicker.value),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = pathInput,
                        onValueChange = { pathInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Server path") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            pathInput.trim().takeIf(String::isNotEmpty)?.let { requestedPath = it }
                        },
                    ) {
                        Text("Open")
                    }
                    IconButton(
                        onClick = { listing?.parentPath?.let { requestedPath = it } },
                        enabled = listing?.parentPath != null && !isLoading,
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Parent directory")
                    }
                    IconButton(
                        onClick = { refreshKey++ },
                        enabled = !isLoading,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }

                error?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                when {
                    isLoading && listing == null -> {
                        Spacer(modifier = Modifier.weight(1f))
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    else -> {
                        val visibleEntries = listing
                            ?.entries
                            .orEmpty()
                            .filter { showFiles || it.kind == WorkspacePath.Kind.DIRECTORY }

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(visibleEntries, key = WorkspacePath::path) { entry ->
                                WorkspacePathRow(
                                    entry = entry,
                                    allowDirectorySelection = allowDirectoryEntrySelection,
                                    onOpen = {
                                        if (entry.kind == WorkspacePath.Kind.DIRECTORY) {
                                            requestedPath = entry.path
                                        } else {
                                            onSelect(entry)
                                        }
                                    },
                                    onSelect = { onSelect(entry) },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            listing?.directory?.let(onSelect)
                        },
                        enabled = listing != null && !isLoading,
                    ) {
                        Text(selectLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspacePathRow(
    entry: WorkspacePath,
    allowDirectorySelection: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.kind == WorkspacePath.Kind.DIRECTORY) {
                Icons.Default.Folder
            } else {
                Icons.Default.InsertDriveFile
            },
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = entry.name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (allowDirectorySelection && entry.kind == WorkspacePath.Kind.DIRECTORY) {
            IconButton(onClick = onSelect) {
                Icon(Icons.Default.Add, contentDescription = "Add directory context")
            }
        }
    }
}
