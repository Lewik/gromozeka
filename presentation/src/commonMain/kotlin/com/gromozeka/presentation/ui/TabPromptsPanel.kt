package com.gromozeka.presentation.ui

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
import com.gromozeka.presentation.services.TabPromptService
import klog.KLoggers

private val log = KLoggers.logger("TabPromptsPanel")

@Composable
fun TabPromptsPanel(
    isVisible: Boolean,
    customPrompts: List<String>,
    onCustomPromptsChange: (List<String>) -> Unit,
    onClose: () -> Unit,
    tabPromptService: TabPromptService,
    modifier: Modifier = Modifier,
) {
    var availablePrompts by remember { mutableStateOf<List<TabPromptService.TabPromptOption>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isVisible) {
        if (!isVisible) return@LaunchedEffect

        isLoading = true
        error = null
        runCatching {
            tabPromptService.listAvailablePrompts()
        }.onSuccess { prompts ->
            availablePrompts = prompts
        }.onFailure { throwable ->
            error = throwable.message ?: throwable::class.simpleName ?: "Unknown error"
            log.warn(throwable) { "Failed to load tab prompts: ${throwable.message}" }
        }
        isLoading = false
    }

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
                        "Agent",
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
                    when {
                        isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        error != null -> {
                            Text(
                                text = "Failed to load prompts: $error",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        availablePrompts.isEmpty() -> {
                            Text(
                                text = "No prompts available",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> {
                            availablePrompts.forEach { prompt ->
                                val isSelected = customPrompts.contains(prompt.id)
                                val selectedIndex = if (isSelected) customPrompts.indexOf(prompt.id) else -1

                                PromptFileItem(
                                    prompt = prompt,
                                    isSelected = isSelected,
                                    selectedIndex = selectedIndex,
                                    totalSelected = customPrompts.size,
                                    onToggle = { selected ->
                                        if (selected) {
                                            onCustomPromptsChange(customPrompts + prompt.id)
                                        } else {
                                            onCustomPromptsChange(customPrompts - prompt.id)
                                        }
                                    },
                                    onMoveUp = {
                                        if (selectedIndex > 0) {
                                            val newList = customPrompts.toMutableList()
                                            newList.removeAt(selectedIndex)
                                            newList.add(selectedIndex - 1, prompt.id)
                                            onCustomPromptsChange(newList)
                                        }
                                    },
                                    onMoveDown = {
                                        if (selectedIndex < customPrompts.size - 1) {
                                            val newList = customPrompts.toMutableList()
                                            newList.removeAt(selectedIndex)
                                            newList.add(selectedIndex + 1, prompt.id)
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
    }
}

@Composable
private fun PromptFileItem(
    prompt: TabPromptService.TabPromptOption,
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
                    prompt.name,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${prompt.type} - ${prompt.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
