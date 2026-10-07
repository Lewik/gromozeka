package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.graphics.shadow.InnerShadowPainter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gromozeka.presentation.ui.LocalTranslation
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import kotlin.math.roundToInt

internal const val VISUAL_INLINE_HIGHLIGHT = "gromozeka-visual-highlight"

/** A soft contour halo: no hard border and no guessed surface color below the element. */
private fun Modifier.visualHighlightGlow(highlighted: Boolean, shape: Shape, bounded: Boolean): Modifier = if (!highlighted) this else drawWithCache {
    val cutout = Path().apply {
        when (val outline = shape.createOutline(size, layoutDirection, this@drawWithCache)) {
            is Outline.Rectangle -> addRect(outline.rect)
            is Outline.Rounded -> addRoundRect(outline.roundRect)
            is Outline.Generic -> addPath(outline.path)
        }
    }
    val glow = DropShadowPainter(shape,
        Shadow(radius = 7.dp, spread = 1.dp, color = Color.White.copy(alpha = 0.4f)))
    // Native tab rows clip at their top/bottom edges. Keep a soft part of the halo
    // inside those bounds too, without adding padding or shifting the tab indicator.
    val insetGlow = if (bounded) InnerShadowPainter(shape,
        Shadow(radius = 7.dp, color = Color.White.copy(alpha = 0.25f))) else null
    onDrawWithContent {
        // A regular filled drop shadow would wash out transparent containers and text.
        clipPath(cutout, ClipOp.Difference) { with(glow) { draw(size) } }
        drawContent()
        insetGlow?.let { with(it) { draw(size) } }
    }
}

/** Always keep the same anchor composition: toggling attention must not reset input/selection. */
@Composable
internal fun VisualHighlightContainer(
    highlighted: Boolean,
    elementId: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    boundedGlow: Boolean = false,
    content: @Composable () -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BadgedBox(
        modifier = modifier.wrapContentSize(Alignment.TopStart).zIndex(if (highlighted) 1f else 0f),
        badge = {
            if (highlighted) VisualHighlightDismissBadge(
                onClear,
                Modifier.offset(x = (-6).dp, y = 4.dp).testTag("visual-highlight-$elementId"),
                pointRight = rtl,
            )
        },
    ) {
        Box(Modifier.visualHighlightGlow(highlighted, shape, boundedGlow)) { content() }
    }
}

/** The pointer consumes its own click; it must not submit a button or follow a link below it. */
@Composable
internal fun VisualHighlightDismissBadge(
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    pointRight: Boolean = false,
) {
    val label = LocalTranslation.current.text("visuals.clearHighlights")
    Box(
        modifier.requiredSize(18.dp).zIndex(1f)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClear)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.SouthWest, contentDescription = null,
            modifier = Modifier.size(14.dp).graphicsLayer { scaleX = if (pointRight) -1f else 1f },
            tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/** Inline spans share a Text composable, so place pointers using text layout rather than new rows. */
@Composable
internal fun VisualAnnotatedText(
    text: AnnotatedString,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val badgeSize = with(LocalDensity.current) { 18.dp.toPx() }
    val ranges = text.getStringAnnotations(VISUAL_INLINE_HIGHLIGHT, 0, text.length)
    Box(modifier) {
        Text(text, style = style, color = color, onTextLayout = { layout = it })
        // Text decorates link annotations internally; compare characters, not annotations.
        val measured = layout?.takeIf { it.layoutInput.text.text == text.text }
        if (measured != null && text.isNotEmpty() && ranges.isNotEmpty()) {
            Box(Modifier.matchParentSize()) {
                ranges.distinctBy { it.item }.forEach { range ->
                    val at = (range.end - 1).coerceIn(0, text.lastIndex)
                    val bounds = measured.getBoundingBox(at)
                    val x = (bounds.right - badgeSize).coerceIn(0f, (measured.size.width - badgeSize).coerceAtLeast(0f))
                    val y = bounds.top.coerceIn(0f, (measured.size.height - badgeSize).coerceAtLeast(0f))
                    VisualHighlightDismissBadge(onClear,
                        Modifier.absoluteOffset { IntOffset(x.roundToInt(), y.roundToInt()) }
                            .testTag("visual-highlight-${range.item}"))
                }
            }
        }
    }
}
