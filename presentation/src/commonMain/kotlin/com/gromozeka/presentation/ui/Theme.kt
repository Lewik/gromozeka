package com.gromozeka.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.services.theming.data.DarkTheme
import com.gromozeka.presentation.services.theming.data.Theme
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding

@Composable
fun GromozekaTheme(
    currentTheme: Theme = DarkTheme(),
    content: @Composable () -> Unit,
) {
    // Create basic ColorScheme from current theme data - only required fields for now
    val colorScheme = ColorScheme(
        primary = currentTheme.primary.toComposeColor(),
        onPrimary = currentTheme.onPrimary.toComposeColor(),
        primaryContainer = currentTheme.primaryContainer.toComposeColor(),
        onPrimaryContainer = currentTheme.onPrimaryContainer.toComposeColor(),
        inversePrimary = currentTheme.inversePrimary?.toComposeColor()
            ?: currentTheme.primaryContainer.toComposeColor(),

        secondary = currentTheme.secondary.toComposeColor(),
        onSecondary = currentTheme.onSecondary.toComposeColor(),
        secondaryContainer = currentTheme.secondaryContainer.toComposeColor(),
        onSecondaryContainer = currentTheme.onSecondaryContainer.toComposeColor(),

        tertiary = currentTheme.tertiary?.toComposeColor() ?: currentTheme.primary.toComposeColor(),
        onTertiary = currentTheme.onTertiary?.toComposeColor() ?: currentTheme.onPrimary.toComposeColor(),
        tertiaryContainer = currentTheme.tertiaryContainer?.toComposeColor()
            ?: currentTheme.primaryContainer.toComposeColor(),
        onTertiaryContainer = currentTheme.onTertiaryContainer?.toComposeColor()
            ?: currentTheme.onPrimaryContainer.toComposeColor(),

        background = currentTheme.background.toComposeColor(),
        onBackground = currentTheme.onBackground.toComposeColor(),
        surface = currentTheme.surface.toComposeColor(),
        onSurface = currentTheme.onSurface.toComposeColor(),
        surfaceVariant = currentTheme.surfaceVariant.toComposeColor(),
        onSurfaceVariant = currentTheme.onSurfaceVariant.toComposeColor(),
        surfaceTint = currentTheme.primary.toComposeColor(),
        inverseSurface = currentTheme.inverseSurface?.toComposeColor()
            ?: currentTheme.onSurface.toComposeColor(),
        inverseOnSurface = currentTheme.inverseOnSurface?.toComposeColor()
            ?: currentTheme.surface.toComposeColor(),

        error = currentTheme.error.toComposeColor(),
        onError = currentTheme.onError.toComposeColor(),
        errorContainer = currentTheme.errorContainer.toComposeColor(),
        onErrorContainer = currentTheme.onErrorContainer.toComposeColor(),

        outline = currentTheme.outline.toComposeColor(),
        outlineVariant = currentTheme.outlineVariant?.toComposeColor()
            ?: currentTheme.outline.toComposeColor().copy(alpha = 0.55f),

        scrim = currentTheme.scrim?.toComposeColor() ?: Color.Black,

        surfaceDim = currentTheme.surfaceVariant.toComposeColor().copy(alpha = 0.87f),
        surfaceBright = currentTheme.surface.toComposeColor().copy(alpha = 1.0f),
        surfaceContainer = currentTheme.surfaceVariant.toComposeColor().copy(alpha = 0.94f),
        surfaceContainerHigh = currentTheme.surfaceVariant.toComposeColor().copy(alpha = 0.92f),
        surfaceContainerHighest = currentTheme.surfaceVariant.toComposeColor().copy(alpha = 1.0f),
        surfaceContainerLow = currentTheme.surfaceVariant.toComposeColor().copy(alpha = 0.38f),
        surfaceContainerLowest = currentTheme.surfaceVariant.toComposeColor().copy(alpha = 0.12f),
    )

    val baseRadius = CompactButtonDefaults.CornerRadius

    val shapes = Shapes(
        extraSmall = RoundedCornerShape(baseRadius * 0.5f), // 4dp / 11dp
        small = RoundedCornerShape(baseRadius),              // 8dp / 22dp - main radius  
        medium = RoundedCornerShape(baseRadius * 1.5f),     // 12dp / 33dp
        large = RoundedCornerShape(baseRadius * 2f),        // 16dp / 44dp
        extraLarge = RoundedCornerShape(baseRadius * 3f)    // 24dp / 66dp
    )

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = shapes,
        content = content
    )
}

// Keep existing compact components for backward compatibility
object CompactButtonDefaults {
    val ContentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    val CornerRadius = 8.dp
}

