package com.gromozeka.presentation.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun ClientStartupLoadingScreen() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().testTag("client-startup-loading"), contentAlignment = Alignment.Center) {
            GromozekaLoadingIndicator(Modifier.size(80.dp).testTag("client-startup-logo"))
        }
    }
}

/** The bulb stays still; only a transparent gap travels around its orange outline. */
@Composable
internal fun GromozekaLoadingIndicator(modifier: Modifier = Modifier, animated: Boolean = true) {
    val phase = if (animated) rememberGromozekaLoadingPhase() else null

    GromozekaBulb(
        modifier = modifier.size(28.dp).progressSemantics(),
        // Read the animation only while drawing, never during composition or layout.
        phase = { phase?.value },
    )
}

/** Static readiness mark: the original bulb with a two-stroke green check inside its head. */
@Composable
internal fun GromozekaReadyIndicator(modifier: Modifier = Modifier) {
    GromozekaBulb(modifier.size(28.dp), phase = { null }, ready = true)
}

@Composable
private fun rememberGromozekaLoadingPhase() =
    rememberInfiniteTransition(label = "Gromozeka loader").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart),
        label = "Outline gap",
    )

/** Lightweight header version: orange ring only, animated for every working conversation. */
@Composable
internal fun GromozekaTabLoadingIndicator(modifier: Modifier = Modifier) {
    val phase = rememberGromozekaLoadingPhase()
    Spacer(modifier.size(14.dp).progressSemantics().graphicsLayer().drawWithCache {
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        val diameter = size.minDimension - stroke.width
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        onDrawBehind {
            drawArc(Color(0xFFFE8B17), startAngle = phase.value * 360f - 90f, sweepAngle = 302.4f,
                useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        }
    })
}

@Composable
internal fun GromozekaBulb(modifier: Modifier, phase: () -> Float?, ready: Boolean = false) {
    Spacer(modifier.graphicsLayer().drawWithCache {
        val outline = PathParser().parsePathString(BulbOutline).toPath()
        val base = PathParser().parsePathString(BulbBase).toPath()
        val readyCheck = if (ready) Path().apply {
            moveTo(416f, 392f)
            lineTo(482f, 458f)
            lineTo(608f, 326f)
        } else null
        val length = PathMeasure().apply { setPath(outline, forceClosed = true) }.length
        val intervals = floatArrayOf(length * 0.84f, length * 0.16f)
        // Outline, base and ready check deliberately share the exact same stroke.
        val stroke = Stroke(width = 48f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Crop the empty margins of the source SVG, preserving the logo's proportions.
        val scale = size.minDimension / 800f
        val left = (size.width - size.minDimension) / 2f - 112f * scale
        val top = (size.height - size.minDimension) / 2f - 112f * scale

        onDrawBehind {
            val offset = phase()
            val outlineStroke = if (offset == null) stroke else Stroke(
                width = stroke.width,
                cap = stroke.cap,
                join = stroke.join,
                pathEffect = PathEffect.dashPathEffect(intervals, phase = offset * length),
            )
            withTransform({
                translate(left, top)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                drawPath(outline, Color(0xFFFE8B17), style = outlineStroke)
                drawPath(base, Color(0xFF1266D0), style = stroke)
                readyCheck?.let { drawPath(it, Color(0xFF43A047), style = stroke) }
            }
        }
    })
}

// Paths from the visible "base" layer of jvmMain/resources/logo.svg.
// Kept as vector geometry: no bitmap frames, masking, SVG parsing or path measurement per frame.
private const val BulbOutline =
    "M512.89258,140.50391 a252,252 0 0 0 -236.80274,165.81054 " +
        "a252,252 0 0 0 74.82032,279.23242 " +
        "c29.16488,25.00535 40.63285,62.50383 34.40234,112.49415 h255.16016 " +
        "c-6.23051,-49.99032 5.23746,-87.4888 34.40234,-112.49415 " +
        "A252,252 0 0 0 749.69531,306.31445 A252,252 0 0 0 512.89258,140.50391 Z"

private const val BulbBase =
    "M381.79543,767.99224 H636.95559 M411.59044,837.94346 H607.16058 " +
        "M450.78705,874.92883 H567.96397 " +
        "M450.78705,874.92883 C445.76053,853.94346 432.69499,841.615 411.59044,837.94346 " +
        "m161.62251,36.98537 c5.02652,-20.98537 18.09206,-33.31383 39.19661,-36.98537"
