package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.LocalTranslation

internal val sidePanelResizeHandleWidth = 8.dp

/** One preferred width for all sidebar tabs; window constraints never overwrite it. */
@Stable
internal class ConversationSidePanelResizeState {
    private var preferredWidth by mutableStateOf<Dp?>(null)

    fun width(availableWidth: Dp): Dp = constrain(availableWidth, preferredWidth ?: conversationSidePanelWidth(availableWidth))

    fun dragBy(availableWidth: Dp, delta: Dp) {
        preferredWidth = constrain(availableWidth, width(availableWidth) - delta)
    }

    fun reset() { preferredWidth = null }

    private fun constrain(availableWidth: Dp, requested: Dp): Dp {
        require(availableWidth.value.isFinite() && availableWidth >= 0.dp)
        require(requested.value.isFinite())
        val minimum = minOf(320.dp, availableWidth / 2)
        val maximum = maxOf(minimum, availableWidth - 360.dp)
        return requested.coerceIn(minimum, maximum)
    }
}

@Composable
internal fun SidePanelResizeHandle(modifier: Modifier, onReset: () -> Unit, onDrag: (Float) -> Unit) {
    val reset by rememberUpdatedState(onReset)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dragging by interaction.collectIsDraggedAsState()
    val color = if (hovered || dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val label = LocalTranslation.current.text("session.sidebar.resize")
    val cursor = remember { horizontalResizePointerIcon() }
    Box(modifier
        .testTag("side-panel-resize-handle")
        .semantics { contentDescription = label }
        .pointerHoverIcon(cursor)
        .hoverable(interaction)
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) }
        .draggable(
            state = rememberDraggableState(onDrag),
            orientation = Orientation.Horizontal,
            interactionSource = interaction,
            reverseDirection = LocalLayoutDirection.current == LayoutDirection.Rtl,
        )
        .drawBehind {
            drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height),
                if (hovered || dragging) 2.dp.toPx() else 1.dp.toPx())
        })
}

internal expect fun horizontalResizePointerIcon(): PointerIcon
