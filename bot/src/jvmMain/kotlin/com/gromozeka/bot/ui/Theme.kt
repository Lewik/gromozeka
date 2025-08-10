package com.gromozeka.bot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GromozekaTheme(content: @Composable () -> Unit) {
    val scale: (String, TextStyle) -> TextStyle = { styleName, original ->

        println("=== $styleName ===")
        println("Original fontSize: ${original.fontSize}")
        println("Original lineHeight: ${original.lineHeight}")
        println("LineHeight isSp: ${original.lineHeight.isSp}")
        println("LineHeight isEm: ${original.lineHeight.isEm}")
        println("LineHeight value: ${original.lineHeight.value}")

        val newFontSize = (original.fontSize.value - 3).sp
        val newLineHeight = (original.lineHeight.value - 3).sp
        println("================")

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
        displayLarge = scale("displayLarge", typography.displayLarge),
        displayMedium = scale("displayMedium", typography.displayMedium),
        displaySmall = scale("displaySmall", typography.displaySmall),
        headlineLarge = scale("headlineLarge", typography.headlineLarge),
        headlineMedium = scale("headlineMedium", typography.headlineMedium),
        headlineSmall = scale("headlineSmall", typography.headlineSmall),
        titleLarge = scale("titleLarge", typography.titleLarge),
        titleMedium = scale("titleMedium", typography.titleMedium),
        titleSmall = scale("titleSmall", typography.titleSmall),
        bodyLarge = scale("bodyLarge", typography.bodyLarge),
        bodyMedium = scale("bodyMedium", typography.bodyMedium),
        bodySmall = scale("bodySmall", typography.bodySmall),
        labelLarge = scale("labelLarge", typography.labelLarge),
        labelMedium = scale("labelMedium", typography.labelMedium),
        labelSmall = scale("labelSmall", typography.labelSmall)
    )

    MaterialTheme(
        typography = scaledTypography,
        content = content
    )
}

// Compact Button Defaults
object CompactButtonDefaults {
    val ContentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
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
    content: @Composable RowScope.() -> Unit,
) {
    OptionalTooltip(tooltip) {
        Button(
            onClick = onClick,
            modifier = modifier.height(CompactButtonDefaults.ButtonHeight),
            enabled = enabled,
            contentPadding = CompactButtonDefaults.ContentPadding,
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