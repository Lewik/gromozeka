package com.gromozeka.bot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Search panel component with text field and search button
 * Features:
 * - Text input with search placeholder
 * - Clear button when text is not empty
 * - Search button with loading indicator
 * - Keyboard search action support
 */
@Composable
fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    isSearching: Boolean,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(LocalTranslation.current.searchSessionsPlaceholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Clear, LocalTranslation.current.clearSearchText)
                    }
                }
            } else null
        )

        // Search button with loading indicator
        CompactButton(
            onClick = onSearch,
            enabled = query.isNotBlank() && !isSearching,
            modifier = Modifier.fillMaxHeight(),
            tooltip = LocalTranslation.current.searchSessionsTooltip
        ) {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Search, LocalTranslation.current.searchSessionsTooltip)
            }
        }
    }
}