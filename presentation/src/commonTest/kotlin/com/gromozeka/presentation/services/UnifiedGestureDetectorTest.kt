package com.gromozeka.presentation.services

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class UnifiedGestureDetectorTest {
    @Test
    fun `quick click cancels capture and stops speech after double click window`() = runBlocking {
        val handler = RecordingPttEventHandler()
        val detector = UnifiedGestureDetector(handler, this)

        detector.onGestureDown()
        detector.onGestureUp()
        delay(450)

        assertEquals(
            listOf("event:BUTTON_DOWN", "cancel", "event:SINGLE_CLICK"),
            handler.actions,
        )
    }

    @Test
    fun `quick double click interrupts without emitting single click`() = runBlocking {
        val handler = RecordingPttEventHandler()
        val detector = UnifiedGestureDetector(handler, this)

        detector.onGestureDown()
        detector.onGestureUp()
        detector.onGestureDown()
        detector.onGestureUp()
        delay(450)

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
    fun `hold records until physical release`() = runBlocking {
        val handler = RecordingPttEventHandler()
        val detector = UnifiedGestureDetector(handler, this)

        detector.onGestureDown()
        delay(180)
        detector.onGestureUp()
        yield()

        assertEquals(
            listOf("event:BUTTON_DOWN", "event:SINGLE_PUSH", "release"),
            handler.actions,
        )
    }

    @Test
    fun `cancelled hold cancels capture instead of releasing it`() = runBlocking {
        val handler = RecordingPttEventHandler()
        val detector = UnifiedGestureDetector(handler, this)

        detector.onGestureDown()
        delay(180)
        detector.cancelGesture()
        yield()

        assertEquals(
            listOf("event:BUTTON_DOWN", "event:SINGLE_PUSH", "cancel"),
            handler.actions,
        )
    }

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
