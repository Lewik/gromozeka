package com.gromozeka.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GromozekaSimpleLoadingIndicatorTest {
    @Test
    fun growsFromCenterAndShrinksBeforeDisposal() = runComposeUiTest {
        mainClock.autoAdvance = false
        val tone = mutableStateOf<LoadingIndicatorTone?>(null)
        setContent {
            Box(Modifier.size(48.dp).background(Color.Black).testTag("slot"), contentAlignment = Alignment.Center) {
                GromozekaSimpleLoadingIndicator(Modifier.size(32.dp).testTag("ring"), tone.value)
            }
        }
        val slot = onNodeWithTag("slot")
        val slotBounds = slot.fetchSemanticsNode().boundsInRoot
        onNodeWithTag("ring").assertDoesNotExist()
        runOnIdle { tone.value = LoadingIndicatorTone.FOREGROUND }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(64)
        val small = radius(slot.captureToImage().toPixelMap())
        mainClock.advanceTimeBy(48)
        val growing = radius(slot.captureToImage().toPixelMap())
        mainClock.advanceTimeBy(240)
        val full = radius(slot.captureToImage().toPixelMap())
        assertTrue(small > 0f && growing > small && full > growing, "$small < $growing < $full")
        assertEquals(slotBounds, slot.fetchSemanticsNode().boundsInRoot)

        runOnIdle { tone.value = null }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(64)
        onNodeWithTag("ring").assertExists()
        val shrinking = radius(slot.captureToImage().toPixelMap())
        assertTrue(shrinking > 0f && shrinking < full, "$shrinking < $full")
        mainClock.advanceTimeBy(240)
        onNodeWithTag("ring").assertDoesNotExist()
        assertEquals(0f, radius(slot.captureToImage().toPixelMap()))
        mainClock.advanceTimeBy(3_000)
        onNodeWithTag("ring").assertDoesNotExist()
        assertEquals(slotBounds, slot.fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun visibilityFramesDoNotRecomposeRelayoutOrRedrawTheParent() = runComposeUiTest {
        mainClock.autoAdvance = false
        val tone = mutableStateOf<LoadingIndicatorTone?>(LoadingIndicatorTone.FOREGROUND)
        var compositions = 0
        var layouts = 0
        var parentDraws = 0
        setContent {
            SideEffect { compositions++ }
            Box(Modifier.size(48.dp).layout { measurable, constraints ->
                layouts++
                val child = measurable.measure(constraints)
                layout(child.width, child.height) { child.place(0, 0) }
            }.drawWithContent { parentDraws++; drawContent() }, contentAlignment = Alignment.Center) {
                GromozekaSimpleLoadingIndicator(Modifier.size(32.dp).testTag("ring"), tone.value)
            }
        }
        for (exiting in listOf(false, true)) {
            if (exiting) runOnIdle { tone.value = null }
            mainClock.advanceTimeBy(48)
            waitForIdle()
            val before = Triple(compositions, layouts, parentDraws)
            repeat(4) { mainClock.advanceTimeByFrame(); waitForIdle() }
            assertEquals(before, Triple(compositions, layouts, parentDraws), "Only the ring's graphics layer should change")
            mainClock.advanceTimeBy(240)
        }
        onNodeWithTag("ring").assertDoesNotExist()
        val idle = Triple(compositions, layouts, parentDraws)
        mainClock.advanceTimeBy(3_000)
        waitForIdle()
        assertEquals(idle, Triple(compositions, layouts, parentDraws), "Hidden loader must not keep invalidating UI")
    }

    @Test
    fun interruptedExitKeepsItsToneAndReversesWithoutRestartingAtZero() = runComposeUiTest {
        mainClock.autoAdvance = false
        val tone = mutableStateOf<LoadingIndicatorTone?>(LoadingIndicatorTone.BACKGROUND)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(onSurfaceVariant = Color.Gray)) {
                Box(Modifier.size(48.dp).background(Color.Black).testTag("slot"), contentAlignment = Alignment.Center) {
                    GromozekaSimpleLoadingIndicator(Modifier.size(32.dp).testTag("ring"), tone.value)
                }
            }
        }
        val slot = onNodeWithTag("slot")
        mainClock.advanceTimeBy(240)
        val full = radius(slot.captureToImage().toPixelMap()) { it.red > 0.2f }
        runOnIdle { tone.value = null }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(64)
        val exiting = slot.captureToImage().toPixelMap()
        val shrinking = radius(exiting) { it.red > 0.2f }
        assertTrue(shrinking in 1f..<full)
        for (y in 0 until exiting.height) for (x in 0 until exiting.width) {
            val pixel = exiting[x, y]
            assertTrue(abs(pixel.red - pixel.green) < 0.02f && abs(pixel.green - pixel.blue) < 0.02f,
                "A background ring must not flash orange during exit")
        }
        runOnIdle { tone.value = LoadingIndicatorTone.BACKGROUND }
        mainClock.advanceTimeByFrame()
        val reversed = radius(slot.captureToImage().toPixelMap()) { it.red > 0.2f }
        // The previous tween may advance one frame before recomposition applies the new target.
        assertTrue(reversed >= shrinking / 2f && reversed <= full + 1f, "$shrinking -> $reversed must not restart at zero")
        var previous = reversed
        repeat(4) {
            mainClock.advanceTimeByFrame()
            val growing = radius(slot.captureToImage().toPixelMap()) { it.red > 0.2f }
            assertTrue(growing >= previous - 0.5f, "After reversal the ring must grow without spring momentum: $previous -> $growing")
            previous = growing
        }
        mainClock.advanceTimeBy(500)
        assertTrue(abs(full - radius(slot.captureToImage().toPixelMap()) { it.red > 0.2f }) < 1f)

        runOnIdle { tone.value = LoadingIndicatorTone.FOREGROUND }
        mainClock.advanceTimeByFrame()
        assertTrue(radius(slot.captureToImage().toPixelMap()) >= full - 1f, "A tone change must not replay the entrance")
        onAllNodesWithTag("ring").assertCountEquals(1)
    }

    @Test
    fun interruptionDuringEntranceStillAnimatesOutAndCanStartAgain() = runComposeUiTest {
        mainClock.autoAdvance = false
        val tone = mutableStateOf<LoadingIndicatorTone?>(LoadingIndicatorTone.FOREGROUND)
        setContent {
            Box(Modifier.size(48.dp).background(Color.Black).testTag("slot"), contentAlignment = Alignment.Center) {
                GromozekaSimpleLoadingIndicator(Modifier.size(32.dp).testTag("ring"), tone.value)
            }
        }
        mainClock.advanceTimeBy(80)
        val before = radius(onNodeWithTag("slot").captureToImage().toPixelMap())
        assertTrue(before > 0f)
        runOnIdle { tone.value = null }
        mainClock.advanceTimeByFrame()
        onNodeWithTag("ring").assertExists()
        assertTrue(radius(onNodeWithTag("slot").captureToImage().toPixelMap()) > 0f,
            "An interrupted entrance must shrink, not disappear immediately")
        mainClock.advanceTimeBy(1_000)
        onNodeWithTag("ring").assertDoesNotExist()
        runOnIdle { tone.value = LoadingIndicatorTone.FOREGROUND }
        mainClock.advanceTimeBy(240)
        onNodeWithTag("ring").assertExists()
        assertTrue(radius(onNodeWithTag("slot").captureToImage().toPixelMap()) > before)
    }

    private fun radius(image: PixelMap, visible: (Color) -> Boolean = {
        // The scaled stroke is subpixel during entry; detect its hue, not full-opacity pixels.
        it.red > 0.04f && it.red > it.green * 1.3f && it.green > it.blue * 2f
    }): Float {
        var radiusSquared = 0f
        for (y in 0 until image.height) for (x in 0 until image.width) if (visible(image[x, y])) {
            val dx = x + 0.5f - image.width / 2f
            val dy = y + 0.5f - image.height / 2f
            radiusSquared = maxOf(radiusSquared, dx * dx + dy * dy)
        }
        return sqrt(radiusSquared)
    }
}