@Composable
fun OptionalTooltip(
    tooltip: String?,
    monospace: Boolean = false,
    noWrap: Boolean = false,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = if (tooltip != null) {
            {
                Surface(
                    modifier = Modifier.wrapContentSize(),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    tonalElevation = 4.dp
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = tooltip,
                            fontFamily = if (monospace) androidx.compose.ui.text.font.FontFamily.Monospace else androidx.compose.ui.text.font.FontFamily.Default,
                            softWrap = !noWrap,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
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
    shape: Shape = MaterialTheme.shapes.small, // Default to our design system radius
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CompactButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    tooltip: String? = null, // Additional parameter for tooltip support
    tooltipMonospace: Boolean = false, // Use monospace font for tooltip
    tooltipNoWrap: Boolean = false, // Disable line wrapping in tooltip
    content: @Composable RowScope.() -> Unit,
) {
    OptionalTooltip(tooltip, monospace = tooltipMonospace, noWrap = tooltipNoWrap) {
        Button(
            onClick = onClick,
            modifier = modifier, // Removed fixed height for global UI scaling compatibility
            enabled = enabled,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            contentPadding = contentPadding,
            interactionSource = interactionSource,
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
        shape = MaterialTheme.shapes.small,
        content = content
    )
}

@Composable
fun GromozekaMarkdown(
    content: String,
    modifier: Modifier = Modifier,
) {
    val bodyStyle = MaterialTheme.typography.bodyMedium

    Markdown(
        content = content,
        modifier = modifier,
        typography = markdownTypography(
            h1 = MaterialTheme.typography.headlineLarge,
            h2 = MaterialTheme.typography.headlineMedium,
            h3 = MaterialTheme.typography.headlineSmall,
            h4 = MaterialTheme.typography.titleLarge,
            h5 = MaterialTheme.typography.titleMedium,
            h6 = MaterialTheme.typography.titleSmall,
            text = bodyStyle,
            paragraph = bodyStyle,
            ordered = bodyStyle,
            bullet = bodyStyle,
            list = bodyStyle,
            textLink = TextLinkStyles(
                style = bodyStyle.copy(
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline,
                ).toSpanStyle(),
            ),
        ),
        padding = markdownPadding(
            block = 0.dp
        )
    )
}

// === Custom Segmented Button Group using CompactButton ===

@Composable
fun CustomSegmentedButtonGroup(
    options: List<SegmentedButtonOption>,
    selectedIndex: Int,
    onSelectionChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Извлекаем радиус из темы для консистентности
    val cornerRadius = CompactButtonDefaults.CornerRadius // 8.dp, синхронизирован с MaterialTheme.shapes.small

    Row(
        modifier = modifier, // .height(CompactButtonDefaults.ButtonHeight), // Removed: conflicts with global UI scaling
        horizontalArrangement = Arrangement.Start
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selectedIndex
            val isFirst = index == 0
            val isLast = index == options.size - 1
            val isSingle = options.size == 1

            // Определяем форму кнопки используя глобальные shapes
            val shape = when {
                isSingle -> MaterialTheme.shapes.small
                isFirst -> RoundedCornerShape(
                    topStart = cornerRadius,
                    bottomStart = cornerRadius,
                    topEnd = 0.dp,
                    bottomEnd = 0.dp
                )

                isLast -> RoundedCornerShape(
                    topStart = 0.dp,
                    bottomStart = 0.dp,
                    topEnd = cornerRadius,
                    bottomEnd = cornerRadius
                )

                else -> RoundedCornerShape(0.dp)
            }

            OptionalTooltip(option.tooltip) {
                Button(
                    onClick = { onSelectionChange(index) },
                    modifier = Modifier, // .height(CompactButtonDefaults.ButtonHeight), // Removed: conflicts with global UI scaling
                    contentPadding = CompactButtonDefaults.ContentPadding,
                    shape = shape,
                    colors = if (isSelected) {
                        // Активная кнопка - стандартные цвета
                        ButtonDefaults.buttonColors()
                    } else {
                        // Неактивная кнопка - фон как у окна
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    border = if (!isSelected) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    } else null
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = option.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}


data class SegmentedButtonOption(
    val text: String,
    val tooltip: String? = null,
)

// === Toggle Button Group (for independent toggles, not radio buttons) ===

data class ToggleButtonOption(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tooltip: String? = null,
)

@Composable
fun ToggleButtonGroup(
    options: List<ToggleButtonOption>,
    selectedIndices: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cornerRadius = CompactButtonDefaults.CornerRadius

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Start
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index in selectedIndices
            val isFirst = index == 0
            val isLast = index == options.size - 1
            val isSingle = options.size == 1

            val shape = when {
                isSingle -> MaterialTheme.shapes.small
                isFirst -> RoundedCornerShape(
                    topStart = cornerRadius,
                    bottomStart = cornerRadius,
                    topEnd = 0.dp,
                    bottomEnd = 0.dp
                )

                isLast -> RoundedCornerShape(
                    topStart = 0.dp,
                    bottomStart = 0.dp,
                    topEnd = cornerRadius,
                    bottomEnd = cornerRadius
                )

                else -> RoundedCornerShape(0.dp)
            }

            OptionalTooltip(option.tooltip) {
                Button(
                    onClick = { onToggle(index) },
                    modifier = Modifier,
                    contentPadding = CompactButtonDefaults.ContentPadding,
                    shape = shape,
                    colors = if (isSelected) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    border = if (!isSelected) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    } else null
                ) {
                    Icon(
                        option.icon,
                        contentDescription = option.tooltip
                    )
                }
            }
        }
    }
}
