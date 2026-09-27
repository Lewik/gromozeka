package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp

/** Shared frame for top and bottom conversation controls; the divider faces the message area. */
@Composable
fun DockedPanel(
    dividerAtTop: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Column {
            if (dividerAtTop) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier.padding(GromozekaTheme.spacing.panelPadding),
                verticalArrangement = Arrangement.spacedBy(GromozekaTheme.spacing.rowGap),
                content = content,
            )
            if (!dividerAtTop) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
