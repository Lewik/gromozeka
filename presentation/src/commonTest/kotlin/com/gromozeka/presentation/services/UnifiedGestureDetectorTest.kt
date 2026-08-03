package com.gromozeka.presentation.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedGestureDetectorTest {
    @Test
    fun `quick click cancels capture and stops speech after double click window`() = runTest {
        val handler = RecordingPttEventHandler()
        val detector = detector(handler)

        detector.onGestureDown()
        detector.onGestureUp()
        runCurrent()
        advanceTimeBy(400)
        runCurrent()

        assertEquals(
            listOf("event:BUTTON_DOWN", "cancel", "event:SINGLE_CLICK"),
            handler.actions,
        )
    }

    @Test
    fun `quick double click interrupts without emitting single click`() = runTest {
        val handler = RecordingPttEventHandler()
        val detector = detector(handler)

        detector.onGestureDown()
        detector.onGestureUp()
        runCurrent()
        advanceTimeBy(100)
        detector.onGestureDown()
        detector.onGestureUp()
        runCurrent()
        advanceTimeBy(400)
        runCurrent()

        assertEquals(
            listOf(
                "event:BUTTON_DOWN",
                "cancel",
                "event:BUTTON_DOWN",
                "cancel",
                "event:DOUBLE_CLICK",
            ),
            handler.actions,
        )
    }

    @Test
    fun `hold records until physical release`() = runTest {
        val handler = RecordingPttEventHandler()
        val detector = detector(handler)

        detector.onGestureDown()
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        detector.onGestureUp()
        runCurrent()

        assertEquals(
            listOf("event:BUTTON_DOWN", "event:SINGLE_PUSH", "release"),
            handler.actions,
        )
    }

    @Test
    fun `cancelled hold cancels capture instead of releasing it`() = runTest {
        val handler = RecordingPttEventHandler()
        val detector = detector(handler)

        detector.onGestureDown()
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        detector.cancelGesture()
        runCurrent()

        assertEquals(
            listOf("event:BUTTON_DOWN", "event:SINGLE_PUSH", "cancel"),
            handler.actions,
        )
    }

    @Test
    fun `release preserves hold when threshold timer is delayed`() = runTest {
        val handler = RecordingPttEventHandler()
        var now = 0L
        val detector = UnifiedGestureDetector(handler, this) { now }

        detector.onGestureDown()
        runCurrent()
        now = 180
        detector.onGestureUp()
        runCurrent()

        assertEquals(
            listOf("event:BUTTON_DOWN", "event:SINGLE_PUSH", "release"),
            handler.actions,
        )
    }

    private fun TestScope.detector(handler: PttEventHandler): UnifiedGestureDetector =
        UnifiedGestureDetector(handler, this) { testScheduler.currentTime }

    private class RecordingPttEventHandler : PttEventHandler {
        val actions = mutableListOf<String>()

        override fun initialize() = Unit

        override suspend fun handlePTTEvent(event: PTTEvent) {
            actions += "event:$event"
        }

        override suspend fun handlePTTRelease() {
            actions += "release"
        }

        override suspend fun handlePTTCancel() {
            actions += "cancel"
        }
    }
}
