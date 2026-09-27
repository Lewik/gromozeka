package com.gromozeka.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.services.theming.data.DarkTheme
import com.gromozeka.presentation.services.theming.data.Theme
import org.jetbrains.compose.resources.DrawableResource

@Composable
fun GromozekaTheme(
    currentTheme: Theme = DarkTheme(),
    density: UiDensity = UiDensity.COMPACT,
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

        primaryFixed = Color.Unspecified,
        primaryFixedDim = Color.Unspecified,
        onPrimaryFixed = Color.Unspecified,
        onPrimaryFixedVariant = Color.Unspecified,
        secondaryFixed = Color.Unspecified,
        secondaryFixedDim = Color.Unspecified,
        onSecondaryFixed = Color.Unspecified,
        onSecondaryFixedVariant = Color.Unspecified,
        tertiaryFixed = Color.Unspecified,
        tertiaryFixedDim = Color.Unspecified,
        onTertiaryFixed = Color.Unspecified,
        onTertiaryFixedVariant = Color.Unspecified,
    )

    val baseRadius = CompactButtonDefaults.CornerRadius

    val shapes = Shapes(
        extraSmall = RoundedCornerShape(baseRadius * 0.5f), // 4dp / 11dp
        small = RoundedCornerShape(baseRadius),              // 8dp / 22dp - main radius  
        medium = RoundedCornerShape(baseRadius * 1.5f),     // 12dp / 33dp
        large = RoundedCornerShape(baseRadius * 2f),        // 16dp / 44dp
        extraLarge = RoundedCornerShape(baseRadius * 3f)    // 24dp / 66dp
    )

    val controls = when (density) {
        UiDensity.COMPACT -> ControlMetrics.Compact
        UiDensity.TOUCH -> ControlMetrics.Touch
    }
    CompositionLocalProvider(
        LocalUiSpacing provides UiSpacing(),
        LocalControlMetrics provides controls,
        LocalMinimumInteractiveComponentSize provides controls.minHeight,
        LocalContentColor provides colorScheme.onBackground,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapes,
            content = content,
        )
    }
}

// Shared defaults for compact controls
object CompactButtonDefaults {
    val ContentPadding: PaddingValues
        @Composable get() = PaddingValues(
            horizontal = GromozekaTheme.controls.horizontalPadding,
            vertical = GromozekaTheme.controls.verticalPadding,
        )
    val CornerRadius = 8.dp

    @Composable
    fun tonalColors(selected: Boolean = false): ButtonColors = ButtonDefaults.buttonColors(
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun OptionalTooltip(
    tooltip: String?,
    monospace: Boolean = false,
    noWrap: Boolean = false,
    content: @Composable () -> Unit,
) {
    // An empty TooltipBox still creates a popup on hover, with its own semantics owner.
    if (tooltip == null || tooltip.isBlank()) {
        content()
        return
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
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
            modifier = modifier.defaultMinSize(minHeight = GromozekaTheme.controls.minHeight),
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


/** Square secondary action using the same metrics and styling as text buttons. */
@Composable
fun CompactIconButton(
    onClick: () -> Unit,
    icon: DrawableResource,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltip: String? = contentDescription,
    colors: ButtonColors = CompactButtonDefaults.tonalColors(),
) {
    CompactButton(
        onClick = onClick,
        modifier = modifier.size(GromozekaTheme.controls.minHeight),
        enabled = enabled,
        tooltip = tooltip,
        colors = colors,
        elevation = null,
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(icon, contentDescription, Modifier.size(GromozekaTheme.controls.iconSize))
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

/** Adjacent buttons share straight inner edges and retain the theme's outer corners. */
@Composable
internal fun joinedButtonShape(index: Int, count: Int): CornerBasedShape {
    val shape = MaterialTheme.shapes.small
    val square = CornerSize(0.dp)
    return shape.copy(
        topStart = if (index == 0) shape.topStart else square,
        bottomStart = if (index == 0) shape.bottomStart else square,
        topEnd = if (index == count - 1) shape.topEnd else square,
        bottomEnd = if (index == count - 1) shape.bottomEnd else square,
    )
}

@Composable
fun CustomSegmentedButtonGroup(
    options: List<SegmentedButtonOption>,
    selectedIndex: Int,
    onSelectionChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.Start) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selectedIndex
            CompactButton(
                onClick = { onSelectionChange(index) },
                shape = joinedButtonShape(index, options.size),
                tooltip = option.tooltip,
                colors = if (isSelected) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(option.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

data class SegmentedButtonOption(
    val text: String,
    val tooltip: String? = null,
)

// Independent toggles, not radio buttons.
data class ToggleButtonOption(
    val icon: DrawableResource,
    val tooltip: String? = null,
)

@Composable
fun ToggleButtonGroup(
    options: List<ToggleButtonOption>,
    selectedIndices: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.Start) {
        options.forEachIndexed { index, option ->
            val isSelected = index in selectedIndices
            CompactButton(
                onClick = { onToggle(index) },
                modifier = Modifier.size(GromozekaTheme.controls.minHeight),
                contentPadding = PaddingValues(0.dp),
                shape = joinedButtonShape(index, options.size),
                tooltip = option.tooltip,
                colors = if (isSelected) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Icon(option.icon, contentDescription = option.tooltip)
            }
        }
    }
}
