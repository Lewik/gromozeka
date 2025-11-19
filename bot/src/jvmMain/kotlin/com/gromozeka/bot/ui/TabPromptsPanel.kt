package com.gromozeka.bot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.application.service.TabPromptService

@Composable
fun TabPromptsPanel(
    isVisible: Boolean,
    customPrompts: List<String>,
    onCustomPromptsChange: (List<String>) -> Unit,
    onClose: () -> Unit,
    tabPromptService: TabPromptService,
    modifier: Modifier = Modifier,
) {
    val availablePrompts = remember { tabPromptService.listAvailablePrompts() }

    AnimatedVisibility(
        visible = isVisible,
        enter = expandHorizontally(),
        exit = shrinkHorizontally(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .width(400.dp)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Tab Prompts",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availablePrompts.forEach { fileName ->
                        val isSelected = customPrompts.contains(fileName)
                        val selectedIndex = if (isSelected) customPrompts.indexOf(fileName) else -1

                        PromptFileItem(
                            fileName = fileName,
                            isSelected = isSelected,
                            selectedIndex = selectedIndex,
                            totalSelected = customPrompts.size,
                            onToggle = { selected ->
                                if (selected) {
                                    onCustomPromptsChange(customPrompts + fileName)
                                } else {
                                    onCustomPromptsChange(customPrompts - fileName)
                                }
                            },
                            onMoveUp = {
                                if (selectedIndex > 0) {
                                    val newList = customPrompts.toMutableList()
                                    newList.removeAt(selectedIndex)
                                    newList.add(selectedIndex - 1, fileName)
                                    onCustomPromptsChange(newList)
                                }
                            },
                            onMoveDown = {
                                if (selectedIndex < customPrompts.size - 1) {
                                    val newList = customPrompts.toMutableList()
                                    newList.removeAt(selectedIndex)
                                    newList.add(selectedIndex + 1, fileName)
                                    onCustomPromptsChange(newList)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptFileItem(
    fileName: String,
    isSelected: Boolean,
    selectedIndex: Int,
    totalSelected: Int,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = onToggle
            )

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (isSelected) {
                    Text(
                        "Order: ${selectedIndex + 1} of $totalSelected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (isSelected) {
            Row {
                IconButton(
                    onClick = onMoveUp,
                    enabled = selectedIndex > 0
                ) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = "Move up",
                        tint = if (selectedIndex > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }

                IconButton(
                    onClick = onMoveDown,
                    enabled = selectedIndex < totalSelected - 1
                ) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = "Move down",
                        tint = if (selectedIndex < totalSelected - 1) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            }
        }
    }
}
