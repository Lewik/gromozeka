package com.gromozeka.presentation.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class HoldToTalkShortcutControllerTest {
    @Test
    fun quickPressCancelsPreparedRecording() = runTest {
        val handler = RecordingPttEventHandler()
        var now = 0L
        val controller = HoldToTalkShortcutController(handler, this) { now }

        controller.onPressed()
        runCurrent()
        now = 100L
        controller.onReleased()
        runCurrent()

        assertEquals(listOf("BUTTON_DOWN", "cancel"), handler.events)
    }

    @Test
    fun holdStartsAndCommitsRecording() = runTest {
        val handler = RecordingPttEventHandler()
        var now = 0L
        val controller = HoldToTalkShortcutController(handler, this) { now }

        controller.onPressed()
        runCurrent()
        now = 151L
        advanceTimeBy(151)
        runCurrent()
        controller.onReleased()
        runCurrent()

        assertEquals(listOf("BUTTON_DOWN", "SINGLE_PUSH", "release"), handler.events)
    }

    @Test
    fun settingsChangeCancelsActiveHold() = runTest {
        val handler = RecordingPttEventHandler()
        val controller = HoldToTalkShortcutController(handler, this)

        controller.onPressed()
        runCurrent()
        controller.cancel()
        runCurrent()

        assertEquals(listOf("BUTTON_DOWN", "cancel"), handler.events)
    }

    private class RecordingPttEventHandler : PttEventHandler {
        val events = mutableListOf<String>()

        override fun initialize() = Unit

        override suspend fun handlePTTEvent(event: PTTEvent) {
            events += event.name
        }

        override suspend fun handlePTTRelease() {
            events += "release"
        }

        override suspend fun handlePTTCancel() {
            events += "cancel"
        }
    }
}
