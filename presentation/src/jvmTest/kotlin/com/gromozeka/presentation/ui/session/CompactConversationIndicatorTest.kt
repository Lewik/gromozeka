package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.service.AiRequestWaitReason
import com.gromozeka.presentation.ui.GromozekaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompactConversationIndicatorTest {
    private val clear = ConversationIndicatorState(ConversationActivity.READY, ConversationPressure.NORMAL, false, false, emptySet())

    @Test
    fun quietUnreadUsesOnlyTheDotAndOtherSignalsKeepTheStatus() {
        for (activity in ConversationActivity.entries) for (pressure in ConversationPressure.entries) {
            for (unread in listOf(false, true)) for (problem in listOf(false, true)) {
                for (waits in listOf(emptySet(), setOf(AiRequestWaitReason.TPM), setOf(AiRequestWaitReason.CONCURRENCY))) {
                    val state = ConversationIndicatorState(activity, pressure, unread, problem, waits)
                    val expected = when {
                        state.copy(unread = false) != clear -> CompactIndicatorContent.STATUS
                        unread -> CompactIndicatorContent.UNREAD_ONLY
                        else -> CompactIndicatorContent.HIDDEN
                    }
                    assertEquals(CompactIndicatorContent.STATUS, CompactIndicatorMode.ALWAYS.content(state))
                    assertEquals(expected, CompactIndicatorMode.ACTIVITY_OR_ATTENTION.content(state), state.toString())
                }
            }
        }
    }

    @Test
    fun changingModesRemovesBothCircleAndCheckButLeavesTheFullBulbUnchanged() = runComposeUiTest {
        val mode = mutableStateOf(CompactIndicatorMode.ALWAYS)
        setContent { GromozekaTheme { Row {
            Box(Modifier.size(40.dp).background(Color.Black).testTag("compact-cell"), contentAlignment = Alignment.Center) {
                CompactConversationIndicator(clear, "Compact state", Modifier.testTag("compact"), mode.value)
            }
            Box(Modifier.size(40.dp).background(Color.Black).testTag("full-cell"), contentAlignment = Alignment.Center) {
                ConversationIndicator(clear, ConversationIndicatorDrawing.FULL, "Full state")
            }
        } } }
        onNodeWithTag("compact").assertWidthIsEqualTo(14.dp).assertHeightIsEqualTo(14.dp)
        val frame = onNodeWithTag("compact").fetchSemanticsNode().boundsInRoot
        val compact = onNodeWithTag("compact-cell").captureToImage().toPixelMap()
        val full = onNodeWithTag("full-cell").captureToImage().toPixelMap()
        assertTrue((0 until compact.height).any { y -> (0 until compact.width).any { x -> compact[x, y] != Color.Black } })
        runOnIdle { mode.value = CompactIndicatorMode.ACTIVITY_OR_ATTENTION }
        onNodeWithContentDescription("Compact state").assertDoesNotExist()
        assertEquals(frame, onNodeWithTag("compact").fetchSemanticsNode().boundsInRoot)
        val hidden = onNodeWithTag("compact-cell").captureToImage().toPixelMap()
        val stillFull = onNodeWithTag("full-cell").captureToImage().toPixelMap()
        for (y in 0 until hidden.height) for (x in 0 until hidden.width) {
            assertEquals(Color.Black, hidden[x, y], "Hidden indicator left pixels at $x/$y")
            assertEquals(full[x, y], stillFull[x, y], "Full bulb changed at $x/$y")
        }
        runOnIdle { mode.value = CompactIndicatorMode.ALWAYS }
        onNodeWithContentDescription("Compact state").assertExists()
        val restored = onNodeWithTag("compact-cell").captureToImage().toPixelMap()
        for (y in 0 until compact.height) for (x in 0 until compact.width) assertEquals(compact[x, y], restored[x, y])
    }

    @Test
    fun quietUnreadKeepsOnlyTheSameCenteredDotWithoutChangingTheFullBulb() = runComposeUiTest {
        mainClock.autoAdvance = false
        val unread = clear.copy(unread = true)
        val state = mutableStateOf(unread)
        val mode = mutableStateOf(CompactIndicatorMode.ALWAYS)
        setContent { GromozekaTheme { Row {
            Box(Modifier.size(40.dp).background(Color.Black).testTag("compact-cell"), contentAlignment = Alignment.Center) {
                CompactConversationIndicator(state.value, "Compact unread", Modifier.testTag("compact"), mode.value)
            }
            Box(Modifier.size(40.dp).background(Color.Black).testTag("full-cell"), contentAlignment = Alignment.Center) {
                ConversationIndicator(unread, ConversationIndicatorDrawing.FULL, "Full unread")
            }
        } } }
        val frame = onNodeWithTag("compact").fetchSemanticsNode().boundsInRoot
        val original = onNodeWithTag("compact-cell").captureToImage().toPixelMap()
        val full = onNodeWithTag("full-cell").captureToImage().toPixelMap()
        runOnIdle { mode.value = CompactIndicatorMode.ACTIVITY_OR_ATTENTION }
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Compact unread").assertExists()
        val dotOnly = onNodeWithTag("compact-cell").captureToImage().toPixelMap()
        val stillFull = onNodeWithTag("full-cell").captureToImage().toPixelMap()
        val radius = dotOnly.width * 4f / 40f
        var dotPixels = 0
        for (y in 0 until dotOnly.height) for (x in 0 until dotOnly.width) {
            val dx = x + 0.5f - dotOnly.width / 2f
            val dy = y + 0.5f - dotOnly.height / 2f
            val expected = if (dx * dx + dy * dy <= radius * radius) original[x, y] else Color.Black
            assertEquals(expected, dotOnly[x, y], "Only the original unread dot may remain at $x/$y")
            assertEquals(full[x, y], stillFull[x, y], "Full bulb must not change")
            if (dotOnly[x, y] != Color.Black) dotPixels++
        }
        assertTrue(dotPixels > 0)
        assertEquals(frame, onNodeWithTag("compact").fetchSemanticsNode().boundsInRoot)
        onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)

        runOnIdle { mode.value = CompactIndicatorMode.ALWAYS }
        mainClock.advanceTimeByFrame()
        val restored = onNodeWithTag("compact-cell").captureToImage().toPixelMap()
        for (y in 0 until original.height) for (x in 0 until original.width) assertEquals(original[x, y], restored[x, y])
        runOnIdle { mode.value = CompactIndicatorMode.ACTIVITY_OR_ATTENTION; state.value = clear }
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Compact unread").assertDoesNotExist()
        val hidden = onNodeWithTag("compact-cell").captureToImage().toPixelMap()
        for (y in 0 until hidden.height) for (x in 0 until hidden.width) assertEquals(Color.Black, hidden[x, y])
        assertEquals(frame, onNodeWithTag("compact").fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun hiddenIndicatorReappearsForWorkUnreadProblemsPressureAndUnknownState() = runComposeUiTest {
        mainClock.autoAdvance = false
        val state = mutableStateOf(clear)
        setContent { GromozekaTheme {
            Box(Modifier.size(40.dp).background(Color.Black).testTag("cell"), contentAlignment = Alignment.Center) {
                CompactConversationIndicator(state.value, "Activity or attention", mode = CompactIndicatorMode.ACTIVITY_OR_ATTENTION)
            }
        } }
        for (value in listOf(
            clear.copy(activity = ConversationActivity.WORKING),
            clear.copy(activity = ConversationActivity.PAUSED),
            clear.copy(activity = ConversationActivity.STOPPING),
            clear.copy(unread = true),
            clear.copy(hasTurnProblem = true),
            clear.copy(pressure = ConversationPressure.GLOBAL_WAIT),
            clear.copy(pressure = ConversationPressure.LOCAL_WAIT),
            clear.copy(pressure = ConversationPressure.UNKNOWN),
            clear.copy(activity = ConversationActivity.UNKNOWN),
        )) {
            runOnIdle { state.value = value }
            mainClock.advanceTimeByFrame()
            onNodeWithContentDescription("Activity or attention").assertExists()
            val visible = onNodeWithTag("cell").captureToImage().toPixelMap()
            assertTrue((0 until visible.height).any { y -> (0 until visible.width).any { x -> visible[x, y] != Color.Black } }, value.toString())
            runOnIdle { state.value = clear }
            mainClock.advanceTimeByFrame()
            onNodeWithContentDescription("Activity or attention").assertDoesNotExist()
            val hidden = onNodeWithTag("cell").captureToImage().toPixelMap()
            for (y in 0 until hidden.height) for (x in 0 until hidden.width) assertEquals(Color.Black, hidden[x, y])
        }
    }
}
