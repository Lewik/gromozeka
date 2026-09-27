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
    fun `short click stops speech immediately without touching capture`() = runTest {
        val handler = RecordingPttEventHandler()
        val detector = detector(handler)
        detector.click()
        runCurrent()
        assertEquals(listOf("event:SINGLE_CLICK"), handler.actions)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("event:SINGLE_CLICK"), handler.actions)
    }

    @Test
    fun `second click adds interruption without starting capture`() = runTest {
        val handler = RecordingPttEventHandler()
        val detector = detector(handler)
        detector.click()
        runCurrent()
        advanceTimeBy(100)
        detector.click()
        runCurrent()
        assertEquals(listOf("event:SINGLE_CLICK", "event:DOUBLE_CLICK"), handler.actions)
    }

    @Test
    fun `separate clicks do not become a double click`() = runTest {
        val handler = RecordingPttEventHandler()
        val detector = detector(handler)
        detector.click()
        runCurrent()
        advanceTimeBy(401)
        detector.click()
        runCurrent()
        assertEquals(listOf("event:SINGLE_CLICK", "event:SINGLE_CLICK"), handler.actions)
    }

    @Test
    fun `hold records until physical release`() = runTest {
        val handler = RecordingPttEventHandler()
        val detector = detector(handler)
        detector.onGestureDown()
        advanceTimeBy(150)
        runCurrent()
        detector.onGestureUp()
        runCurrent()
        assertEquals(listOf("event:SINGLE_PUSH", "release"), handler.actions)
    }

    @Test
    fun `repeated key down does not restart a hold`() = runTest {
        val handler = RecordingPttEventHandler()
        val detector = detector(handler)
        detector.onGestureDown()
        advanceTimeBy(100)
        detector.onGestureDown()
        advanceTimeBy(100)
        runCurrent()
        detector.onGestureDown()
        detector.onGestureUp()
        runCurrent()
        assertEquals(listOf("event:SINGLE_PUSH", "release"), handler.actions)
    }

    @Test
    fun `cancelled hold cancels capture instead of sending it`() = runTest {
        val handler = RecordingPttEventHandler()
        val detector = detector(handler)
        detector.onGestureDown()
        advanceTimeBy(150)
        runCurrent()
        detector.cancelGesture()
        runCurrent()
        assertEquals(listOf("event:SINGLE_PUSH", "cancel"), handler.actions)
    }

    @Test
    fun `cancelled short press performs no action`() = runTest {
        val handler = RecordingPttEventHandler()
        val detector = detector(handler)
        detector.onGestureDown()
        detector.cancelGesture()
        detector.onGestureUp()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(emptyList(), handler.actions)
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
        assertEquals(listOf("event:SINGLE_PUSH", "release"), handler.actions)
    }

    @Test
    fun `unavailable microphone still allows single and double interruption gestures`() = runTest {
        val handler = RecordingPttEventHandler(canRecord = false)
        val detector = detector(handler)
        detector.click()
        advanceTimeBy(100)
        detector.click()
        runCurrent()
        assertEquals(listOf("event:SINGLE_CLICK", "event:DOUBLE_CLICK"), handler.actions)
    }

    @Test
    fun `hold with unavailable microphone does not start or release recording`() = runTest {
        val handler = RecordingPttEventHandler(canRecord = false)
        val detector = detector(handler)
        detector.onGestureDown()
        advanceTimeBy(500)
        runCurrent()
        detector.onGestureUp()
        runCurrent()
        assertEquals(emptyList(), handler.actions)
    }

    private fun UnifiedGestureDetector.click() {
        onGestureDown()
        onGestureUp()
    }

    private fun TestScope.detector(handler: PttEventHandler): UnifiedGestureDetector =
        UnifiedGestureDetector(handler, this) { testScheduler.currentTime }

    private class RecordingPttEventHandler(override val canRecord: Boolean = true) : PttEventHandler {
        val actions = mutableListOf<String>()
        override fun initialize() = Unit
        override suspend fun handlePTTEvent(event: PTTEvent) { actions += "event:$event" }
        override suspend fun handlePTTRelease() { actions += "release" }
        override suspend fun handlePTTCancel() { actions += "cancel" }
    }
}
