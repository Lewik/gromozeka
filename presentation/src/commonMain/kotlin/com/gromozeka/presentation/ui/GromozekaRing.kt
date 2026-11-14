package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

internal val gromozekaRingStrokeWidth = 1.5.dp

@Composable
internal fun GromozekaRing(
    modifier: Modifier,
    phase: () -> Float?,
    ready: Boolean = false,
    outlineColor: Color,
    errorRayColor: Color? = null,
) {
    Spacer(modifier.graphicsLayer().drawWithCache {
        val stroke = Stroke(gromozekaRingStrokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val radius = (size.minDimension - stroke.width) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val topLeft = center - Offset(radius, radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        val readyCheck = if (ready) Path().apply {
            moveTo(center.x - radius * 0.48f, center.y)
            lineTo(center.x - radius * 0.12f, center.y + radius * 0.36f)
            lineTo(center.x + radius * 0.48f, center.y - radius * 0.36f)
        } else null
        val rayPath = if (errorRayColor != null) Path().apply {
            val startRadius = radius + stroke.width / 2f + 1.5.dp.toPx()
            val endRadius = startRadius + 2.dp.toPx()
            for (degrees in listOf(-180, -135, -90, -45, 0)) {
                val angle = degrees * kotlin.math.PI / 180.0
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                moveTo(center.x + direction.x * startRadius, center.y + direction.y * startRadius)
                lineTo(center.x + direction.x * endRadius, center.y + direction.y * endRadius)
            }
        } else null
        val rayPaint = if (rayPath != null) Paint().apply {
            color = requireNotNull(errorRayColor)
            style = PaintingStyle.Stroke
            strokeWidth = stroke.width
            strokeCap = stroke.cap
        } else null
        onDrawBehind {
            if (rayPath != null && rayPaint != null) drawIntoCanvas { it.drawPath(rayPath, rayPaint) }
            val offset = phase()
            if (offset == null) {
                drawCircle(outlineColor, radius, center, style = stroke)
            } else {
                drawArc(outlineColor, startAngle = offset * 360f - 90f, sweepAngle = 302.4f,
                    useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            }
            readyCheck?.let { drawPath(it, Color(0xFF43A047), style = stroke) }
        }
    })
}
