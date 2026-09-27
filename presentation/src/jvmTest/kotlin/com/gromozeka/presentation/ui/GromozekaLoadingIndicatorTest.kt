package com.gromozeka.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GromozekaLoadingIndicatorTest {
    @Test
    fun gapUpdatesDrawWithoutRecompositionLayoutOrParentRedraw() = runComposeUiTest {
        val phase = mutableFloatStateOf(0f)
        var compositions = 0
        var layouts = 0
        var parentDraws = 0
        var bulbDraws = 0
        setContent {
            SideEffect { compositions++ }
            Box(Modifier.layout { measurable, constraints ->
                layouts++
                val child = measurable.measure(constraints)
                layout(child.width, child.height) { child.place(0, 0) }
            }.drawWithContent { parentDraws++; drawContent() }) {
                GromozekaBulb(Modifier.size(96.dp), phase = { bulbDraws++; phase.floatValue })
            }
        }
        waitForIdle()
        val initialCompositions = compositions
        val initialLayouts = layouts
        val initialParentDraws = parentDraws
        val initialBulbDraws = bulbDraws
        repeat(8) { index ->
            runOnIdle { phase.floatValue = (index + 1) / 10f }
            waitForIdle()
        }
        runOnIdle {
            assertEquals(initialCompositions, compositions, "Only draw should observe the phase")
            assertEquals(initialLayouts, layouts, "The animation must not invalidate layout")
            assertEquals(initialParentDraws, parentDraws, "The draw layer must isolate the surrounding UI")
            assertTrue(bulbDraws > initialBulbDraws)
        }
    }

    @Test
    fun gapMovesButBlueBaseDoesNotChange() = runComposeUiTest {
        val phase = mutableFloatStateOf(0f)
        setContent {
            Box(Modifier.background(Color(0xFF202020))) {
                GromozekaBulb(Modifier.size(128.dp).testTag("bulb"), phase = { phase.floatValue })
            }
        }
        val before = onNodeWithTag("bulb").captureToImage().toPixelMap()
        runOnIdle { phase.floatValue = 0.35f }
        val after = onNodeWithTag("bulb").captureToImage().toPixelMap()
        var changedOrangePixels = 0
        var bluePixels = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            val a = before[x, y]
            val b = after[x, y]
            if (a.blue > a.red + 0.2f || b.blue > b.red + 0.2f) {
                bluePixels++
                assertEquals(a, b, "Blue base must stay still at $x,$y")
            } else if (a != b) changedOrangePixels++
        }
        assertTrue(bluePixels > 100)
        assertTrue(changedOrangePixels > 100)
    }

    @Test
    fun readyBulbHasGreenCheckWithoutAnimationOrProgressSemantics() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { GromozekaReadyIndicator(Modifier.size(96.dp).testTag("ready")) }
        val node = onNodeWithTag("ready")
        node.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
        val before = node.captureToImage().toPixelMap()
        mainClock.advanceTimeBy(3_000)
        val after = node.captureToImage().toPixelMap()
        var greenPixels = 0
        var greenMinX = before.width
        var greenMaxX = -1
        var blueMinX = before.width
        var blueMaxX = -1
        for (y in 0 until before.height) for (x in 0 until before.width) {
            val pixel = before[x, y]
            if (pixel.green > pixel.red + 0.15f && pixel.green > pixel.blue + 0.15f) {
                greenPixels++
                greenMinX = minOf(greenMinX, x)
                greenMaxX = maxOf(greenMaxX, x)
            }
            if (pixel.blue > pixel.red + 0.2f && pixel.blue > pixel.green + 0.1f) {
                blueMinX = minOf(blueMinX, x)
                blueMaxX = maxOf(blueMaxX, x)
            }
            assertEquals(pixel, after[x, y], "Ready indicator must stay static")
        }
        assertTrue(greenPixels > 50, "The bulb should contain a visible green check")
        assertTrue(greenMinX >= blueMinX && greenMaxX <= blueMaxX, "Check must fit within the bulb base's width")
    }

    @Test
    fun standardAnimationMovesAndStaticModeDoesNot() = runComposeUiTest {
        mainClock.autoAdvance = false
        val animated = androidx.compose.runtime.mutableStateOf(true)
        setContent { GromozekaLoadingIndicator(Modifier.size(96.dp).testTag("loader"), animated.value) }
        mainClock.advanceTimeBy(100)
        val before = onNodeWithTag("loader").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(700)
        val after = onNodeWithTag("loader").captureToImage().toPixelMap()
        assertTrue((0 until before.height).any { y -> (0 until before.width).any { x -> before[x, y] != after[x, y] } })
        runOnIdle { animated.value = false }
        mainClock.advanceTimeByFrame()
        val stopped = onNodeWithTag("loader").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(1_000)
        val still = onNodeWithTag("loader").captureToImage().toPixelMap()
        for (y in 0 until stopped.height) for (x in 0 until stopped.width) assertEquals(stopped[x, y], still[x, y])
    }
}
