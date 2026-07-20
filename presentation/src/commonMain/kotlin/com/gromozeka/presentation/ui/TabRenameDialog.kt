package com.gromozeka.presentation.ui

import androidx.compose.runtime.Composable

/**
 * Modal dialog for renaming a tab
 *
 * @param isOpen Whether the dialog is open
 * @param currentName Current tab name
 * @param onRename Callback when tab is renamed
 * @param onDismiss Callback when dialog is dismissed
 */
@Composable
fun TabRenameDialog(
    isOpen: Boolean,
    currentName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    NameEditDialog(
        isOpen = isOpen,
        currentName = currentName,
        title = LocalTranslation.current.renameTabTitle,
        label = LocalTranslation.current.tabNameLabel,
        onRename = onRename,
        onDismiss = onDismiss,
    )
}
