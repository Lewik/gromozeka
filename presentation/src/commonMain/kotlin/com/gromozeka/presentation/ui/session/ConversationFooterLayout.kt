package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.GromozekaTheme

@Composable
internal fun composerEditorRowHeight(): Dp {
    val controls = GromozekaTheme.controls
    val line = rememberTextMeasurer().measure("Hg", style = MaterialTheme.typography.bodyLarge, softWrap = false).size.height
    return with(LocalDensity.current) { maxOf(controls.minHeight, line.toDp() + controls.verticalPadding * 2) }
}

@Composable
internal fun composerInputRowHeight(): Dp =
    composerEditorRowHeight() + GromozekaTheme.spacing.panelPadding * 2 + 1.dp

/** Growing input takes height from chat, not from the independent right-hand panel. */
@Composable
internal fun ConversationWorkspaceLayout(
    summaryWidth: Dp,
    showSidePanel: Boolean,
    narrow: Boolean,
    modifier: Modifier = Modifier,
    onResize: ((Float) -> Unit)? = null,
    onResetWidth: (() -> Unit)? = null,
    chat: @Composable () -> Unit,
    sidePanel: @Composable () -> Unit,
    composer: @Composable () -> Unit,
    summary: @Composable () -> Unit,
) {
    require((onResize == null) == (onResetWidth == null)) { "Resizable sidebar requires both drag and reset handlers" }
    val inputBaseHeight = composerInputRowHeight()
    val border = MaterialTheme.colorScheme.outlineVariant
    Layout(
        content = {
            Box(Modifier.clipToBounds()) { chat() }
            Box(Modifier.clipToBounds()) { if (showSidePanel) sidePanel() }
            Box(Modifier.clipToBounds(), contentAlignment = Alignment.BottomStart) { composer() }
            Surface(Modifier.testTag("conversation-footer"), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
                Box(Modifier.clipToBounds().drawBehind {
                    drawLine(border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
                    drawLine(border, Offset.Zero, Offset(0f, size.height), 1.dp.toPx())
                }, contentAlignment = Alignment.CenterStart) { summary() }
            }
            if (!narrow && showSidePanel && onResize != null) SidePanelResizeHandle(Modifier, checkNotNull(onResetWidth), onResize)
        },
        modifier = modifier,
    ) { children, constraints ->
        require(constraints.hasBoundedWidth && constraints.hasBoundedHeight)
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (narrow) {
            val summaryPlaceable = children[3].measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
            val input = children[2].measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height - summaryPlaceable.height))
            val chatHeight = height - summaryPlaceable.height - input.height
            val chatPlaceable = children[0].measure(Constraints.fixed(width, chatHeight))
            val panel = children[1].measure(Constraints.fixed(if (showSidePanel) width else 0, chatHeight))
            layout(width, height) {
                summaryPlaceable.placeRelative(0, 0)
                chatPlaceable.placeRelative(0, summaryPlaceable.height)
                if (showSidePanel) panel.placeRelative(0, summaryPlaceable.height)
                input.placeRelative(0, height - input.height)
            }
        } else {
            val rightWidth = summaryWidth.roundToPx().coerceIn(0, width)
            val leftWidth = width - rightWidth
            val summaryPlaceable = children[3].measure(Constraints(
                minWidth = rightWidth, maxWidth = rightWidth,
                minHeight = inputBaseHeight.roundToPx().coerceAtMost(height), maxHeight = height,
            ))
            val input = children[2].measure(Constraints(
                minWidth = leftWidth, maxWidth = leftWidth,
                minHeight = inputBaseHeight.roundToPx().coerceAtMost(height), maxHeight = height,
            ))
            val chatBottom = height - if (showSidePanel) input.height else maxOf(input.height, summaryPlaceable.height)
            val chatPlaceable = children[0].measure(Constraints.fixed(if (showSidePanel) leftWidth else width, chatBottom))
            val panel = children[1].measure(Constraints.fixed(if (showSidePanel) rightWidth else 0, height - summaryPlaceable.height))
            val handle = children.getOrNull(4)?.measure(Constraints.fixed(sidePanelResizeHandleWidth.roundToPx().coerceAtMost(width), height))
            layout(width, height) {
                chatPlaceable.placeRelative(0, 0)
                panel.placeRelative(leftWidth, 0)
                input.placeRelative(0, height - input.height)
                summaryPlaceable.placeRelative(leftWidth, height - summaryPlaceable.height)
                handle?.let { it.placeRelative((leftWidth - it.width / 2).coerceIn(0, width - it.width), 0) }
            }
        }
    }
}
