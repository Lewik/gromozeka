package com.gromozeka.bot.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.ui.viewmodel.ContextsPanelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextsPanelToolbar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    sortBy: ContextsPanelViewModel.SortOption,
    onSortByChange: (ContextsPanelViewModel.SortOption) -> Unit,
    showOnlyRecent: Boolean,
    onToggleRecentFilter: () -> Unit,
    onExtractContexts: () -> Unit,
    onRefresh: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { 
                Text(
                    "Search contexts...", 
                    style = MaterialTheme.typography.bodyMedium
                ) 
            },
            leadingIcon = { 
                Icon(
                    Icons.Default.Search, 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                ) 
            },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else null,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            )
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Filter and sort row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Sort dropdown
            var sortExpanded by remember { mutableStateOf(false) }
            
            ExposedDropdownMenuBox(
                expanded = sortExpanded,
                onExpandedChange = { sortExpanded = it },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = sortBy.displayName,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { 
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortExpanded) 
                    },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                    )
                )
                
                ExposedDropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false }
                ) {
                    ContextsPanelViewModel.SortOption.values().forEach { option ->
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    option.displayName,
                                    style = MaterialTheme.typography.bodyMedium
                                ) 
                            },
                            onClick = {
                                onSortByChange(option)
                                sortExpanded = false
                            },
                            leadingIcon = if (option == sortBy) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else null
                        )
                    }
                }
            }
            
            // Recent filter toggle
            FilterChip(
                selected = showOnlyRecent,
                onClick = onToggleRecentFilter,
                label = { 
                    Text(
                        "Recent", 
                        style = MaterialTheme.typography.bodySmall
                    ) 
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Action buttons row  
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onExtractContexts,
                enabled = !isLoading,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Extract")
            }
            
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isLoading,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun CompactButton(
    onClick: () -> Unit,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: (@Composable RowScope.() -> Unit)? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        if (content != null) {
            content()
        } else {
            icon?.let { iconVector ->
                Icon(
                    iconVector,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                if (text.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
            if (text.isNotEmpty()) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}