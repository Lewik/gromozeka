package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.getTabDisplayName
import com.gromozeka.presentation.ui.viewmodel.TabViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun CustomTabRow(
    selectedTabIndex: Int,
    showTabsAtBottom: Boolean,
    tabs: List<TabViewModel>,
    hoveredTabIndex: Int,
    onTabSelect: (Int?) -> Unit,
    onTabHover: (Int) -> Unit,
    onTabHoverExit: () -> Unit,
    onRenameTab: (Int, String) -> Unit,
    coroutineScope: CoroutineScope,
) {
    var renameDialogOpen by remember { mutableStateOf(false) }
    var renameTabIndex by remember { mutableStateOf(-1) }
    var renameCurrentName by remember { mutableStateOf("") }

    SecondaryTabRow(
        selectedTabIndex = selectedTabIndex,
        indicator = {
            TabRowDefaults.SecondaryIndicator(
                Modifier.Companion
                    .tabIndicatorOffset(selectedTabIndex, matchContentSize = false)
                    .offset(y = if (showTabsAtBottom) (-46).dp else 0.dp)
            )
        },
        divider = {},
    ) {
        // Projects tab (first tab)
        OptionalTooltip("Проекты") {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = {
                    coroutineScope.launch {
                        onTabSelect(null)
                    }
                },
                text = {
                    Row(verticalAlignment = Alignment.Companion.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = "Sessions list")
                    }
                }
            )
        }

        // Session tabs with loading indicators and edit button
        tabs.forEachIndexed { index, tab ->
            val isLoading = tab.isWaitingForResponse.collectAsState().value
            val tabUiState = tab.uiState.collectAsState().value
            val tabIndex = index + 1

            Tab(
                selected = selectedTabIndex == tabIndex,
                onClick = {
                    coroutineScope.launch {
                        onTabSelect(index)
                    }
                },
                modifier = Modifier.Companion.onPointerEvent(PointerEventType.Companion.Enter) {
                    onTabHover(index)
                }
                    .onPointerEvent(PointerEventType.Companion.Exit) { onTabHoverExit() },
                text = {
                    Box(
                        modifier = Modifier.Companion.fillMaxWidth(),
                        contentAlignment = Alignment.Companion.Center
                    ) {
                        Text(getTabDisplayName(tabUiState, index))

                        // Edit button (pencil) - appears on hover
                        if (hoveredTabIndex == index) {
                            IconButton(
                                onClick = {
                                    renameTabIndex = index
                                    renameCurrentName = getTabDisplayName(tabUiState, index)
                                    renameDialogOpen = true
                                },
                                modifier = Modifier.Companion
                                    .size(16.dp)
                                    .align(Alignment.Companion.CenterStart)
                                    .offset(x = (-8).dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit tab name")
                            }
                        }

                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.Companion
                                    .size(12.dp)
                                    .align(Alignment.Companion.CenterEnd),
                                strokeWidth = 1.5.dp
                            )
                        }
                    }
                }
            )
        }

        // Rename dialog
        TabRenameDialog(
            isOpen = renameDialogOpen,
            currentName = renameCurrentName,
            onRename = { newName ->
                val tabIndexToRename = renameTabIndex
                coroutineScope.launch {
                    onRenameTab(tabIndexToRename, newName)
                }
            },
            onDismiss = {
                renameDialogOpen = false
                renameTabIndex = -1
                renameCurrentName = ""
            }
        )
    }
}