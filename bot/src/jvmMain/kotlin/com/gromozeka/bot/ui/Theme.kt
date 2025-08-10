package com.gromozeka.bot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GromozekaTheme(content: @Composable () -> Unit) {
    val scale: (TextStyle) -> TextStyle = { original ->
        val newFontSize = (original.fontSize.value - 3).sp
        val newLineHeight = (original.lineHeight.value - 3).sp

        original.copy(
            fontSize = newFontSize,
            lineHeight = newLineHeight,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both
            )
        )
    }

    val typography = MaterialTheme.typography
    val scaledTypography = Typography(
        displayLarge = scale(typography.displayLarge),
        displayMedium = scale(typography.displayMedium),
        displaySmall = scale(typography.displaySmall),
        headlineLarge = scale(typography.headlineLarge),
        headlineMedium = scale(typography.headlineMedium),
        headlineSmall = scale(typography.headlineSmall),
        titleLarge = scale(typography.titleLarge),
        titleMedium = scale(typography.titleMedium),
        titleSmall = scale(typography.titleSmall),
        bodyLarge = scale(typography.bodyLarge),
        bodyMedium = scale(typography.bodyMedium),
        bodySmall = scale(typography.bodySmall),
        labelLarge = scale(typography.labelLarge),
        labelMedium = scale(typography.labelMedium),
        labelSmall = scale(typography.labelSmall)
    )

    MaterialTheme(
        typography = scaledTypography,
        content = content
    )
}

// Compact Button Defaults
object CompactButtonDefaults {
    val ContentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
    val ButtonHeight = 28.dp
    val CornerRadius = 6.dp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionalTooltip(
    tooltip: String?,
    content: @Composable () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = if (tooltip != null) {
            { PlainTooltip { Text(tooltip) } }
        } else {
            { }  // Empty composable
        },
        state = rememberTooltipState(),
        content = content
    )
}

@Composable
fun CompactButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltip: String? = null,
    contentPadding: PaddingValues = CompactButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    OptionalTooltip(tooltip) {
        Button(
            onClick = onClick,
            modifier = modifier.height(CompactButtonDefaults.ButtonHeight),
            enabled = enabled,
            contentPadding = contentPadding,
            shape = RoundedCornerShape(CompactButtonDefaults.CornerRadius),
            content = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        )
    }
}

@Composable
fun CompactIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltip: String? = null,
    content: @Composable () -> Unit,
) {
    OptionalTooltip(tooltip) {
        FilledIconButton(
            onClick = onClick,
            modifier = modifier.size(CompactButtonDefaults.ButtonHeight),
            shape = RoundedCornerShape(CompactButtonDefaults.CornerRadius),
            enabled = enabled,
            content = content
        )
    }
}

@Composable
fun CompactCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(CompactButtonDefaults.CornerRadius),
        content = content
    )
}