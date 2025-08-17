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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.gromozeka.bot.services.theming.ThemeService

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow

@Composable
fun GromozekaTheme(
    themeService: ThemeService? = null,
    content: @Composable () -> Unit
) {
    // Get current theme from service, fallback to default dark theme if service not available
    val currentTheme by (themeService?.currentTheme?.collectAsState()
        ?: androidx.compose.runtime.mutableStateOf(com.gromozeka.bot.services.theming.data.DarkTheme()))
    
    // Create ColorScheme from current theme data
    val colorScheme = ColorScheme(
        primary = currentTheme.primary.toComposeColor(),
        onPrimary = currentTheme.onPrimary.toComposeColor(),
        primaryContainer = currentTheme.primaryContainer.toComposeColor(),
        onPrimaryContainer = currentTheme.onPrimaryContainer.toComposeColor(),
        inversePrimary = currentTheme.inversePrimary?.toComposeColor() ?: Color(0xFFBEA6FF),
        
        secondary = currentTheme.secondary.toComposeColor(),
        onSecondary = currentTheme.onSecondary.toComposeColor(),
        secondaryContainer = currentTheme.secondaryContainer.toComposeColor(),
        onSecondaryContainer = currentTheme.onSecondaryContainer.toComposeColor(),
        
        tertiary = currentTheme.tertiary?.toComposeColor() ?: Color(0xFF6750A4),
        onTertiary = currentTheme.onTertiary?.toComposeColor() ?: Color(0xFFFFFFFF),
        tertiaryContainer = currentTheme.tertiaryContainer?.toComposeColor() ?: Color(0xFFEADDFF),
        onTertiaryContainer = currentTheme.onTertiaryContainer?.toComposeColor() ?: Color(0xFF21005D),
        
        background = currentTheme.background.toComposeColor(),
        onBackground = currentTheme.onBackground.toComposeColor(),
        surface = currentTheme.surface.toComposeColor(),
        onSurface = currentTheme.onSurface.toComposeColor(),
        surfaceVariant = currentTheme.surfaceVariant.toComposeColor(),
        onSurfaceVariant = currentTheme.onSurfaceVariant.toComposeColor(),
        surfaceTint = currentTheme.primary.toComposeColor(), // Use primary as surface tint
        inverseSurface = currentTheme.inverseSurface?.toComposeColor() ?: Color(0xFF313033),
        inverseOnSurface = currentTheme.inverseOnSurface?.toComposeColor() ?: Color(0xFFF4EFF4),
        
        error = currentTheme.error.toComposeColor(),
        onError = currentTheme.onError.toComposeColor(),
        errorContainer = currentTheme.errorContainer.toComposeColor(),
        onErrorContainer = currentTheme.onErrorContainer.toComposeColor(),
        
        outline = currentTheme.outline.toComposeColor(),
        outlineVariant = currentTheme.outlineVariant?.toComposeColor() ?: Color(0xFFCAC4D0),
        scrim = currentTheme.scrim?.toComposeColor() ?: Color(0xFF000000),
        
        // Additional required surface colors - use calculated variants
        surfaceBright = currentTheme.surface.toComposeColor().copy(alpha = 0.87f),
        surfaceDim = currentTheme.surface.toComposeColor().copy(alpha = 0.12f),
        surfaceContainer = currentTheme.surfaceVariant.toComposeColor(),
        surfaceContainerHigh = currentTheme.surfaceVariant.toComposeColor().copy(alpha = 0.87f),
        surfaceContainerHighest = currentTheme.surfaceVariant.toComposeColor().copy(alpha = 1.0f),
        surfaceContainerLow = currentTheme.surfaceVariant.toComposeColor().copy(alpha = 0.38f),
        surfaceContainerLowest = currentTheme.surfaceVariant.toComposeColor().copy(alpha = 0.12f),
    )
    val compactTypography = Typography(
        // Headers - максимум 24sp, пропорционально уменьшается
        displayLarge = MaterialTheme.typography.displayLarge.copy(fontSize = 24.sp, lineHeight = 28.sp),
        displayMedium = MaterialTheme.typography.displayMedium.copy(fontSize = 22.sp, lineHeight = 26.sp),
        displaySmall = MaterialTheme.typography.displaySmall.copy(fontSize = 20.sp, lineHeight = 24.sp),

        headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontSize = 20.sp, lineHeight = 24.sp),
        headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontSize = 18.sp, lineHeight = 22.sp),
        headlineSmall = MaterialTheme.typography.headlineSmall.copy(fontSize = 16.sp, lineHeight = 20.sp),

        titleLarge = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, lineHeight = 20.sp),
        titleMedium = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
        titleSmall = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, lineHeight = 17.sp),

        // Body text - основа 12sp
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp, lineHeight = 16.sp),
        bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        bodySmall = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),

        // Labels - 11sp как есть
        labelLarge = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, lineHeight = 15.sp),
        labelMedium = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 15.sp),
        labelSmall = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = compactTypography,
        content = content
    )
}

