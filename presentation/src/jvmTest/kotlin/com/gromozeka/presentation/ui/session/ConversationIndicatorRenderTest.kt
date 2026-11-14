package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gromozeka.presentation.ui.GromozekaBulb
import com.gromozeka.presentation.ui.GromozekaRevolutionMillis
import com.gromozeka.presentation.ui.GromozekaTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationIndicatorRenderTest {
    private val ready = ConversationIndicatorState(ConversationActivity.READY, ConversationPressure.NORMAL, false, false, emptySet())

    @Test
    fun readyFullIndicatorPreservesTheOriginalBulbPixels() = runDesktopComposeUiTest {
        setContent { GromozekaTheme { Row(Modifier.background(Color.Black)) {
            GromozekaBulb(Modifier.size(28.dp).testTag("original"), phase = { null }, ready = true)
            ConversationIndicator(ready, ConversationIndicatorDrawing.FULL, "Ready", Modifier.testTag("actual"))
        } } }
        samePixels(onNodeWithTag("original").captureToImage().toPixelMap(), onNodeWithTag("actual").captureToImage().toPixelMap())
    }

    @Test
    fun unreadReplacesCheckAndReadingRestoresItForBothDrawings() = runDesktopComposeUiTest {
        val state = mutableStateOf(ready)
        setContent { IndicatorCells(state.value) }
        val initial = ConversationIndicatorDrawing.entries.associateWith { onNodeWithTag(it.name).captureToImage().toPixelMap() }
        runOnIdle { state.value = ready.copy(unread = true) }
        for (drawing in ConversationIndicatorDrawing.entries) {
            val pixels = onNodeWithTag(drawing.name).captureToImage().toPixelMap()
            assertTrue(count(initial.getValue(drawing), ::green) > 0)
            assertEquals(0, count(pixels, ::green), "Unread must replace, not overlay a green check")
            assertTrue(count(pixels, ::red) > 0)
        }
        runOnIdle { state.value = ready }
        for (drawing in ConversationIndicatorDrawing.entries) samePixels(initial.getValue(drawing), onNodeWithTag(drawing.name).captureToImage().toPixelMap())
    }

    @Test
    fun errorRaysPreserveAllOriginalPixelsAreOpaqueAndFitTheirSlots() = runDesktopComposeUiTest {
        val state = mutableStateOf(ready.copy(pressure = ConversationPressure.LOCAL_WAIT))
        setContent { IndicatorCells(state.value) }
        val initial = ConversationIndicatorDrawing.entries.associateWith { onNodeWithTag("cell-${it.name}").captureToImage().toPixelMap() }
        runOnIdle { state.value = state.value.copy(hasTurnProblem = true) }
        for (drawing in ConversationIndicatorDrawing.entries) {
            val before = initial.getValue(drawing)
            val after = onNodeWithTag("cell-${drawing.name}").captureToImage().toPixelMap()
            val background = before[0, 0]
            var opaque = 0
            val slot = if (drawing == ConversationIndicatorDrawing.FULL) 40 else 24
            val margin = (60 - slot) // Raster density is two.
            for (y in 0 until after.height) for (x in 0 until after.width) {
                if (before[x, y] != background) assertEquals(before[x, y], after[x, y], "$drawing original $x/$y")
                if (x < margin || y < margin || x >= after.width - margin || y >= after.height - margin)
                    assertEquals(background, after[x, y], "$drawing rays outside slot at $x/$y")
                if (after[x, y] == Color(0xFFFF5A64)) opaque++
            }
            assertTrue(opaque > 0, "$drawing needs fully opaque error rays")
            val core = onNodeWithTag(drawing.name).fetchSemanticsNode().boundsInRoot
            assertEquals(drawing.size.value * 2, core.width)
            assertEquals(core.width, core.height)
        }
        runOnIdle { state.value = state.value.copy(hasTurnProblem = false) }
        for (drawing in ConversationIndicatorDrawing.entries) samePixels(initial.getValue(drawing), onNodeWithTag("cell-${drawing.name}").captureToImage().toPixelMap())
    }

    @Test
    fun fiveFullRaysRemainVisibleIncludingTheTopRayAndMatchTheBaseStroke() = runDesktopComposeUiTest(width = 600, height = 600) {
        setContent { CompositionLocalProvider(LocalDensity provides Density(8f)) { GromozekaTheme {
            Box(Modifier.size(60.dp).background(Color(0xFF26313B)).testTag("cell"), contentAlignment = Alignment.Center) {
                ConversationIndicator(ready.copy(pressure = ConversationPressure.LOCAL_WAIT, hasTurnProblem = true), ConversationIndicatorDrawing.FULL, "Problem")
            }
        } } }
        val image = onNodeWithTag("cell").captureToImage().toPixelMap()
        fun redIn(xs: IntRange, ys: IntRange) = ys.sumOf { y -> xs.count { x -> red(image[x, y]) } }
        for ((xs, ys) in listOf(28..32 to 10..15, 18..23 to 14..19, 37..42 to 14..19, 14..19 to 24..28, 41..46 to 24..28))
            assertTrue(redIn(xs.first * 8..xs.last * 8, ys.first * 8..ys.last * 8) > 0, "Missing ray $xs/$ys")
        val background = image[0, 0]
        val rayWidth = (27 * 8 until 33 * 8).sumOf { x -> ((image[x, 100].red - background.red) / (1f - background.red)).toDouble() }
        val baseWidth = (38 * 8 until 40 * 8).sumOf { y -> ((image[240, y].blue - background.blue) / (208f / 255f - background.blue)).toDouble() }
        assertTrue(abs(rayWidth - baseWidth) < 0.1, "Ray $rayWidth differs from base stroke $baseWidth")
    }

    @Test
    fun bothDrawingsMoveOnlyTheirGapWithoutRecompositionOrLayout() = runDesktopComposeUiTest {
        mainClock.autoAdvance = false
        var compositions = 0
        var layouts = 0
        setContent {
            SideEffect { compositions++ }
            Box(Modifier.layout { measurable, constraints ->
                layouts++
                val child = measurable.measure(constraints)
                layout(child.width, child.height) { child.place(0, 0) }
            }) { IndicatorCells(ready.copy(activity = ConversationActivity.WORKING, pressure = ConversationPressure.LOCAL_WAIT, hasTurnProblem = true)) }
        }
        assertEquals(5_000, GromozekaRevolutionMillis)
        mainClock.advanceTimeBy(100)
        val initial = ConversationIndicatorDrawing.entries.associateWith { onNodeWithTag("cell-${it.name}").captureToImage().toPixelMap() }
        val compositionCount = compositions
        val layoutCount = layouts
        mainClock.advanceTimeBy(1100)
        for (drawing in ConversationIndicatorDrawing.entries) {
            val before = initial.getValue(drawing)
            val after = onNodeWithTag("cell-${drawing.name}").captureToImage().toPixelMap()
            var moving = 0
            for (y in 0 until after.height) for (x in 0 until after.width) {
                if (red(before[x, y])) assertEquals(before[x, y], after[x, y], "Error rays must remain stationary")
                if (before[x, y] != after[x, y]) moving++
            }
            assertTrue(moving > 5, "$drawing gap must move")
        }
        assertEquals(compositionCount, compositions)
        assertEquals(layoutCount, layouts)
    }

    private fun red(c: Color) = c.red > c.green + 0.1f && c.red > c.blue + 0.1f
    private fun green(c: Color) = c.green > c.red + 0.15f && c.green > c.blue + 0.15f
    private fun count(pixels: PixelMap, predicate: (Color) -> Boolean) = (0 until pixels.height).sumOf { y -> (0 until pixels.width).count { x -> predicate(pixels[x, y]) } }
    private fun samePixels(first: PixelMap, second: PixelMap) {
        assertEquals(first.width, second.width); assertEquals(first.height, second.height)
        for (y in 0 until first.height) for (x in 0 until first.width) assertEquals(first[x, y], second[x, y], "$x/$y")
    }
}

@Composable
private fun IndicatorCells(state: ConversationIndicatorState) {
    CompositionLocalProvider(LocalDensity provides Density(2f)) { GromozekaTheme {
        Row {
            for (drawing in ConversationIndicatorDrawing.entries) {
                Box(Modifier.size(60.dp).background(Color(0xFF26313B)).testTag("cell-${drawing.name}"), contentAlignment = Alignment.Center) {
                    ConversationIndicator(state, drawing, "Activity", Modifier.testTag(drawing.name))
                }
            }
        }
    } }
}
