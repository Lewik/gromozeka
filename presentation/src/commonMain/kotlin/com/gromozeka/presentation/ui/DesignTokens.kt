package com.gromozeka.presentation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Interaction density, independent of color theme, window width and display scaling. */
enum class UiDensity { COMPACT, TOUCH }

@Immutable
data class UiSpacing(
    val controlGap: Dp = 4.dp,
    val rowGap: Dp = 8.dp,
    val panelPadding: Dp = 8.dp,
    val contentPadding: Dp = 16.dp,
)

@Immutable
data class ControlMetrics(
    val minHeight: Dp,
    val verticalPadding: Dp,
    val horizontalPadding: Dp = 12.dp,
    val iconSize: Dp = 20.dp,
    val smallIconSize: Dp = 16.dp,
) {
    companion object {
        val Compact = ControlMetrics(minHeight = 40.dp, verticalPadding = 8.dp)
        val Touch = ControlMetrics(minHeight = 48.dp, verticalPadding = 12.dp)
    }
}

internal val LocalUiSpacing = staticCompositionLocalOf { UiSpacing() }
internal val LocalControlMetrics = staticCompositionLocalOf { ControlMetrics.Compact }

/** Layout tokens complement MaterialTheme's colors, typography and shapes. */
object GromozekaTheme {
    val spacing: UiSpacing
        @Composable @ReadOnlyComposable get() = LocalUiSpacing.current

    val controls: ControlMetrics
        @Composable @ReadOnlyComposable get() = LocalControlMetrics.current
}