// Compact Button Defaults
object CompactButtonDefaults {
    val ContentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
    val ButtonHeight = 28.dp
    val CornerRadius = 6.dp
    val SwitchScale = 0.8f
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
fun CompactSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.scale(CompactButtonDefaults.SwitchScale),
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource
    )
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

@Composable
fun GromozekaMarkdown(
    content: String,
    modifier: Modifier = Modifier,
) {
    Markdown(
        content = content,
        typography = markdownTypography(
            h1 = MaterialTheme.typography.displayMedium,    // 22sp (было 24sp)
            h2 = MaterialTheme.typography.displaySmall,     // 20sp (было 22sp)  
            h3 = MaterialTheme.typography.headlineMedium,   // 18sp (было 20sp)
            h4 = MaterialTheme.typography.headlineSmall,    // 16sp (было 18sp)
            h5 = MaterialTheme.typography.titleMedium,      // 14sp (было 16sp)
            h6 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), // 12sp жирный
            text = MaterialTheme.typography.bodyMedium,     // 12sp
            paragraph = MaterialTheme.typography.bodyMedium, // 12sp
            code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), // 12sp mono
            inlineCode = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) // 12sp mono
        ),
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
    Row(
        modifier = modifier.height(CompactButtonDefaults.ButtonHeight),
        horizontalArrangement = Arrangement.Start
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selectedIndex
            val isFirst = index == 0
            val isLast = index == options.size - 1
            val isSingle = options.size == 1
            
            // Определяем форму кнопки
            val shape = when {
                isSingle -> RoundedCornerShape(CompactButtonDefaults.CornerRadius)
                isFirst -> RoundedCornerShape(
                    topStart = CompactButtonDefaults.CornerRadius,
                    bottomStart = CompactButtonDefaults.CornerRadius,
                    topEnd = 0.dp,
                    bottomEnd = 0.dp
                )
                isLast -> RoundedCornerShape(
                    topStart = 0.dp,
                    bottomStart = 0.dp, 
                    topEnd = CompactButtonDefaults.CornerRadius,
                    bottomEnd = CompactButtonDefaults.CornerRadius
                )
                else -> RoundedCornerShape(0.dp)
            }
            
            OptionalTooltip(option.tooltip) {
                Button(
                    onClick = { onSelectionChange(index) },
                    modifier = Modifier.height(CompactButtonDefaults.ButtonHeight),
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

// === Old Segmented Button Group using Material3 built-in components ===

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedButtonGroup(
    options: List<SegmentedButtonOption>,
    selectedIndex: Int,
    onSelectionChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.height(CompactButtonDefaults.ButtonHeight)
    ) {
        options.forEachIndexed { index, option ->
            OptionalTooltip(option.tooltip) {
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size,
                        baseShape = RoundedCornerShape(CompactButtonDefaults.CornerRadius)
                    ),
                    onClick = { onSelectionChange(index) },
                    selected = index == selectedIndex,
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                        activeBorderColor = MaterialTheme.colorScheme.primary,
                        inactiveBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.height(CompactButtonDefaults.ButtonHeight)
                ) {
                    Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
            Row(
                Modifier.defaultMinSize(
                        minWidth = ButtonDefaults.MinWidth,
                        minHeight = ButtonDefaults.MinHeight
                    )
                    .padding(CompactButtonDefaults.ContentPadding),

                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,

            ) {
                     Text(
                        text = option.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
        }

                }
                }
            }
        }
    }
}

data class SegmentedButtonOption(
    val text: String,
    val tooltip: String? = null
)