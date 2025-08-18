package com.gromozeka.bot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.bot.services.theming.ThemeService
import com.mikepenz.markdown.m3.Markdown

@Composable
fun GromozekaTheme(
    themeService: ThemeService? = null,
    content: @Composable () -> Unit,
) {
    // Get current theme from service, fallback to default dark theme if service not available
    val currentTheme by (themeService?.currentTheme?.collectAsState()
        ?: androidx.compose.runtime.mutableStateOf(com.gromozeka.bot.services.theming.data.DarkTheme()))

    // Create basic ColorScheme from current theme data - only required fields for now
    val colorScheme = ColorScheme(
        primary = currentTheme.primary.toComposeColor(),
        onPrimary = currentTheme.onPrimary.toComposeColor(),
        primaryContainer = currentTheme.primaryContainer.toComposeColor(),
        onPrimaryContainer = currentTheme.onPrimaryContainer.toComposeColor(),
        inversePrimary = Color(0xFFBEA6FF), // fallback

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
        inverseSurface = Color(0xFF313030),
        inverseOnSurface = Color(0xFFF1F0F0),

        error = currentTheme.error.toComposeColor(),
        onError = currentTheme.onError.toComposeColor(),
        errorContainer = currentTheme.errorContainer.toComposeColor(),
        onErrorContainer = currentTheme.onErrorContainer.toComposeColor(),

        outline = currentTheme.outline.toComposeColor(),
        outlineVariant = Color(0xFF444746),

        scrim = Color(0xFF000000),

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionalTooltip(
    tooltip: String?,
    content: @Composable () -> Unit,
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
    shape: Shape = MaterialTheme.shapes.small, // Default to our design system radius
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CompactButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    tooltip: String? = null, // Additional parameter for tooltip support
    content: @Composable RowScope.() -> Unit,
) {
    OptionalTooltip(tooltip) {
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
    Markdown(
        content = content,
        modifier = modifier
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